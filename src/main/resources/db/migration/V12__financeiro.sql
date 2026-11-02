-- =====================================================================
-- VareSync — V12: financeiro, parte A (Fase 15: F15.1 a F15.3)
--   1. Contas financeiras: conta_financeira ("Cofre", padrão do caixa, e "Banco"), saldo protegido conta_saldo, razão
--      imutável conta_movimento (mesmo mecanismo do estoque e do crédito, seção 0.2) e vw_conta_inconsistente.
--   2. Formas de pagamento: taxa, dias para receber e conta de destino.
--   3. Caixa: as contas usadas pela integração (F15.1). A conta do reembolso OUTRO fica no próprio movimento.
--   4. Recebíveis de cartão, PIX e das outras formas com conta de destino (F15.2).
--   5. Contas a pagar: categorias, títulos, pagamentos e parcelas da entrada de mercadoria (F15.3).
--   6. Permissão FINANCEIRO e configuração financeiro.integrar_caixa (desligada).
-- =====================================================================

-- 1. Contas ---------------------------------------------------------------------------

CREATE TABLE conta_financeira (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome         VARCHAR(60) NOT NULL,
    tipo         VARCHAR(10) NOT NULL,
    ativa        BOOLEAN     NOT NULL DEFAULT TRUE,
    padrao_caixa BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_conta_nome   CHECK (btrim(nome) <> ''),
    CONSTRAINT ck_conta_tipo   CHECK (tipo IN ('COFRE', 'BANCO', 'OUTRA')),
    CONSTRAINT ck_conta_padrao CHECK (NOT padrao_caixa OR ativa)
);
CREATE UNIQUE INDEX ux_conta_nome   ON conta_financeira (lower(nome));
CREATE UNIQUE INDEX ux_conta_padrao ON conta_financeira (padrao_caixa) WHERE padrao_caixa;
COMMENT ON TABLE  conta_financeira              IS 'Onde o dinheiro da loja fica fora da gaveta: cofre, banco ou outra (F15.1).';
COMMENT ON COLUMN conta_financeira.padrao_caixa IS 'Conta que os diálogos do caixa já trazem preenchida; no máximo uma.';

CREATE TABLE conta_saldo (
    conta_id      BIGINT        PRIMARY KEY,
    saldo         NUMERIC(14,2) NOT NULL DEFAULT 0,
    atualizado_em TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_cs_conta FOREIGN KEY (conta_id) REFERENCES conta_financeira (id) ON DELETE CASCADE
);
COMMENT ON TABLE  conta_saldo       IS 'Saldo da conta. Só o trigger do razão escreve nele.';
COMMENT ON COLUMN conta_saldo.saldo IS 'Pode ficar negativo: saldo de conta nunca bloqueia uma operação de caixa.';

-- As tabelas de origem vêm antes do razão, que aponta para elas.

