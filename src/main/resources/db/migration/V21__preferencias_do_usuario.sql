-- =====================================================================
-- VareSync — V21: preferências por usuário (Fase 21, parte A: F21.1)
--   Tema (claro ou escuro) e tamanho da fonte (100%, 115% ou 130%) de cada usuário, aplicados no login e na hora
--   em que mudam. Usuário sem linha usa o padrão (claro, 100%). Excluir o usuário leva a preferência junto.
-- =====================================================================

CREATE TABLE usuario_preferencia (
    usuario_id     BIGINT      NOT NULL,
    tema           VARCHAR(10) NOT NULL DEFAULT 'CLARO',
    fonte          SMALLINT    NOT NULL DEFAULT 100,
    atualizado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_usuario_preferencia PRIMARY KEY (usuario_id),
    CONSTRAINT fk_up_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id) ON DELETE CASCADE,
    CONSTRAINT ck_up_tema CHECK (tema IN ('CLARO', 'ESCURO')),
    CONSTRAINT ck_up_fonte CHECK (fonte IN (100, 115, 130))
);

CREATE TRIGGER trg_usuario_preferencia_atualizado_em BEFORE UPDATE ON usuario_preferencia
    FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();

COMMENT ON TABLE usuario_preferencia IS 'Preferências de aparência de cada usuário (F21.1); sem linha = padrão (claro, 100%).';
COMMENT ON COLUMN usuario_preferencia.fonte IS 'Tamanho da fonte em % do padrão: 100, 115 ou 130.';
