-- =====================================================================
-- VareSync — V7: crédito do cliente e cashback, parte B (Fase 12: F12.3 a F12.5)
--   1. Saldo de crédito protegido (cliente_credito), uma linha por cliente, criada com o cliente; os clientes que já
--      existem recebem a linha com saldo zero.
--   2. Razão imutável cliente_credito_movimento (F12.3): o INSERT aplica o valor ao saldo pelo trigger, que também
--      preenche saldo_anterior e saldo_posterior (mesmo mecanismo do estoque, seção 0.2 das ampliações).
--      USO_EM_VENDA não deixa o saldo negativo; os estornos podem.
--   3. vw_cliente_credito_inconsistente: precisa ficar vazia (testes e Diagnóstico de Configurações).
--   4. Forma de pagamento CREDITO_CLIENTE (F12.4): CHECK ampliado, só uma forma desse tipo e a forma
--      "Crédito do cliente", ativa.
--   5. Configurações da fidelidade (seção 0.4): desligada, 1% de cashback e compra mínima zero.
--   O CREDITO_DEVOLUCAO já entra no CHECK do tipo (tabela da F12.3), mas só é gravado a partir da F13.
-- =====================================================================

-- 1. Saldo ---------------------------------------------------------------------

CREATE TABLE cliente_credito (
    cliente_id    BIGINT        PRIMARY KEY,
    saldo         NUMERIC(12,2) NOT NULL DEFAULT 0,
    atualizado_em TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_cc_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id) ON DELETE CASCADE
);
COMMENT ON TABLE  cliente_credito       IS 'Saldo de crédito do cliente (F12.3). Só o trigger do razão escreve nele.';
COMMENT ON COLUMN cliente_credito.saldo IS 'Valor devido ao cliente; negativo depois de um estorno, abatido pelos próximos créditos.';

-- 2. Razão -----------------------------------------------------------------------

CREATE TABLE cliente_credito_movimento (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cliente_id      BIGINT        NOT NULL,
    tipo            VARCHAR(20)   NOT NULL,
    valor           NUMERIC(12,2) NOT NULL,
    saldo_anterior  NUMERIC(12,2) NOT NULL,
    saldo_posterior NUMERIC(12,2) NOT NULL,
    venda_id        BIGINT,
    motivo          VARCHAR(200),
    usuario_id      BIGINT        NOT NULL,
    criado_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_ccm_cliente FOREIGN KEY (cliente_id) REFERENCES cliente (id),
    CONSTRAINT fk_ccm_venda   FOREIGN KEY (venda_id)   REFERENCES venda (id),
    CONSTRAINT fk_ccm_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_ccm_tipo  CHECK (tipo IN ('CASHBACK', 'ESTORNO_CASHBACK', 'CREDITO_DEVOLUCAO', 'USO_EM_VENDA',
                                            'ESTORNO_USO', 'AJUSTE')),
    CONSTRAINT ck_ccm_sinal CHECK (
        (tipo IN ('CASHBACK', 'CREDITO_DEVOLUCAO', 'ESTORNO_USO') AND valor > 0)
     OR (tipo IN ('ESTORNO_CASHBACK', 'USO_EM_VENDA')             AND valor < 0)
     OR (tipo = 'AJUSTE'                                          AND valor <> 0)),
    CONSTRAINT ck_ccm_origem CHECK (
        (tipo = 'AJUSTE' AND venda_id IS NULL AND motivo IS NOT NULL AND btrim(motivo) <> '')
     OR (tipo <> 'AJUSTE' AND venda_id IS NOT NULL)),
    CONSTRAINT ck_ccm_saldo CHECK (saldo_posterior = saldo_anterior + valor)
);
CREATE INDEX ix_ccm_cliente_data ON cliente_credito_movimento (cliente_id, criado_em);
CREATE INDEX ix_ccm_venda        ON cliente_credito_movimento (venda_id);
-- impede gerar ou estornar duas vezes o mesmo crédito de uma venda (idempotência)
CREATE UNIQUE INDEX ux_ccm_venda_tipo ON cliente_credito_movimento (venda_id, tipo)
    WHERE tipo IN ('CASHBACK', 'ESTORNO_CASHBACK', 'USO_EM_VENDA', 'ESTORNO_USO');
COMMENT ON TABLE  cliente_credito_movimento                 IS 'Extrato do crédito do cliente. Imutável. O INSERT atualiza cliente_credito via trigger.';
COMMENT ON COLUMN cliente_credito_movimento.valor           IS 'Com sinal: positivo credita, negativo debita.';
COMMENT ON COLUMN cliente_credito_movimento.saldo_anterior  IS 'Preenchido pelo trigger (não informar no INSERT).';
COMMENT ON COLUMN cliente_credito_movimento.saldo_posterior IS 'Preenchido pelo trigger (não informar no INSERT).';