CREATE TABLE recebivel (
    id                       BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venda_pagamento_id       BIGINT,
    recebimento_pagamento_id BIGINT,
    forma_pagamento_id       SMALLINT      NOT NULL,
    conta_id                 BIGINT        NOT NULL,
    valor_bruto              NUMERIC(12,2) NOT NULL,
    taxa                     NUMERIC(12,2) NOT NULL,
    valor_liquido            NUMERIC(12,2) NOT NULL,
    previsto_para            DATE          NOT NULL,
    status                   VARCHAR(10)   NOT NULL,
    recebido_em              DATE,
    criado_em                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_rcv_venda_pagamento       FOREIGN KEY (venda_pagamento_id)       REFERENCES venda_pagamento (id),
    CONSTRAINT fk_rcv_recebimento_pagamento FOREIGN KEY (recebimento_pagamento_id) REFERENCES recebimento_pagamento (id),
    CONSTRAINT fk_rcv_forma                 FOREIGN KEY (forma_pagamento_id)       REFERENCES forma_pagamento (id),
    CONSTRAINT fk_rcv_conta                 FOREIGN KEY (conta_id)                 REFERENCES conta_financeira (id),
    CONSTRAINT uq_rcv_venda_pagamento       UNIQUE (venda_pagamento_id),
    CONSTRAINT uq_rcv_recebimento_pagamento UNIQUE (recebimento_pagamento_id),
    CONSTRAINT ck_rcv_origem   CHECK (num_nonnulls(venda_pagamento_id, recebimento_pagamento_id) = 1),
    CONSTRAINT ck_rcv_valores  CHECK (valor_bruto > 0 AND taxa >= 0 AND valor_liquido >= 0
                                      AND valor_liquido = valor_bruto - taxa),
    CONSTRAINT ck_rcv_status   CHECK (status IN ('PREVISTO', 'RECEBIDO', 'CANCELADO')),
    CONSTRAINT ck_rcv_recebido CHECK (status = 'CANCELADO' OR (status = 'RECEBIDO') = (recebido_em IS NOT NULL))
);
CREATE INDEX ix_rcv_status_previsto ON recebivel (status, previsto_para);
CREATE INDEX ix_rcv_forma           ON recebivel (forma_pagamento_id);
COMMENT ON TABLE  recebivel             IS 'Dinheiro a receber de um pagamento numa forma com conta de destino (F15.2).';
COMMENT ON COLUMN recebivel.recebido_em IS 'Continua preenchido no recebível recebido e depois cancelado (o razão tem o estorno).';

CREATE TABLE categoria_despesa (
    id    BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome  VARCHAR(60) NOT NULL,
    grupo VARCHAR(12) NOT NULL,
    ativa BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_cd_nome  CHECK (btrim(nome) <> ''),
    CONSTRAINT ck_cd_grupo CHECK (grupo IN ('MERCADORIA', 'OPERACIONAL', 'IMPOSTO', 'FINANCEIRA', 'OUTRA'))
);
CREATE UNIQUE INDEX ux_cd_nome ON categoria_despesa (lower(nome));
COMMENT ON TABLE categoria_despesa IS 'Categoria do título a pagar; o grupo decide a linha do DRE (F15.5).';

CREATE TABLE titulo_pagar (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    categoria_id        BIGINT        NOT NULL,
    descricao           VARCHAR(200)  NOT NULL,
    competencia         DATE          NOT NULL,
    vencimento          DATE          NOT NULL,
    fornecedor_id       BIGINT,
    entrada_id          BIGINT,
    documento           VARCHAR(30),
    emissao             DATE,
    valor               NUMERIC(12,2) NOT NULL,
    valor_pago          NUMERIC(12,2) NOT NULL DEFAULT 0,
    status              VARCHAR(10)   NOT NULL DEFAULT 'ABERTO',
    motivo_cancelamento VARCHAR(200),
    criado_em           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por          BIGINT        NOT NULL,
    CONSTRAINT fk_tp_categoria  FOREIGN KEY (categoria_id)  REFERENCES categoria_despesa (id),
    CONSTRAINT fk_tp_fornecedor FOREIGN KEY (fornecedor_id) REFERENCES fornecedor (id),
    CONSTRAINT fk_tp_entrada    FOREIGN KEY (entrada_id)    REFERENCES entrada_mercadoria (id),
    CONSTRAINT fk_tp_criado_por FOREIGN KEY (criado_por)    REFERENCES usuario (id),
    CONSTRAINT ck_tp_descricao   CHECK (btrim(descricao) <> ''),
    CONSTRAINT ck_tp_competencia CHECK (extract(day FROM competencia) = 1),
    CONSTRAINT ck_tp_valor       CHECK (valor > 0),
    CONSTRAINT ck_tp_pago        CHECK (valor_pago >= 0 AND valor_pago <= valor),
    CONSTRAINT ck_tp_status      CHECK (status IN ('ABERTO', 'PAGO', 'CANCELADO')),
    CONSTRAINT ck_tp_situacao    CHECK (status = 'CANCELADO' OR (status = 'PAGO') = (valor_pago = valor)),
    CONSTRAINT ck_tp_cancelado   CHECK ((status = 'CANCELADO') = (motivo_cancelamento IS NOT NULL)
                                        AND (status <> 'CANCELADO' OR valor_pago = 0))
);
CREATE INDEX ix_tp_status_vencimento ON titulo_pagar (status, vencimento);
CREATE INDEX ix_tp_competencia       ON titulo_pagar (competencia);
CREATE INDEX ix_tp_entrada           ON titulo_pagar (entrada_id);
COMMENT ON TABLE  titulo_pagar             IS 'Conta a pagar (F15.3). Em aberto = valor − valor_pago.';
COMMENT ON COLUMN titulo_pagar.competencia IS '1º dia do mês a que a despesa pertence (DRE); o vencimento é quando o dinheiro sai.';

