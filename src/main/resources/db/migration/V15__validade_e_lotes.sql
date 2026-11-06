-- =====================================================================
-- VareSync — V15: validade e lotes, parte A (Fase 17: F17.1)
--   1. produto.controla_validade (desligado nos produtos que já existem).
--   2. Lotes (lote): código (vazio permitido, sem espaços nas pontas, maiúsculo) e validade; o "SEM LOTE" é o lote de
--      código vazio e sem validade, no máximo um por produto.
--   3. Saldo do lote protegido (lote_saldo) e razão imutável (lote_movimento: MOVIMENTO, SALDO_INICIAL ou
--      TRANSFERENCIA), no padrão da seção 0.2 das ampliações; transferência entre lotes (lote_transferencia).
--   4. Garantia no banco: trigger de restrição adiado em movimento_estoque que confere, no commit, que o movimento de
--      produto com o controle ligado foi distribuído inteiro nos lotes.
--   5. vw_lote_inconsistente (precisa ficar vazia).
--   6. Divisão do item da entrada em lotes (entrada_mercadoria_item_lote).
--   7. Configuração validade.alerta_dias.
-- =====================================================================

-- 1. Controle no produto ---------------------------------------------------------------

ALTER TABLE produto ADD COLUMN controla_validade BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN produto.controla_validade IS 'Com o controle ligado, todo movimento de estoque do produto é distribuído em lotes (F17).';

-- 2. Lotes -----------------------------------------------------------------------------

CREATE TABLE lote (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id BIGINT      NOT NULL,
    codigo     VARCHAR(30) NOT NULL DEFAULT '',
    validade   DATE,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    criado_por BIGINT      NOT NULL,
    CONSTRAINT fk_lote_produto    FOREIGN KEY (produto_id) REFERENCES produto (id),
    CONSTRAINT fk_lote_criado_por FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT ck_lote_codigo     CHECK (codigo = upper(btrim(codigo))),
    CONSTRAINT ck_lote_validade   CHECK (validade IS NOT NULL OR codigo = '')
);
-- O mesmo código com outra validade é outro lote; o lote sem código é identificado pela validade; o "SEM LOTE"
-- (código vazio e sem validade) fica no máximo um por produto.
CREATE UNIQUE INDEX ux_lote_produto_codigo_validade
    ON lote (produto_id, codigo, coalesce(validade, 'infinity'::date));
COMMENT ON TABLE  lote          IS 'Lote de um produto com controle de validade (F17). O "SEM LOTE" tem código vazio e validade nula.';
COMMENT ON COLUMN lote.codigo   IS 'Pode ser vazio; gravado sem espaços nas pontas e em maiúsculas.';
COMMENT ON COLUMN lote.validade IS 'Nula só no "SEM LOTE".';

CREATE TABLE lote_saldo (
    lote_id       BIGINT        PRIMARY KEY,
    saldo         NUMERIC(14,3) NOT NULL DEFAULT 0,
    atualizado_em TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_ls_lote FOREIGN KEY (lote_id) REFERENCES lote (id)
);
COMMENT ON TABLE lote_saldo IS 'Saldo do lote. Protegido: alterado só pelo trigger de lote_movimento. Só o "SEM LOTE" pode ficar negativo.';

CREATE TABLE lote_transferencia (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id      BIGINT        NOT NULL,
    lote_origem_id  BIGINT        NOT NULL,
    lote_destino_id BIGINT        NOT NULL,
    quantidade      NUMERIC(14,3) NOT NULL,
    motivo          VARCHAR(200)  NOT NULL,
    usuario_id      BIGINT        NOT NULL,
    criado_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_lt_produto FOREIGN KEY (produto_id)      REFERENCES produto (id),
    CONSTRAINT fk_lt_origem  FOREIGN KEY (lote_origem_id)  REFERENCES lote (id),
    CONSTRAINT fk_lt_destino FOREIGN KEY (lote_destino_id) REFERENCES lote (id),
    CONSTRAINT fk_lt_usuario FOREIGN KEY (usuario_id)      REFERENCES usuario (id),
    CONSTRAINT ck_lt_quantidade CHECK (quantidade > 0),
    CONSTRAINT ck_lt_lotes      CHECK (lote_origem_id <> lote_destino_id),
    CONSTRAINT ck_lt_motivo     CHECK (btrim(motivo) <> '')
);
CREATE INDEX ix_lt_produto ON lote_transferencia (produto_id);
COMMENT ON TABLE lote_transferencia IS 'Transferência entre lotes do mesmo produto: não mexe no estoque do produto; as duas linhas TRANSFERENCIA do razão apontam para ela.';

