-- =====================================================================
-- VareSync — V10: crediário e contas a receber (Fase 14: F14.1)
--   1. Cliente: limite de crédito e bloqueio do crediário.
--   2. Forma de pagamento CREDIARIO: CHECK ampliado, só uma forma desse tipo e a forma "Crediário".
--   3. Títulos a receber, recebimentos e tabelas de apoio (o recebimento em si é da parte B).
--   4. Devolução: valor abatido dos títulos em aberto e a forma ABATIMENTO.
--   5. vw_caixa_resumo: recebimentos em dinheiro (no fim), somados ao dinheiro esperado; as devoluções em dinheiro
--      contam só a parte que saiu da gaveta.
--   6. Permissões RECEBIMENTO e LIMITE_CREDITO e configurações crediario.* (seções 0.3 e 0.4 das ampliações).
-- =====================================================================

-- 1. Cliente ------------------------------------------------------------------------

ALTER TABLE cliente
    ADD COLUMN limite_credito      NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN crediario_bloqueado BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_cliente_limite_credito CHECK (limite_credito >= 0);
COMMENT ON COLUMN cliente.limite_credito      IS 'Limite do crediário: limite disponível = limite − soma do que está em aberto (F14.2).';
COMMENT ON COLUMN cliente.crediario_bloqueado IS 'Bloqueado, o cliente não compra no crediário, nem com liberação.';

-- 2. Forma de pagamento ---------------------------------------------------------------

ALTER TABLE forma_pagamento
    DROP CONSTRAINT ck_forma_pagamento_tipo,
    ADD CONSTRAINT ck_forma_pagamento_tipo CHECK (tipo IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO',
                                                           'OUTROS', 'CREDITO_CLIENTE', 'CREDIARIO'));
CREATE UNIQUE INDEX ux_forma_pagamento_crediario ON forma_pagamento (tipo) WHERE tipo = 'CREDIARIO';
INSERT INTO forma_pagamento (descricao, tipo) VALUES ('Crediário', 'CREDIARIO');

-- 3. Títulos e recebimentos ---------------------------------------------------------------

CREATE TABLE titulo_receber (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id          BIGINT        NOT NULL,
    venda_id            BIGINT        NOT NULL,
    parcela             INTEGER       NOT NULL,
    total_parcelas      INTEGER       NOT NULL,
    emissao             DATE          NOT NULL,
    vencimento          DATE          NOT NULL,
    valor               NUMERIC(12,2) NOT NULL,
    valor_pago          NUMERIC(12,2) NOT NULL DEFAULT 0,
    valor_abatido       NUMERIC(12,2) NOT NULL DEFAULT 0,
    status              VARCHAR(10)   NOT NULL DEFAULT 'ABERTO',
    motivo_cancelamento VARCHAR(200),
    criado_em           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por          BIGINT        NOT NULL,
    CONSTRAINT fk_tr_cliente    FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    CONSTRAINT fk_tr_venda      FOREIGN KEY (venda_id)   REFERENCES venda (id),
    CONSTRAINT fk_tr_criado_por FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT uq_tr_parcela    UNIQUE (venda_id, parcela),
    CONSTRAINT ck_tr_parcela    CHECK (parcela >= 1 AND parcela <= total_parcelas),
    CONSTRAINT ck_tr_valor      CHECK (valor > 0),
    CONSTRAINT ck_tr_baixas     CHECK (valor_pago >= 0 AND valor_abatido >= 0 AND valor_pago + valor_abatido <= valor),
    CONSTRAINT ck_tr_status     CHECK (status IN ('ABERTO', 'QUITADO', 'CANCELADO')),
    CONSTRAINT ck_tr_situacao   CHECK (status = 'CANCELADO'
                                       OR (status = 'QUITADO') = (valor_pago + valor_abatido = valor)),
    CONSTRAINT ck_tr_cancelado  CHECK ((status = 'CANCELADO') = (motivo_cancelamento IS NOT NULL))
);
CREATE INDEX ix_tr_cliente_status ON titulo_receber (cliente_id, status);
CREATE INDEX ix_tr_vencimento     ON titulo_receber (vencimento);
COMMENT ON TABLE  titulo_receber               IS 'Parcela de uma venda no crediário (F14). Em aberto = valor − valor_pago − valor_abatido.';
COMMENT ON COLUMN titulo_receber.valor_abatido IS 'Parte abatida por devolução, sem multa nem juros.';