CREATE TABLE titulo_pagar_pagamento (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    titulo_pagar_id BIGINT        NOT NULL,
    conta_id        BIGINT        NOT NULL,
    data            DATE          NOT NULL,
    valor           NUMERIC(12,2) NOT NULL,
    juros           NUMERIC(12,2) NOT NULL DEFAULT 0,
    desconto        NUMERIC(12,2) NOT NULL DEFAULT 0,
    usuario_id      BIGINT        NOT NULL,
    criado_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    estornado       BOOLEAN       NOT NULL DEFAULT FALSE,
    estornado_por   BIGINT,
    estornado_em    TIMESTAMPTZ,
    motivo_estorno  VARCHAR(200),
    CONSTRAINT fk_tpp_titulo        FOREIGN KEY (titulo_pagar_id) REFERENCES titulo_pagar (id),
    CONSTRAINT fk_tpp_conta         FOREIGN KEY (conta_id)        REFERENCES conta_financeira (id),
    CONSTRAINT fk_tpp_usuario       FOREIGN KEY (usuario_id)      REFERENCES usuario (id),
    CONSTRAINT fk_tpp_estornado_por FOREIGN KEY (estornado_por)   REFERENCES usuario (id),
    CONSTRAINT ck_tpp_valores CHECK (valor > 0 AND juros >= 0 AND desconto >= 0 AND desconto <= valor + juros),
    CONSTRAINT ck_tpp_estorno CHECK (estornado = (estornado_por IS NOT NULL) AND estornado = (estornado_em IS NOT NULL)
                                     AND estornado = (motivo_estorno IS NOT NULL))
);
CREATE INDEX ix_tpp_titulo ON titulo_pagar_pagamento (titulo_pagar_id);
COMMENT ON COLUMN titulo_pagar_pagamento.valor IS 'Quanto abate do título; a conta sai com valor + juros − desconto.';

