-- =====================================================================
-- VareSync — V8: devoluções e trocas, parte A (Fase 13: F13.1)
--   1. Tabelas devolucao e devolucao_item; um trigger confere que a devolução não passa do que foi vendido.
--   2. Movimento de estoque DEVOLUCAO_VENDA (+), com origem em movimento_estoque.devolucao_item_id; os tipos que já
--      existiam passam a exigir devolucao_item_id nulo.
--   3. Crédito do cliente: cliente_credito_movimento.devolucao_id; CREDITO_DEVOLUCAO exige a devolução; uma venda
--      pode ter várias devoluções, cada uma com o seu estorno de cashback.
--   4. vw_caixa_resumo com as devoluções em dinheiro (no fim) descontadas do dinheiro esperado.
--   5. Permissão DEVOLUCAO e configuração devolucao.prazo_dias (seções 0.3 e 0.4 das ampliações).
-- =====================================================================

-- 1. Devolução ---------------------------------------------------------------------

CREATE TABLE devolucao (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venda_id        BIGINT        NOT NULL,
    usuario_id      BIGINT        NOT NULL,
    data_hora       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    motivo          VARCHAR(200)  NOT NULL,
    forma_reembolso VARCHAR(20)   NOT NULL,
    valor_total     NUMERIC(12,2) NOT NULL,
    cliente_id      BIGINT,
    liberado_por    BIGINT,
    observacao      VARCHAR(500),
    caixa_sessao_id BIGINT,
    CONSTRAINT fk_dev_venda        FOREIGN KEY (venda_id)        REFERENCES venda (id),
    CONSTRAINT fk_dev_usuario      FOREIGN KEY (usuario_id)      REFERENCES usuario (id),
    CONSTRAINT fk_dev_cliente      FOREIGN KEY (cliente_id)      REFERENCES cliente (id),
    CONSTRAINT fk_dev_liberado_por FOREIGN KEY (liberado_por)    REFERENCES usuario (id),
    CONSTRAINT fk_dev_caixa        FOREIGN KEY (caixa_sessao_id) REFERENCES caixa_sessao (id),
    CONSTRAINT ck_dev_forma   CHECK (forma_reembolso IN ('DINHEIRO', 'CREDITO_CLIENTE', 'OUTRO')),
    CONSTRAINT ck_dev_motivo  CHECK (btrim(motivo) <> ''),
    CONSTRAINT ck_dev_valor   CHECK (valor_total >= 0),
    CONSTRAINT ck_dev_caixa   CHECK (forma_reembolso <> 'DINHEIRO' OR caixa_sessao_id IS NOT NULL),
    CONSTRAINT ck_dev_cliente CHECK (forma_reembolso <> 'CREDITO_CLIENTE' OR cliente_id IS NOT NULL)
);
CREATE INDEX ix_dev_venda     ON devolucao (venda_id);
CREATE INDEX ix_dev_data_hora ON devolucao (data_hora);
CREATE INDEX ix_dev_caixa     ON devolucao (caixa_sessao_id);
CREATE INDEX ix_dev_cliente   ON devolucao (cliente_id);
COMMENT ON TABLE  devolucao                 IS 'Devolução de itens de uma venda finalizada (F13). Imutável depois de gravada.';
COMMENT ON COLUMN devolucao.forma_reembolso IS 'DINHEIRO sai da gaveta do caixa; CREDITO_CLIENTE vira vale-troca; OUTRO é estorno feito fora do sistema.';
COMMENT ON COLUMN devolucao.liberado_por    IS 'Quem liberou a devolução (sem a permissão DEVOLUCAO ou fora do prazo); nulo sem liberação.';
COMMENT ON COLUMN devolucao.caixa_sessao_id IS 'Sessão de caixa de onde saiu o dinheiro (obrigatória no reembolso em dinheiro).';