CREATE TABLE recebimento (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id      BIGINT        NOT NULL,
    caixa_sessao_id BIGINT        NOT NULL,
    usuario_id      BIGINT        NOT NULL,
    liberado_por    BIGINT,
    data_hora       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    valor_principal NUMERIC(12,2) NOT NULL,
    multa           NUMERIC(12,2) NOT NULL DEFAULT 0,
    juros           NUMERIC(12,2) NOT NULL DEFAULT 0,
    desconto        NUMERIC(12,2) NOT NULL DEFAULT 0,
    valor_total     NUMERIC(12,2) NOT NULL,
    troco           NUMERIC(12,2) NOT NULL DEFAULT 0,
    estornado       BOOLEAN       NOT NULL DEFAULT FALSE,
    estornado_por   BIGINT,
    estornado_em    TIMESTAMPTZ,
    CONSTRAINT fk_rec_cliente       FOREIGN KEY (cliente_id)      REFERENCES cliente (id),
    CONSTRAINT fk_rec_caixa         FOREIGN KEY (caixa_sessao_id) REFERENCES caixa_sessao (id),
    CONSTRAINT fk_rec_usuario       FOREIGN KEY (usuario_id)      REFERENCES usuario (id),
    CONSTRAINT fk_rec_liberado_por  FOREIGN KEY (liberado_por)    REFERENCES usuario (id),
    CONSTRAINT fk_rec_estornado_por FOREIGN KEY (estornado_por)   REFERENCES usuario (id),
    CONSTRAINT ck_rec_valores  CHECK (valor_principal >= 0 AND multa >= 0 AND juros >= 0 AND desconto >= 0 AND troco >= 0),
    CONSTRAINT ck_rec_total    CHECK (valor_total = valor_principal + multa + juros - desconto),
    CONSTRAINT ck_rec_estorno  CHECK (estornado = (estornado_por IS NOT NULL) AND estornado = (estornado_em IS NOT NULL))
);
CREATE INDEX ix_rec_cliente   ON recebimento (cliente_id);
CREATE INDEX ix_rec_caixa     ON recebimento (caixa_sessao_id);
CREATE INDEX ix_rec_data_hora ON recebimento (data_hora);
COMMENT ON TABLE recebimento IS 'Recebimento de títulos do crediário no caixa (F14.3).';