CREATE TABLE lote_movimento (
    id                   BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lote_id              BIGINT        NOT NULL,
    tipo                 VARCHAR(15)   NOT NULL,
    movimento_estoque_id BIGINT,
    transferencia_id     BIGINT,
    quantidade           NUMERIC(14,3) NOT NULL,
    usuario_id           BIGINT        NOT NULL,
    criado_em            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_lm_lote          FOREIGN KEY (lote_id)              REFERENCES lote (id),
    CONSTRAINT fk_lm_movimento     FOREIGN KEY (movimento_estoque_id) REFERENCES movimento_estoque (id),
    CONSTRAINT fk_lm_transferencia FOREIGN KEY (transferencia_id)     REFERENCES lote_transferencia (id),
    CONSTRAINT fk_lm_usuario       FOREIGN KEY (usuario_id)           REFERENCES usuario (id),
    CONSTRAINT ck_lm_tipo       CHECK (tipo IN ('MOVIMENTO', 'SALDO_INICIAL', 'TRANSFERENCIA')),
    CONSTRAINT ck_lm_quantidade CHECK (quantidade <> 0),
    CONSTRAINT ck_lm_origem     CHECK ((tipo = 'MOVIMENTO')     = (movimento_estoque_id IS NOT NULL)
                                   AND (tipo = 'TRANSFERENCIA') = (transferencia_id IS NOT NULL))
);
CREATE INDEX ix_lm_lote ON lote_movimento (lote_id, criado_em);
CREATE UNIQUE INDEX ux_lm_movimento_lote     ON lote_movimento (movimento_estoque_id, lote_id)
    WHERE movimento_estoque_id IS NOT NULL;
CREATE UNIQUE INDEX ux_lm_transferencia_lote ON lote_movimento (transferencia_id, lote_id)
    WHERE transferencia_id IS NOT NULL;
COMMENT ON TABLE  lote_movimento            IS 'Razão dos lotes. Imutável. O INSERT atualiza lote_saldo via trigger. Um movimento de estoque pode ser dividido em várias linhas.';
COMMENT ON COLUMN lote_movimento.quantidade IS 'Com sinal: positivo entra no lote, negativo sai. No MOVIMENTO, o mesmo sinal do movimento de estoque.';

-- 3. Saldo protegido e razão -----------------------------------------------------------

CREATE FUNCTION fn_lote_saldo_protegido() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.saldo <> 0 THEN
            RAISE EXCEPTION 'Não é permitido apagar o saldo do lote % com saldo %.', OLD.lote_id, OLD.saldo;
        END IF;
        RETURN OLD;
    END IF;
    IF coalesce(current_setting('varesync.via_lote', true), '') <> 'on' THEN
        RAISE EXCEPTION 'O saldo do lote só pode ser alterado registrando um movimento do lote.'
            USING HINT = 'Insira em lote_movimento; o saldo de lote_saldo é atualizado automaticamente.';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_lote_saldo_protegido
    BEFORE INSERT OR UPDATE OR DELETE ON lote_saldo
    FOR EACH ROW EXECUTE FUNCTION fn_lote_saldo_protegido();

-- Lote novo nasce com saldo zero.
CREATE FUNCTION fn_lote_cria_saldo() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM set_config('varesync.via_lote', 'on', true);
    INSERT INTO lote_saldo (lote_id, saldo, atualizado_em) VALUES (NEW.id, 0, NEW.criado_em);
    PERFORM set_config('varesync.via_lote', 'off', true);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_lote_cria_saldo
    AFTER INSERT ON lote
    FOR EACH ROW EXECUTE FUNCTION fn_lote_cria_saldo();