CREATE TABLE conta_movimento (
    id                        BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conta_id                  BIGINT        NOT NULL,
    tipo                      VARCHAR(20)   NOT NULL,
    valor                     NUMERIC(14,2) NOT NULL,
    saldo_anterior            NUMERIC(14,2) NOT NULL,
    saldo_posterior           NUMERIC(14,2) NOT NULL,
    data_hora                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    descricao                 VARCHAR(250)  NOT NULL,
    usuario_id                BIGINT        NOT NULL,
    caixa_sessao_id           BIGINT,
    caixa_movimento_id        BIGINT,
    recebivel_id              BIGINT,
    titulo_pagar_pagamento_id BIGINT,
    devolucao_id              BIGINT,
    transferencia_id          BIGINT,
    CONSTRAINT fk_cm2_conta       FOREIGN KEY (conta_id)                  REFERENCES conta_financeira (id),
    CONSTRAINT fk_cm2_usuario     FOREIGN KEY (usuario_id)                REFERENCES usuario (id),
    CONSTRAINT fk_cm2_sessao      FOREIGN KEY (caixa_sessao_id)           REFERENCES caixa_sessao (id),
    CONSTRAINT fk_cm2_movimento   FOREIGN KEY (caixa_movimento_id)        REFERENCES caixa_movimento (id),
    CONSTRAINT fk_cm2_recebivel   FOREIGN KEY (recebivel_id)              REFERENCES recebivel (id),
    CONSTRAINT fk_cm2_pagamento   FOREIGN KEY (titulo_pagar_pagamento_id) REFERENCES titulo_pagar_pagamento (id),
    CONSTRAINT fk_cm2_devolucao   FOREIGN KEY (devolucao_id)              REFERENCES devolucao (id),
    CONSTRAINT ck_cm2_tipo CHECK (tipo IN ('ABERTURA_CAIXA', 'FECHAMENTO_CAIXA', 'SANGRIA', 'SUPRIMENTO', 'RECEBIVEL',
                                           'ESTORNO_RECEBIVEL', 'PAGAMENTO', 'ESTORNO_PAGAMENTO', 'REEMBOLSO',
                                           'TRANSFERENCIA', 'SALDO_INICIAL', 'AJUSTE')),
    CONSTRAINT ck_cm2_sinal CHECK (
        (tipo IN ('FECHAMENTO_CAIXA', 'SANGRIA', 'RECEBIVEL', 'ESTORNO_PAGAMENTO') AND valor > 0)
     OR (tipo IN ('ABERTURA_CAIXA', 'SUPRIMENTO', 'ESTORNO_RECEBIVEL', 'PAGAMENTO', 'REEMBOLSO') AND valor < 0)
     OR (tipo IN ('TRANSFERENCIA', 'SALDO_INICIAL', 'AJUSTE') AND valor <> 0)),
    CONSTRAINT ck_cm2_uma_origem CHECK (num_nonnulls(caixa_sessao_id, caixa_movimento_id, recebivel_id,
                                                     titulo_pagar_pagamento_id, devolucao_id, transferencia_id) <= 1),
    CONSTRAINT ck_cm2_origem CHECK (
        (tipo IN ('ABERTURA_CAIXA', 'FECHAMENTO_CAIXA'))        = (caixa_sessao_id IS NOT NULL)
    AND (tipo IN ('SANGRIA', 'SUPRIMENTO'))                     = (caixa_movimento_id IS NOT NULL)
    AND (tipo IN ('RECEBIVEL', 'ESTORNO_RECEBIVEL'))            = (recebivel_id IS NOT NULL)
    AND (tipo IN ('PAGAMENTO', 'ESTORNO_PAGAMENTO'))            = (titulo_pagar_pagamento_id IS NOT NULL)
    AND (tipo = 'REEMBOLSO')                                    = (devolucao_id IS NOT NULL)
    AND (tipo = 'TRANSFERENCIA')                                = (transferencia_id IS NOT NULL)),
    CONSTRAINT ck_cm2_descricao CHECK (btrim(descricao) <> ''),
    CONSTRAINT ck_cm2_saldo     CHECK (saldo_posterior = saldo_anterior + valor)
);
CREATE INDEX ix_cm2_conta_data ON conta_movimento (conta_id, data_hora);
CREATE INDEX ix_cm2_data       ON conta_movimento (data_hora);
-- impede lançar duas vezes a mesma origem (idempotência, como no kardex)
CREATE UNIQUE INDEX ux_cm2_sessao        ON conta_movimento (caixa_sessao_id, tipo) WHERE caixa_sessao_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cm2_movimento     ON conta_movimento (caixa_movimento_id) WHERE caixa_movimento_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cm2_recebivel     ON conta_movimento (recebivel_id, tipo) WHERE recebivel_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cm2_pagamento     ON conta_movimento (titulo_pagar_pagamento_id, tipo)
    WHERE titulo_pagar_pagamento_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cm2_devolucao     ON conta_movimento (devolucao_id) WHERE devolucao_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cm2_transferencia ON conta_movimento (transferencia_id, conta_id) WHERE transferencia_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cm2_saldo_inicial ON conta_movimento (conta_id) WHERE tipo = 'SALDO_INICIAL';
COMMENT ON TABLE  conta_movimento                  IS 'Extrato da conta. Imutável. O INSERT atualiza conta_saldo via trigger.';
COMMENT ON COLUMN conta_movimento.valor            IS 'Com sinal: positivo entra, negativo sai.';
COMMENT ON COLUMN conta_movimento.saldo_anterior   IS 'Preenchido pelo trigger (não informar no INSERT).';
COMMENT ON COLUMN conta_movimento.saldo_posterior  IS 'Preenchido pelo trigger (não informar no INSERT).';
COMMENT ON COLUMN conta_movimento.transferencia_id IS 'Agrupa as duas pernas de uma transferência entre contas.';