CREATE TABLE devolucao_item (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    devolucao_id  BIGINT        NOT NULL,
    venda_item_id BIGINT        NOT NULL,
    quantidade    NUMERIC(14,3) NOT NULL,
    valor         NUMERIC(12,2) NOT NULL,
    custo_total   NUMERIC(12,2) NOT NULL,
    volta_estoque BOOLEAN       NOT NULL,
    CONSTRAINT fk_di_devolucao  FOREIGN KEY (devolucao_id)  REFERENCES devolucao (id),
    CONSTRAINT fk_di_venda_item FOREIGN KEY (venda_item_id) REFERENCES venda_item (id),
    CONSTRAINT ck_di_quantidade CHECK (quantidade > 0),
    CONSTRAINT ck_di_valores    CHECK (valor >= 0 AND custo_total >= 0),
    CONSTRAINT uq_di_item       UNIQUE (devolucao_id, venda_item_id)
);
CREATE INDEX ix_di_venda_item ON devolucao_item (venda_item_id);
COMMENT ON TABLE  devolucao_item               IS 'Item devolvido: parte (ou todo) de um item da venda, com o valor e o custo proporcionais.';
COMMENT ON COLUMN devolucao_item.volta_estoque IS 'Falso = avariado: a devolução entra e sai do estoque como perda na mesma transação.';

-- Última barreira (o service confere antes, com a venda travada): o item é da venda da devolução, não foi cancelado
-- e a soma das devoluções não passa da quantidade vendida.
CREATE FUNCTION fn_devolucao_item_confere() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_venda_item  venda_item%ROWTYPE;
    v_venda_id    BIGINT;
    v_devolvida   NUMERIC(14,3);
BEGIN
    SELECT * INTO v_venda_item FROM venda_item WHERE id = NEW.venda_item_id;
    SELECT venda_id INTO v_venda_id FROM devolucao WHERE id = NEW.devolucao_id;
    IF v_venda_item.venda_id IS DISTINCT FROM v_venda_id THEN
        RAISE EXCEPTION 'O item % não pertence à venda da devolução %.', NEW.venda_item_id, NEW.devolucao_id;
    END IF;
    IF v_venda_item.cancelado THEN
        RAISE EXCEPTION 'Item cancelado durante a venda não pode ser devolvido.';
    END IF;
    SELECT coalesce(sum(quantidade), 0) INTO v_devolvida FROM devolucao_item WHERE venda_item_id = NEW.venda_item_id;
    IF v_devolvida + NEW.quantidade > v_venda_item.quantidade THEN
        RAISE EXCEPTION 'A quantidade devolvida passaria da quantidade vendida (vendida %, já devolvida %, nesta devolução %).',
            v_venda_item.quantidade, v_devolvida, NEW.quantidade;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_devolucao_item_confere
    BEFORE INSERT ON devolucao_item
    FOR EACH ROW EXECUTE FUNCTION fn_devolucao_item_confere();

CREATE FUNCTION fn_devolucao_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Devoluções não podem ser alteradas nem excluídas.';
END $$;

CREATE TRIGGER trg_devolucao_imutavel
    BEFORE UPDATE OR DELETE ON devolucao
    FOR EACH ROW EXECUTE FUNCTION fn_devolucao_imutavel();
CREATE TRIGGER trg_devolucao_item_imutavel
    BEFORE UPDATE OR DELETE ON devolucao_item
    FOR EACH ROW EXECUTE FUNCTION fn_devolucao_imutavel();

-- 2. Estoque -----------------------------------------------------------------------

ALTER TABLE movimento_estoque
    ADD COLUMN devolucao_item_id BIGINT,
    ADD CONSTRAINT fk_me_devolucao_item FOREIGN KEY (devolucao_item_id) REFERENCES devolucao_item (id);
CREATE UNIQUE INDEX ux_me_devolucao_item ON movimento_estoque (devolucao_item_id, tipo)
    WHERE devolucao_item_id IS NOT NULL;
