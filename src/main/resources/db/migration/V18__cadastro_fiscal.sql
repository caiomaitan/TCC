-- =====================================================================
-- VareSync — V18: cadastro fiscal, parte A (Fase 19: F19.1 a F19.3)
--   1. Permissão FISCAL e as configurações da empresa: regime tributário (SIMPLES ou NORMAL), UF e IE.
--   2. Perfis tributários (perfil_tributario): CFOP, situação do ICMS (CST no regime normal ou CSOSN no Simples),
--      alíquota e redução de base do ICMS, PIS e COFINS, código de benefício e, da reforma tributária, CST do IBS e
--      da CBS e cClassTrib.
--   3. Dados fiscais do produto: NCM, CEST, origem e perfil tributário.
--   4. Tabelas de apoio NCM e CEST, importadas pela tela (sem elas, só o formato é conferido).
--
-- O formato dos códigos (NCM, CEST, CFOP, CST, CSOSN, cBenef, cClassTrib) é conferido pelo sistema ao gravar, e não
-- por CHECK: as regras 2, 3 e 8 do R30 (F19.4) acusam formato inválido gravado fora do sistema, e com CHECK elas
-- nunca disparariam. O banco garante o que não depende de regra fiscal: a origem de 0 a 8, as alíquotas e a redução
-- entre 0 e 100, uma só situação do ICMS por perfil e o nome único.
-- =====================================================================

-- 1. Permissão e configurações ----------------------------------------------------------

INSERT INTO permissao (codigo, descricao) VALUES
    ('FISCAL', 'Cadastro tributário e consistência fiscal');

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('empresa.regime_tributario', 'SIMPLES', 'Regime tributário da empresa: SIMPLES (Simples Nacional) ou NORMAL'),
    ('empresa.uf',                '',        'UF da loja (sigla)'),
    ('empresa.ie',                '',        'Inscrição estadual da loja (só dígitos ou ISENTO)');

-- 2. Perfis tributários -----------------------------------------------------------------

CREATE TABLE perfil_tributario (
    id                BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome              VARCHAR(60)   NOT NULL,
    cfop              VARCHAR(4)    NOT NULL,
    cst_icms          VARCHAR(2),
    csosn             VARCHAR(3),
    aliquota_icms     NUMERIC(5,2)  NOT NULL DEFAULT 0,
    reducao_base_icms NUMERIC(5,2),
    cst_pis           VARCHAR(2)    NOT NULL,
    aliquota_pis      NUMERIC(5,2)  NOT NULL DEFAULT 0,
    cst_cofins        VARCHAR(2)    NOT NULL,
    aliquota_cofins   NUMERIC(5,2)  NOT NULL DEFAULT 0,
    codigo_beneficio  VARCHAR(10),
    cst_ibs_cbs       VARCHAR(3),
    cclass_trib       VARCHAR(6),
    ativo             BOOLEAN       NOT NULL DEFAULT TRUE,
    criado_em         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por        BIGINT        NOT NULL,
    atualizado_em     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_por    BIGINT        NOT NULL,
    CONSTRAINT fk_pt_criado_por     FOREIGN KEY (criado_por)     REFERENCES usuario (id),
    CONSTRAINT fk_pt_atualizado_por FOREIGN KEY (atualizado_por) REFERENCES usuario (id),
    CONSTRAINT ck_pt_nome      CHECK (btrim(nome) <> ''),
    CONSTRAINT ck_pt_icms      CHECK (num_nonnulls(cst_icms, csosn) = 1),
    CONSTRAINT ck_pt_aliquotas CHECK (aliquota_icms BETWEEN 0 AND 100 AND aliquota_pis BETWEEN 0 AND 100
                                      AND aliquota_cofins BETWEEN 0 AND 100),
    CONSTRAINT ck_pt_reducao   CHECK (reducao_base_icms IS NULL OR (reducao_base_icms > 0 AND reducao_base_icms < 100))
);
CREATE UNIQUE INDEX ux_perfil_tributario_nome ON perfil_tributario (lower(nome));
CREATE TRIGGER trg_perfil_tributario_atualizado_em BEFORE UPDATE ON perfil_tributario
    FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();
COMMENT ON TABLE  perfil_tributario                   IS 'Perfis tributários (F19.2): a tributação aplicada aos produtos.';
COMMENT ON COLUMN perfil_tributario.cst_icms          IS 'CST do ICMS (regime normal), 2 dígitos; nulo quando o perfil usa CSOSN.';
COMMENT ON COLUMN perfil_tributario.csosn             IS 'CSOSN (Simples Nacional), 3 dígitos; nulo quando o perfil usa CST.';
COMMENT ON COLUMN perfil_tributario.reducao_base_icms IS 'Percentual de redução da base do ICMS (CST 20 e 70); nulo = sem redução.';
COMMENT ON COLUMN perfil_tributario.codigo_beneficio  IS 'cBenef: 8 caracteres começando pela UF (ex.: SP010360) ou SEM CBENEF; nulo = sem código.';
COMMENT ON COLUMN perfil_tributario.cst_ibs_cbs       IS 'Reforma tributária: CST do IBS e da CBS, 3 dígitos (opcional).';
COMMENT ON COLUMN perfil_tributario.cclass_trib       IS 'Reforma tributária: cClassTrib, 6 dígitos que começam pelo CST do IBS e da CBS (opcional).';

-- 3. Dados fiscais do produto -----------------------------------------------------------

ALTER TABLE produto
    ADD COLUMN ncm                  VARCHAR(8),
    ADD COLUMN cest                 VARCHAR(7),
    ADD COLUMN origem               SMALLINT,
    ADD COLUMN perfil_tributario_id BIGINT,
    ADD CONSTRAINT fk_produto_perfil_tributario FOREIGN KEY (perfil_tributario_id) REFERENCES perfil_tributario (id),
    ADD CONSTRAINT ck_produto_origem CHECK (origem BETWEEN 0 AND 8);
CREATE INDEX ix_produto_perfil_tributario ON produto (perfil_tributario_id);
CREATE INDEX ix_produto_ncm ON produto (ncm);
COMMENT ON COLUMN produto.ncm    IS 'NCM, 8 dígitos (F19.2); nulo = não informado.';
COMMENT ON COLUMN produto.cest   IS 'CEST, 7 dígitos (F19.2); nulo = não informado.';
COMMENT ON COLUMN produto.origem IS 'Origem da mercadoria, 0 a 8 (tabela do ICMS); nula = não informada.';

-- 4. Tabelas de apoio -------------------------------------------------------------------

CREATE TABLE ncm (
    codigo    VARCHAR(8) PRIMARY KEY,
    descricao TEXT       NOT NULL
);
COMMENT ON TABLE ncm IS 'Tabela NCM importada (F19.3): só os códigos de 8 dígitos. Vazia = só o formato é conferido.';

CREATE TABLE cest (
    id        BIGINT     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cest      VARCHAR(7) NOT NULL,
    ncm       VARCHAR(8) NOT NULL,
    descricao TEXT       NOT NULL,
    CONSTRAINT uq_cest_ncm UNIQUE (cest, ncm)
);
CREATE INDEX ix_cest_ncm ON cest (ncm);
COMMENT ON TABLE  cest     IS 'Tabela CEST importada (F19.3): cada CEST e os NCM (ou começos de NCM) que ele abrange.';
COMMENT ON COLUMN cest.ncm IS 'NCM ou começo dele (2 a 8 dígitos): o CEST vale para os NCM que começam por estes dígitos.';