CREATE SEQUENCE conta_transferencia_seq;

-- Saldo protegido: só o trigger do razão escreve nele (apagar, só com saldo zero, junto com a conta).
CREATE FUNCTION fn_conta_saldo_protegido() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.saldo <> 0 THEN
            RAISE EXCEPTION 'Não é permitido apagar a conta % com saldo %.', OLD.conta_id, OLD.saldo;
        END IF;
        RETURN OLD;
    END IF;
    IF coalesce(current_setting('varesync.via_conta', true), '') <> 'on' THEN
        RAISE EXCEPTION 'O saldo da conta só pode ser alterado registrando um movimento da conta.'
            USING HINT = 'Insira em conta_movimento; o saldo de conta_saldo é atualizado automaticamente.';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_conta_saldo_protegido
    BEFORE INSERT OR UPDATE OR DELETE ON conta_saldo
    FOR EACH ROW EXECUTE FUNCTION fn_conta_saldo_protegido();

-- Conta nova nasce com saldo zero.
CREATE FUNCTION fn_conta_cria_saldo() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('varesync.via_conta', 'on', true);
    INSERT INTO conta_saldo (conta_id, saldo) VALUES (NEW.id, 0);
    PERFORM set_config('varesync.via_conta', 'off', true);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_conta_cria_saldo
    AFTER INSERT ON conta_financeira
    FOR EACH ROW EXECUTE FUNCTION fn_conta_cria_saldo();

-- Movimento aplica o saldo (o UPDATE trava a linha do saldo até o fim da transação).
CREATE FUNCTION fn_conta_aplica_saldo() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_saldo NUMERIC(14,2);
BEGIN
    PERFORM set_config('varesync.via_conta', 'on', true);
    UPDATE conta_saldo
       SET saldo         = saldo + NEW.valor,
           atualizado_em = NEW.data_hora
     WHERE conta_id = NEW.conta_id
    RETURNING saldo INTO v_saldo;
    PERFORM set_config('varesync.via_conta', 'off', true);

    IF v_saldo IS NULL THEN
        RAISE EXCEPTION 'Conta % sem saldo.', NEW.conta_id;
    END IF;

    NEW.saldo_posterior := v_saldo;
    NEW.saldo_anterior  := v_saldo - NEW.valor;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_conta_aplica_saldo
    BEFORE INSERT ON conta_movimento
    FOR EACH ROW EXECUTE FUNCTION fn_conta_aplica_saldo();

CREATE FUNCTION fn_conta_movimento_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Movimentos da conta não podem ser alterados nem excluídos.'
        USING HINT = 'Para corrigir, registre um ajuste.';
END $$;

CREATE TRIGGER trg_conta_movimento_imutavel
    BEFORE UPDATE OR DELETE ON conta_movimento
    FOR EACH ROW EXECUTE FUNCTION fn_conta_movimento_imutavel();

CREATE VIEW vw_conta_inconsistente AS
SELECT cs.conta_id,
       cs.saldo                  AS saldo_registrado,
       coalesce(sum(m.valor), 0) AS saldo_pelos_movimentos
  FROM conta_saldo cs
  LEFT JOIN conta_movimento m ON m.conta_id = cs.conta_id
 GROUP BY cs.conta_id, cs.saldo
HAVING cs.saldo <> coalesce(sum(m.valor), 0);
COMMENT ON VIEW vw_conta_inconsistente IS 'Precisa ficar vazia: o saldo de cada conta é a soma dos seus movimentos.';

INSERT INTO conta_financeira (nome, tipo, padrao_caixa) VALUES ('Cofre', 'COFRE', TRUE), ('Banco', 'BANCO', FALSE);

-- 2. Formas de pagamento -----------------------------------------------------------------