COMMENT ON COLUMN movimento_estoque.devolucao_item_id IS 'Item de devolução que originou o movimento DEVOLUCAO_VENDA.';

ALTER TABLE movimento_estoque
    DROP CONSTRAINT ck_me_tipo,
    ADD CONSTRAINT ck_me_tipo CHECK (tipo IN ('ENTRADA_COMPRA', 'VENDA', 'CANCELAMENTO_VENDA', 'AJUSTE_ENTRADA',
                                              'AJUSTE_SAIDA', 'PERDA', 'INVENTARIO', 'DEVOLUCAO_VENDA')),
    DROP CONSTRAINT ck_me_sinal,
    ADD CONSTRAINT ck_me_sinal CHECK (
        (tipo IN ('ENTRADA_COMPRA', 'CANCELAMENTO_VENDA', 'AJUSTE_ENTRADA', 'DEVOLUCAO_VENDA') AND quantidade > 0)
     OR (tipo IN ('VENDA', 'AJUSTE_SAIDA', 'PERDA')                                           AND quantidade < 0)
     OR (tipo = 'INVENTARIO'                                                                   AND quantidade <> 0)),
    DROP CONSTRAINT ck_me_origem,
    ADD CONSTRAINT ck_me_origem CHECK (
        (tipo IN ('VENDA', 'CANCELAMENTO_VENDA')
            AND venda_item_id IS NOT NULL AND inventario_item_id IS NULL AND entrada_item_id IS NULL
            AND devolucao_item_id IS NULL)
     OR (tipo = 'INVENTARIO'
            AND inventario_item_id IS NOT NULL AND venda_item_id IS NULL AND entrada_item_id IS NULL
            AND devolucao_item_id IS NULL)
     OR (tipo = 'ENTRADA_COMPRA'
            AND entrada_item_id IS NOT NULL AND venda_item_id IS NULL AND inventario_item_id IS NULL
            AND devolucao_item_id IS NULL)
     OR (tipo IN ('AJUSTE_ENTRADA', 'AJUSTE_SAIDA', 'PERDA')
            AND motivo IS NOT NULL AND btrim(motivo) <> ''
            AND venda_item_id IS NULL AND inventario_item_id IS NULL AND entrada_item_id IS NULL
            AND devolucao_item_id IS NULL)
     OR (tipo = 'DEVOLUCAO_VENDA'
            AND devolucao_item_id IS NOT NULL AND venda_item_id IS NULL AND inventario_item_id IS NULL
            AND entrada_item_id IS NULL));

-- 3. Crédito do cliente ------------------------------------------------------------------

ALTER TABLE cliente_credito_movimento
    ADD COLUMN devolucao_id BIGINT,
    ADD CONSTRAINT fk_ccm_devolucao FOREIGN KEY (devolucao_id) REFERENCES devolucao (id);
CREATE INDEX ix_ccm_devolucao ON cliente_credito_movimento (devolucao_id);
COMMENT ON COLUMN cliente_credito_movimento.devolucao_id IS 'Devolução que gerou o vale-troca (CREDITO_DEVOLUCAO) ou o estorno parcial do cashback.';

ALTER TABLE cliente_credito_movimento
    DROP CONSTRAINT ck_ccm_origem,
    ADD CONSTRAINT ck_ccm_origem CHECK (
        (tipo = 'AJUSTE' AND venda_id IS NULL AND devolucao_id IS NULL AND motivo IS NOT NULL AND btrim(motivo) <> '')
     OR (tipo = 'CREDITO_DEVOLUCAO' AND venda_id IS NOT NULL AND devolucao_id IS NOT NULL)
     OR (tipo IN ('CASHBACK', 'USO_EM_VENDA', 'ESTORNO_USO') AND venda_id IS NOT NULL AND devolucao_id IS NULL)
     OR (tipo = 'ESTORNO_CASHBACK' AND venda_id IS NOT NULL));