CREATE TABLE recebimento_titulo (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recebimento_id BIGINT        NOT NULL,
    titulo_id      BIGINT        NOT NULL,
    principal      NUMERIC(12,2) NOT NULL,
    multa          NUMERIC(12,2) NOT NULL DEFAULT 0,
    juros          NUMERIC(12,2) NOT NULL DEFAULT 0,
    desconto       NUMERIC(12,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_rt_recebimento FOREIGN KEY (recebimento_id) REFERENCES recebimento (id),
    CONSTRAINT fk_rt_titulo      FOREIGN KEY (titulo_id)      REFERENCES titulo_receber (id),
    CONSTRAINT uq_rt_titulo      UNIQUE (recebimento_id, titulo_id),
    CONSTRAINT ck_rt_valores     CHECK (principal >= 0 AND multa >= 0 AND juros >= 0 AND desconto >= 0)
);
CREATE INDEX ix_rt_titulo ON recebimento_titulo (titulo_id);

CREATE TABLE recebimento_pagamento (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recebimento_id     BIGINT        NOT NULL,
    forma_pagamento_id INTEGER       NOT NULL,
    valor              NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_rp_recebimento FOREIGN KEY (recebimento_id)     REFERENCES recebimento (id),
    CONSTRAINT fk_rp_forma       FOREIGN KEY (forma_pagamento_id) REFERENCES forma_pagamento (id),
    CONSTRAINT uq_rp_forma       UNIQUE (recebimento_id, forma_pagamento_id),
    CONSTRAINT ck_rp_valor       CHECK (valor > 0)
);
COMMENT ON COLUMN recebimento_pagamento.valor IS 'Como em venda_pagamento: no dinheiro, já sem o troco.';

-- 4. Devolução ---------------------------------------------------------------------

ALTER TABLE devolucao
    ADD COLUMN valor_abatido NUMERIC(12,2) NOT NULL DEFAULT 0,
    DROP CONSTRAINT ck_dev_forma,
    ADD CONSTRAINT ck_dev_forma CHECK (forma_reembolso IN ('DINHEIRO', 'CREDITO_CLIENTE', 'OUTRO', 'ABATIMENTO')),
    ADD CONSTRAINT ck_dev_abatido CHECK (valor_abatido >= 0 AND valor_abatido <= valor_total
        AND (forma_reembolso = 'ABATIMENTO') = (valor_abatido > 0 AND valor_abatido = valor_total));
COMMENT ON COLUMN devolucao.valor_abatido IS 'Parte da devolução que abateu títulos em aberto da venda; o restante foi reembolsado na forma_reembolso.';
COMMENT ON COLUMN devolucao.forma_reembolso IS 'DINHEIRO sai da gaveta do caixa; CREDITO_CLIENTE vira vale-troca; OUTRO é estorno feito fora do sistema; ABATIMENTO: a devolução inteira abateu títulos.';

-- 5. Caixa (colunas existentes na mesma ordem; a nova no fim) ------------------------------------------------

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
                        - coalesce(dv.devolucoes_dinheiro, 0)
                        + coalesce(rc.recebimentos_dinheiro, 0) AS dinheiro_esperado,
       coalesce(dv.devolucoes_dinheiro, 0)     AS devolucoes_dinheiro,
       coalesce(rc.recebimentos_dinheiro, 0)   AS recebimentos_dinheiro
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
  LEFT JOIN (SELECT caixa_sessao_id, sum(valor_total - valor_abatido) AS devolucoes_dinheiro
               FROM devolucao
              WHERE forma_reembolso = 'DINHEIRO'
              GROUP BY caixa_sessao_id) dv ON dv.caixa_sessao_id = s.id
  LEFT JOIN (SELECT r.caixa_sessao_id, sum(rp.valor) AS recebimentos_dinheiro
               FROM recebimento r
               JOIN recebimento_pagamento rp ON rp.recebimento_id = r.id
               JOIN forma_pagamento fp       ON fp.id = rp.forma_pagamento_id
              WHERE NOT r.estornado AND fp.tipo = 'DINHEIRO'
              GROUP BY r.caixa_sessao_id) rc ON rc.caixa_sessao_id = s.id;

-- 6. Permissões e configurações -----------------------------------------------------------

INSERT INTO permissao (codigo, descricao) VALUES
    ('RECEBIMENTO', 'Receber títulos do crediário'),
    ('LIMITE_CREDITO', 'Gerenciar crédito: vender acima do limite ou a cliente em atraso e dispensar encargos');

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('crediario.max_parcelas', '3', 'Número máximo de parcelas no crediário'),
    ('crediario.dias_primeira', '30', 'Dias até o vencimento da primeira parcela'),
    ('crediario.juros_mes_pct', '2', 'Juros simples ao mês sobre títulos em atraso (%)'),
    ('crediario.multa_pct', '2', 'Multa sobre títulos em atraso (%)'),
    ('crediario.carencia_dias', '0', 'Dias de atraso sem multa nem juros'),
    ('crediario.bloqueio_atraso_dias', '30', 'Dias de atraso a partir dos quais a venda a prazo pede liberação');