ALTER TABLE forma_pagamento
    ADD COLUMN taxa_pct         NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN dias_recebimento SMALLINT     NOT NULL DEFAULT 0,
    ADD COLUMN conta_destino_id BIGINT,
    ADD CONSTRAINT fk_fp_conta_destino FOREIGN KEY (conta_destino_id) REFERENCES conta_financeira (id),
    ADD CONSTRAINT ck_fp_taxa CHECK (taxa_pct >= 0 AND taxa_pct <= 20),
    ADD CONSTRAINT ck_fp_dias CHECK (dias_recebimento >= 0 AND dias_recebimento <= 60),
    ADD CONSTRAINT ck_fp_conta_destino CHECK (conta_destino_id IS NULL
                                              OR tipo NOT IN ('DINHEIRO', 'CREDITO_CLIENTE', 'CREDIARIO'));
COMMENT ON COLUMN forma_pagamento.conta_destino_id IS 'Com conta de destino, o pagamento gera recebível. Dinheiro, crédito do cliente e crediário nunca têm.';

UPDATE forma_pagamento
   SET conta_destino_id = (SELECT id FROM conta_financeira WHERE nome = 'Banco')
 WHERE tipo NOT IN ('DINHEIRO', 'CREDITO_CLIENTE', 'CREDIARIO');

-- 3. Caixa ---------------------------------------------------------------------------------

ALTER TABLE caixa_sessao
    ADD COLUMN conta_origem_id  BIGINT,
    ADD COLUMN conta_destino_id BIGINT,
    ADD CONSTRAINT fk_cs_conta_origem  FOREIGN KEY (conta_origem_id)  REFERENCES conta_financeira (id),
    ADD CONSTRAINT fk_cs_conta_destino FOREIGN KEY (conta_destino_id) REFERENCES conta_financeira (id);
COMMENT ON COLUMN caixa_sessao.conta_origem_id IS 'De onde saiu o fundo de troco. Preenchida = sessão integrada às contas, da abertura ao fechamento.';

ALTER TABLE caixa_movimento
    ADD COLUMN conta_id BIGINT,
    ADD CONSTRAINT fk_cm_conta FOREIGN KEY (conta_id) REFERENCES conta_financeira (id);

-- 4 e 5. Tabelas criadas acima; faltam as parcelas e as categorias -----------------------------

CREATE TABLE entrada_mercadoria_parcela (
    id         BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entrada_id BIGINT        NOT NULL,
    numero     INTEGER       NOT NULL,
    vencimento DATE          NOT NULL,
    valor      NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_emp_entrada FOREIGN KEY (entrada_id) REFERENCES entrada_mercadoria (id) ON DELETE CASCADE,
    CONSTRAINT uq_emp_numero  UNIQUE (entrada_id, numero),
    CONSTRAINT ck_emp_numero  CHECK (numero >= 1),
    CONSTRAINT ck_emp_valor   CHECK (valor > 0)
);
COMMENT ON TABLE entrada_mercadoria_parcela IS 'Duplicatas da nota: editáveis com a entrada aberta; ao confirmar, cada uma vira um título a pagar (F15.3).';

INSERT INTO categoria_despesa (nome, grupo) VALUES
    ('Compra de mercadorias', 'MERCADORIA'),
    ('Aluguel', 'OPERACIONAL'),
    ('Energia', 'OPERACIONAL'),
    ('Água', 'OPERACIONAL'),
    ('Internet e telefone', 'OPERACIONAL'),
    ('Salários e encargos', 'OPERACIONAL'),
    ('Pró-labore', 'OPERACIONAL'),
    ('Manutenção', 'OPERACIONAL'),
    ('Marketing', 'OPERACIONAL'),
    ('Impostos sobre vendas', 'IMPOSTO'),
    ('Tarifas bancárias e juros', 'FINANCEIRA'),
    ('Outras', 'OUTRA');

-- 6. Permissão e configuração ---------------------------------------------------------------

INSERT INTO permissao (codigo, descricao) VALUES
    ('FINANCEIRO', 'Contas financeiras, contas a pagar, fluxo de caixa e DRE');

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('financeiro.integrar_caixa', 'false', 'Abertura, sangria, suprimento e fechamento do caixa movimentam as contas (ligar depois de lançar o saldo inicial)');