-- Movimentos da venda (sem devolução): um de cada tipo. Movimentos de devolução: um de cada tipo por devolução.
DROP INDEX ux_ccm_venda_tipo;
CREATE UNIQUE INDEX ux_ccm_venda_tipo ON cliente_credito_movimento (venda_id, tipo)
    WHERE tipo IN ('CASHBACK', 'ESTORNO_CASHBACK', 'USO_EM_VENDA', 'ESTORNO_USO') AND devolucao_id IS NULL;
CREATE UNIQUE INDEX ux_ccm_devolucao_tipo ON cliente_credito_movimento (devolucao_id, tipo)
    WHERE devolucao_id IS NOT NULL;

-- 4. Caixa (colunas existentes na mesma ordem; a nova no fim) ------------------------------------------------

CREATE OR REPLACE VIEW vw_caixa_resumo AS
SELECT s.id                                    AS caixa_sessao_id,
       s.terminal,
       s.status,
       s.usuario_id,
       s.aberto_em,
       s.fechado_em,
       s.valor_abertura,
       coalesce(cm.suprimentos, 0)             AS suprimentos,
       coalesce(cm.sangrias, 0)                AS sangrias,
       coalesce(vd.vendas_dinheiro, 0)         AS vendas_dinheiro,
       coalesce(vt.qtd_vendas, 0)              AS qtd_vendas,
       coalesce(vt.total_vendido, 0)           AS total_vendido,
       coalesce(vt.qtd_canceladas, 0)          AS qtd_canceladas,
       s.valor_abertura + coalesce(cm.suprimentos, 0) - coalesce(cm.sangrias, 0)
                        + coalesce(vd.vendas_dinheiro, 0)
                        - coalesce(dv.devolucoes_dinheiro, 0) AS dinheiro_esperado,
       coalesce(dv.devolucoes_dinheiro, 0)     AS devolucoes_dinheiro
  FROM caixa_sessao s
  LEFT JOIN (SELECT caixa_sessao_id,
                    sum(valor) FILTER (WHERE tipo = 'SUPRIMENTO') AS suprimentos,
                    sum(valor) FILTER (WHERE tipo = 'SANGRIA')    AS sangrias
               FROM caixa_movimento
              GROUP BY caixa_sessao_id) cm ON cm.caixa_sessao_id = s.id
  LEFT JOIN (SELECT v.caixa_sessao_id, sum(vp.valor) AS vendas_dinheiro
               FROM venda v
               JOIN venda_pagamento vp ON vp.venda_id = v.id
               JOIN forma_pagamento fp ON fp.id = vp.forma_pagamento_id
              WHERE v.status = 'FINALIZADA' AND fp.tipo = 'DINHEIRO'
              GROUP BY v.caixa_sessao_id) vd ON vd.caixa_sessao_id = s.id
  LEFT JOIN (SELECT caixa_sessao_id,
                    count(*) FILTER (WHERE status = 'FINALIZADA')                  AS qtd_vendas,
                    coalesce(sum(valor_total) FILTER (WHERE status = 'FINALIZADA'), 0) AS total_vendido,
                    count(*) FILTER (WHERE status = 'CANCELADA')                   AS qtd_canceladas
               FROM venda
              GROUP BY caixa_sessao_id) vt ON vt.caixa_sessao_id = s.id
  LEFT JOIN (SELECT caixa_sessao_id, sum(valor_total) AS devolucoes_dinheiro
               FROM devolucao
              WHERE forma_reembolso = 'DINHEIRO'
              GROUP BY caixa_sessao_id) dv ON dv.caixa_sessao_id = s.id;

-- 5. Permissão e configuração -----------------------------------------------------------

INSERT INTO permissao (codigo, descricao) VALUES
    ('DEVOLUCAO', 'Registrar devoluções e trocas');

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('devolucao.prazo_dias', '30', 'Dias depois da venda em que a devolução não pede liberação de supervisor');