-- Saldo protegido: só o trigger do razão escreve nele (apagar, só com saldo zero, junto com o cliente).
CREATE FUNCTION fn_cliente_credito_protegido() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.saldo <> 0 THEN
            RAISE EXCEPTION 'Não é permitido apagar o crédito do cliente % com saldo %.', OLD.cliente_id, OLD.saldo;
        END IF;
        RETURN OLD;
    END IF;
    IF coalesce(current_setting('varesync.via_credito', true), '') <> 'on' THEN
        RAISE EXCEPTION 'O crédito do cliente só pode ser alterado registrando um movimento de crédito.'
            USING HINT = 'Insira em cliente_credito_movimento; o saldo de cliente_credito é atualizado automaticamente.';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_cliente_credito_protegido
    BEFORE INSERT OR UPDATE OR DELETE ON cliente_credito
    FOR EACH ROW EXECUTE FUNCTION fn_cliente_credito_protegido();

-- Cliente novo nasce com saldo zero.
CREATE FUNCTION fn_cliente_cria_credito() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('varesync.via_credito', 'on', true);
    INSERT INTO cliente_credito (cliente_id, saldo, atualizado_em) VALUES (NEW.id, 0, NEW.criado_em);
    PERFORM set_config('varesync.via_credito', 'off', true);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_cliente_cria_credito
    AFTER INSERT ON cliente
    FOR EACH ROW EXECUTE FUNCTION fn_cliente_cria_credito();

-- Movimento aplica o saldo (o UPDATE trava a linha do saldo até o fim da transação).
CREATE FUNCTION fn_credito_aplica_saldo() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_saldo NUMERIC(12,2);
BEGIN
    PERFORM set_config('varesync.via_credito', 'on', true);
    UPDATE cliente_credito
       SET saldo         = saldo + NEW.valor,
           atualizado_em = NEW.criado_em
     WHERE cliente_id = NEW.cliente_id
    RETURNING saldo INTO v_saldo;
    PERFORM set_config('varesync.via_credito', 'off', true);

    IF v_saldo IS NULL THEN
        RAISE EXCEPTION 'Cliente % sem saldo de crédito.', NEW.cliente_id;
    END IF;
    IF NEW.tipo = 'USO_EM_VENDA' AND v_saldo < 0 THEN
        RAISE EXCEPTION 'O uso de crédito deixaria o saldo do cliente % negativo (%).', NEW.cliente_id, v_saldo;
    END IF;

    NEW.saldo_posterior := v_saldo;
    NEW.saldo_anterior  := v_saldo - NEW.valor;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_credito_aplica_saldo
    BEFORE INSERT ON cliente_credito_movimento
    FOR EACH ROW EXECUTE FUNCTION fn_credito_aplica_saldo();

CREATE FUNCTION fn_credito_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Movimentos de crédito do cliente não podem ser alterados nem excluídos.'
        USING HINT = 'Para corrigir, registre um ajuste.';
END $$;

CREATE TRIGGER trg_credito_imutavel
    BEFORE UPDATE OR DELETE ON cliente_credito_movimento
    FOR EACH ROW EXECUTE FUNCTION fn_credito_imutavel();

-- Clientes que já existem (parte A): saldo zero.
SELECT set_config('varesync.via_credito', 'on', true);
INSERT INTO cliente_credito (cliente_id, saldo, atualizado_em)
SELECT id, 0, criado_em FROM cliente;
SELECT set_config('varesync.via_credito', 'off', true);

-- 3. Consistência (deve retornar ZERO linhas) -------------------------------------

CREATE VIEW vw_cliente_credito_inconsistente AS
SELECT cc.cliente_id,
       cc.saldo                       AS saldo_registrado,
       coalesce(sum(m.valor), 0)      AS saldo_pelos_movimentos
  FROM cliente_credito cc
  LEFT JOIN cliente_credito_movimento m ON m.cliente_id = cc.cliente_id
 GROUP BY cc.cliente_id, cc.saldo
HAVING cc.saldo <> coalesce(sum(m.valor), 0);

-- 4. Forma de pagamento ----------------------------------------------------------

ALTER TABLE forma_pagamento
    DROP CONSTRAINT ck_forma_pagamento_tipo,
    ADD CONSTRAINT ck_forma_pagamento_tipo CHECK (tipo IN ('DINHEIRO', 'PIX', 'CARTAO_DEBITO', 'CARTAO_CREDITO',
                                                           'OUTROS', 'CREDITO_CLIENTE'));
CREATE UNIQUE INDEX ux_forma_pagamento_credito_cliente ON forma_pagamento (tipo) WHERE tipo = 'CREDITO_CLIENTE';
INSERT INTO forma_pagamento (descricao, tipo) VALUES ('Crédito do cliente', 'CREDITO_CLIENTE');
COMMENT ON COLUMN forma_pagamento.tipo IS 'Só o tipo DINHEIRO aceita troco e entra no saldo físico da gaveta. CREDITO_CLIENTE abate do crédito do cliente identificado.';

-- 5. Configurações ---------------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('fidelidade.ativo', 'false', 'Gera cashback nas vendas com cliente identificado'),
    ('fidelidade.cashback_pct', '1', 'Percentual de cashback sobre a venda (sem o que foi pago com crédito)'),
    ('fidelidade.compra_minima', '0', 'Valor mínimo da venda (sem o crédito usado) para gerar cashback');