-- Linha do razão: confere lote, produto, controle e sinal; aplica o saldo (o UPDATE trava a linha do lote).
CREATE FUNCTION fn_lote_aplica_saldo() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_produto    BIGINT;
    v_sem_lote   BOOLEAN;
    v_controla   BOOLEAN;
    v_mov        RECORD;
    v_transf     RECORD;
    v_saldo      NUMERIC(14,3);
BEGIN
    SELECT l.produto_id, (l.codigo = '' AND l.validade IS NULL), p.controla_validade
      INTO v_produto, v_sem_lote, v_controla
      FROM lote l
      JOIN produto p ON p.id = l.produto_id
     WHERE l.id = NEW.lote_id;
    IF NOT v_controla THEN
        RAISE EXCEPTION 'O produto % está com o controle de validade desligado: os lotes não são movimentados.', v_produto;
    END IF;

    IF NEW.tipo = 'MOVIMENTO' THEN
        SELECT produto_id, quantidade INTO v_mov FROM movimento_estoque WHERE id = NEW.movimento_estoque_id;
        IF v_mov.produto_id <> v_produto THEN
            RAISE EXCEPTION 'O lote % não é do produto do movimento de estoque nº %.', NEW.lote_id, NEW.movimento_estoque_id;
        END IF;
        IF sign(v_mov.quantidade) <> sign(NEW.quantidade) THEN
            RAISE EXCEPTION 'A quantidade no lote não tem o mesmo sinal do movimento de estoque nº %.', NEW.movimento_estoque_id;
        END IF;
    ELSIF NEW.tipo = 'TRANSFERENCIA' THEN
        SELECT produto_id, lote_origem_id, lote_destino_id INTO v_transf
          FROM lote_transferencia WHERE id = NEW.transferencia_id;
        IF v_transf.produto_id <> v_produto THEN
            RAISE EXCEPTION 'O lote % não é do produto da transferência nº %.', NEW.lote_id, NEW.transferencia_id;
        END IF;
        IF (NEW.quantidade < 0 AND NEW.lote_id <> v_transf.lote_origem_id)
           OR (NEW.quantidade > 0 AND NEW.lote_id <> v_transf.lote_destino_id) THEN
            RAISE EXCEPTION 'Na transferência nº %, a saída é do lote de origem e a entrada é no lote de destino.', NEW.transferencia_id;
        END IF;
    END IF;

    PERFORM set_config('varesync.via_lote', 'on', true);
    UPDATE lote_saldo
       SET saldo         = saldo + NEW.quantidade,
           atualizado_em = NEW.criado_em
     WHERE lote_id = NEW.lote_id
    RETURNING saldo INTO v_saldo;
    PERFORM set_config('varesync.via_lote', 'off', true);

    IF v_saldo IS NULL THEN
        RAISE EXCEPTION 'Lote % sem saldo.', NEW.lote_id;
    END IF;
    IF v_saldo < 0 AND NOT v_sem_lote THEN
        RAISE EXCEPTION 'O lote ficaria com saldo negativo (%): só o "SEM LOTE" pode ficar negativo.', v_saldo;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_lote_aplica_saldo
    BEFORE INSERT ON lote_movimento
    FOR EACH ROW EXECUTE FUNCTION fn_lote_aplica_saldo();

CREATE FUNCTION fn_lote_imutavel() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Movimentos e transferências de lote não podem ser alterados nem excluídos.'
        USING HINT = 'Para corrigir, registre uma transferência entre lotes.';
END $$;

CREATE TRIGGER trg_lote_movimento_imutavel
    BEFORE UPDATE OR DELETE ON lote_movimento
    FOR EACH ROW EXECUTE FUNCTION fn_lote_imutavel();

CREATE TRIGGER trg_lote_transferencia_imutavel
    BEFORE UPDATE OR DELETE ON lote_transferencia
    FOR EACH ROW EXECUTE FUNCTION fn_lote_imutavel();

-- 4. Garantia no commit ----------------------------------------------------------------

