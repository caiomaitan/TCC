-- =====================================================================
-- VareSync — V4: robustez e segurança, parte A (Fase 10: F10.1 a F10.3)
--   1. usuario.versao_acesso: muda quando o acesso do usuário muda (situação, perfil de supervisor, senha,
--      troca obrigatória ou permissões). A sessão aberta guarda a versão do login e confere o banco a cada
--      operação (F10.1).
--   2. Cancelamentos e liberações gravados na venda (F10.3): quem cancelou o item, quando e quem liberou; quem
--      executou o cancelamento da venda; quem autorizou o desconto acima do limite. Os CHECKs são NOT VALID: as
--      vendas anteriores a esta migration não têm esses dados (o R16 continua lendo a auditoria para elas).
--   3. Configuração seguranca.inatividade_minutos (F10.2).
-- =====================================================================

-- 1. Versão do acesso ----------------------------------------------------

ALTER TABLE usuario ADD COLUMN versao_acesso INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN usuario.versao_acesso IS 'Incrementada por trigger quando mudam ativo, supervisor, senha_hash, deve_trocar_senha ou as permissões. A sessão aberta compara com a versão lida no login.';

-- BEFORE UPDATE em usuario. O nome faz este trigger rodar depois de trg_usuario_atualizado_em (ordem alfabética).
CREATE FUNCTION fn_usuario_versao_acesso() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('varesync.via_permissao', true) = 'on' THEN
        -- UPDATE feito por fn_usuario_permissao_versao: só a versão muda; o horário continua o da aplicação (V3).
        NEW.atualizado_em := OLD.atualizado_em;
        RETURN NEW;
    END IF;
    IF NEW.ativo             IS DISTINCT FROM OLD.ativo
    OR NEW.supervisor        IS DISTINCT FROM OLD.supervisor
    OR NEW.senha_hash        IS DISTINCT FROM OLD.senha_hash
    OR NEW.deve_trocar_senha IS DISTINCT FROM OLD.deve_trocar_senha THEN
        NEW.versao_acesso := OLD.versao_acesso + 1;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_usuario_versao_acesso
    BEFORE UPDATE ON usuario
    FOR EACH ROW EXECUTE FUNCTION fn_usuario_versao_acesso();

-- Permissão incluída ou excluída: incrementa a versão do usuário dono da linha.
CREATE FUNCTION fn_usuario_permissao_versao() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_usuario BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_usuario := OLD.usuario_id;
    ELSE
        v_usuario := NEW.usuario_id;
    END IF;
    PERFORM set_config('varesync.via_permissao', 'on', true);
    UPDATE usuario SET versao_acesso = versao_acesso + 1 WHERE id = v_usuario;
    PERFORM set_config('varesync.via_permissao', 'off', true);
    RETURN NULL;
END $$;

CREATE TRIGGER trg_usuario_permissao_versao
    AFTER INSERT OR DELETE ON usuario_permissao
    FOR EACH ROW EXECUTE FUNCTION fn_usuario_permissao_versao();

-- 2. Cancelamentos e liberações gravados na venda ------------------------

ALTER TABLE venda_item
    ADD COLUMN cancelado_em              TIMESTAMPTZ,
    ADD COLUMN cancelado_por             BIGINT,
    ADD COLUMN cancelamento_liberado_por BIGINT,
    ADD CONSTRAINT fk_vi_cancelado_por  FOREIGN KEY (cancelado_por)             REFERENCES usuario (id),
    ADD CONSTRAINT fk_vi_cancel_liberou FOREIGN KEY (cancelamento_liberado_por) REFERENCES usuario (id),
    ADD CONSTRAINT ck_vi_cancelamento   CHECK (NOT cancelado OR (cancelado_em IS NOT NULL AND cancelado_por IS NOT NULL))
        NOT VALID;
COMMENT ON COLUMN venda_item.cancelado_por             IS 'Quem cancelou o item durante a venda (F6).';
COMMENT ON COLUMN venda_item.cancelamento_liberado_por IS 'Quem liberou o cancelamento do item; nulo quando não houve liberação.';

ALTER TABLE venda
    ADD COLUMN cancelamento_operador_id BIGINT,
    ADD COLUMN desconto_liberado_por    BIGINT,
    ADD CONSTRAINT fk_venda_cancel_operador  FOREIGN KEY (cancelamento_operador_id) REFERENCES usuario (id),
    ADD CONSTRAINT fk_venda_desconto_liberou FOREIGN KEY (desconto_liberado_por)    REFERENCES usuario (id),
    ADD CONSTRAINT ck_venda_cancel_operador  CHECK (status <> 'CANCELADA' OR cancelamento_operador_id IS NOT NULL)
        NOT VALID;
COMMENT ON COLUMN venda.cancelamento_operador_id IS 'Quem executou o cancelamento da venda; cancelada_por é quem autorizou.';
COMMENT ON COLUMN venda.desconto_liberado_por    IS 'Quem autorizou o desconto acima do limite (o supervisor da liberação ou o próprio operador com a permissão); nulo dentro do limite.';

-- 3. Configuração ---------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('seguranca.inatividade_minutos', '15', 'Minutos sem teclado nem mouse até bloquear a tela (0 desliga)');