CREATE FUNCTION fn_movimento_distribuido_em_lotes() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_controla BOOLEAN;
    v_nome     TEXT;
    v_soma     NUMERIC(14,3);
BEGIN
    SELECT controla_validade, nome INTO v_controla, v_nome FROM produto WHERE id = NEW.produto_id;
    IF NOT coalesce(v_controla, FALSE) THEN
        RETURN NULL;
    END IF;
    SELECT coalesce(sum(quantidade), 0) INTO v_soma
      FROM lote_movimento
     WHERE movimento_estoque_id = NEW.id;
    IF v_soma <> NEW.quantidade THEN
        RAISE EXCEPTION 'O movimento de estoque do produto "%" não foi distribuído nos lotes (movimento %, nos lotes %).',
            v_nome, NEW.quantidade, v_soma;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_movimento_distribuido_em_lotes
    AFTER INSERT ON movimento_estoque
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_movimento_distribuido_em_lotes();

-- 5. Consistência ----------------------------------------------------------------------

CREATE VIEW vw_lote_inconsistente AS
SELECT l.produto_id,
       l.id                      AS lote_id,
       NULL::BIGINT              AS transferencia_id,
       'SALDO_DO_LOTE'::TEXT     AS problema,
       ls.saldo                  AS valor_registrado,
       coalesce(sum(m.quantidade), 0) AS valor_esperado
  FROM lote l
  JOIN lote_saldo ls          ON ls.lote_id = l.id
  LEFT JOIN lote_movimento m  ON m.lote_id = l.id
 GROUP BY l.produto_id, l.id, ls.saldo
HAVING ls.saldo <> coalesce(sum(m.quantidade), 0)
UNION ALL
SELECT p.id, NULL, NULL, 'SOMA_DOS_LOTES',
       coalesce((SELECT sum(ls.saldo) FROM lote l JOIN lote_saldo ls ON ls.lote_id = l.id WHERE l.produto_id = p.id), 0),
       pe.quantidade
  FROM produto p
  JOIN produto_estoque pe ON pe.produto_id = p.id
 WHERE p.controla_validade
   AND pe.quantidade <> coalesce((SELECT sum(ls.saldo) FROM lote l JOIN lote_saldo ls ON ls.lote_id = l.id
                                   WHERE l.produto_id = p.id), 0)
UNION ALL
SELECT t.produto_id, NULL, t.id, 'TRANSFERENCIA',
       coalesce(sum(m.quantidade), 0), 0
  FROM lote_transferencia t
  LEFT JOIN lote_movimento m ON m.transferencia_id = t.id
 GROUP BY t.produto_id, t.id
HAVING coalesce(sum(m.quantidade), 0) <> 0 OR count(m.id) <> 2;
COMMENT ON VIEW vw_lote_inconsistente IS 'Precisa ficar vazia: saldo do lote = soma do razão; com o controle ligado, soma dos lotes = saldo do produto; transferência soma zero.';

-- 6. Divisão do item da entrada em lotes -------------------------------------------------

CREATE TABLE entrada_mercadoria_item_lote (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entrada_item_id BIGINT        NOT NULL,
    codigo          VARCHAR(30)   NOT NULL DEFAULT '',
    validade        DATE          NOT NULL,
    quantidade      NUMERIC(14,3) NOT NULL,
    CONSTRAINT fk_emil_item FOREIGN KEY (entrada_item_id) REFERENCES entrada_mercadoria_item (id) ON DELETE CASCADE,
    CONSTRAINT ck_emil_codigo     CHECK (codigo = upper(btrim(codigo))),
    CONSTRAINT ck_emil_quantidade CHECK (quantidade > 0),
    CONSTRAINT uq_emil_lote UNIQUE (entrada_item_id, codigo, validade)
);
COMMENT ON TABLE entrada_mercadoria_item_lote IS 'Divisão do item da entrada em lotes (F17.2), na unidade do produto. Apagada junto com o item.';

-- 7. Configuração ----------------------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('validade.alerta_dias', '7', 'Dias antes do vencimento em que o lote entra no alerta de validade');
