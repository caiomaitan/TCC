-- =====================================================================
-- VareSync — V23: tributos da venda (Fase 22, parte A: F22.1)
--   1. venda_item_tributo: os tributos calculados de cada item vendido (F22.2), com a cópia do cadastro fiscal do
--      produto no momento da venda. Uma linha por item não cancelado cujo produto tinha NCM, origem e perfil
--      tributário ativo. As vendas anteriores a esta migration ficam sem linhas ("sem tributação calculada").
--   2. perfil_tributario: alíquota do FCP (CST 00 e 20) e redução de alíquota do IBS e da CBS.
--   3. produto: ST retido por unidade de venda (preenchido pela NF-e de compra na parte B, F22.4).
--   4. ibpt: tabela de tributos aproximados do IBPT (Lei 12.741/2012), importada pela tela (F22.3).
--   5. forma_pagamento.tpag: o meio de pagamento da NFC-e, preenchido pelo tipo (também nas formas novas, pelo
--      trigger), com o CHECK dos códigos da tabela oficial que servem para a NFC-e.
--   6. Configurações fiscal.*: alíquotas de teste do IBS e da CBS de 2026 e a exclusão do ICMS da base do PIS e da
--      COFINS (Tema 69 do STF).
-- =====================================================================

-- 1. Tributos de cada item vendido ------------------------------------------------------

CREATE TABLE venda_item_tributo (
    venda_item_id            BIGINT        PRIMARY KEY,
    -- cópia do cadastro
    ncm                      VARCHAR(8)    NOT NULL,
    cest                     VARCHAR(7),
    origem                   SMALLINT      NOT NULL,
    cfop                     VARCHAR(4)    NOT NULL,
    cst_icms                 VARCHAR(2),
    csosn                    VARCHAR(3),
    codigo_beneficio         VARCHAR(10),
    cst_pis                  VARCHAR(2)    NOT NULL,
    cst_cofins               VARCHAR(2)    NOT NULL,
    cst_ibs_cbs              VARCHAR(3),
    cclass_trib              VARCHAR(6),
    -- ICMS próprio e FCP
    base_icms                NUMERIC(14,2) NOT NULL DEFAULT 0,
    aliquota_icms            NUMERIC(5,2)  NOT NULL DEFAULT 0,
    reducao_base_icms        NUMERIC(5,2),
    valor_icms               NUMERIC(14,2) NOT NULL DEFAULT 0,
    aliquota_fcp             NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_fcp                NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- ST retido anteriormente (CST 60 e CSOSN 500); nulos sem o dado no produto
    base_st_retido           NUMERIC(14,2),
    aliquota_st              NUMERIC(5,2),
    icms_substituto          NUMERIC(14,2),
    icms_st_retido           NUMERIC(14,2),
    -- PIS e COFINS
    base_pis                 NUMERIC(14,2) NOT NULL DEFAULT 0,
    aliquota_pis             NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_pis                NUMERIC(14,2) NOT NULL DEFAULT 0,
    base_cofins              NUMERIC(14,2) NOT NULL DEFAULT 0,
    aliquota_cofins          NUMERIC(5,2)  NOT NULL DEFAULT 0,
    valor_cofins             NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- IBS e CBS (regime normal com o CST do IBS e da CBS no perfil); nulos quando não se aplicam
    base_ibs_cbs             NUMERIC(14,2),
    aliquota_cbs             NUMERIC(7,4),
    aliquota_ibs_uf          NUMERIC(7,4),
    aliquota_ibs_mun         NUMERIC(7,4),
    reducao_aliquota_ibs_cbs NUMERIC(5,2),
    valor_cbs                NUMERIC(14,2),
    valor_ibs_uf             NUMERIC(14,2),
    valor_ibs_mun            NUMERIC(14,2),
    -- tributos aproximados (Lei 12.741/2012); nulos sem a linha vigente da tabela IBPT
    trib_federal             NUMERIC(14,2),
    trib_estadual            NUMERIC(14,2),
    trib_municipal           NUMERIC(14,2),
    ibpt_versao              VARCHAR(20),
    CONSTRAINT fk_vit_venda_item FOREIGN KEY (venda_item_id) REFERENCES venda_item (id),
    CONSTRAINT ck_vit_origem     CHECK (origem BETWEEN 0 AND 8),
    CONSTRAINT ck_vit_icms       CHECK (num_nonnulls(cst_icms, csosn) = 1),
    CONSTRAINT ck_vit_valores    CHECK (base_icms >= 0 AND valor_icms >= 0 AND valor_fcp >= 0 AND base_pis >= 0
                                        AND valor_pis >= 0 AND base_cofins >= 0 AND valor_cofins >= 0),
    CONSTRAINT ck_vit_ibs_cbs    CHECK ((base_ibs_cbs IS NULL) = (valor_cbs IS NULL)
                                        AND (base_ibs_cbs IS NULL) = (valor_ibs_uf IS NULL)
                                        AND (base_ibs_cbs IS NULL) = (valor_ibs_mun IS NULL)),
    CONSTRAINT ck_vit_aproximados CHECK ((trib_federal IS NULL) = (trib_estadual IS NULL)
                                         AND (trib_federal IS NULL) = (trib_municipal IS NULL))
);
COMMENT ON TABLE venda_item_tributo IS 'Tributos calculados de cada item vendido (F22.2), com a cópia do cadastro fiscal do momento da venda. Só registra: nenhum valor da venda muda.';
COMMENT ON COLUMN venda_item_tributo.base_ibs_cbs IS 'Valor líquido − ICMS − FCP − PIS − COFINS (NT 2025.002-RTC); nulo fora do regime normal ou sem o CST do IBS e da CBS.';
COMMENT ON COLUMN venda_item_tributo.trib_federal IS 'Tributos aproximados federais (IBPT, Lei 12.741/2012); nulo sem a linha vigente para o NCM.';

-- 2. Perfil tributário --------------------------------------------------------------------

ALTER TABLE perfil_tributario
    ADD COLUMN aliquota_fcp             NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN reducao_aliquota_ibs_cbs NUMERIC(5,2),
    ADD CONSTRAINT ck_pt_fcp            CHECK (aliquota_fcp BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_pt_reducao_ibs_cbs CHECK (reducao_aliquota_ibs_cbs IS NULL
                                                OR (reducao_aliquota_ibs_cbs > 0 AND reducao_aliquota_ibs_cbs <= 100));
COMMENT ON COLUMN perfil_tributario.aliquota_fcp IS 'Fundo de Combate à Pobreza, % sobre a base do ICMS (só nos CST 00 e 20).';
COMMENT ON COLUMN perfil_tributario.reducao_aliquota_ibs_cbs IS 'Redução das alíquotas do IBS e da CBS, %; nula = sem redução.';

-- 3. ST retido do produto -------------------------------------------------------------------

ALTER TABLE produto
    ADD COLUMN st_base_unitaria            NUMERIC(14,4),
    ADD COLUMN st_aliquota                 NUMERIC(5,2),
    ADD COLUMN st_icms_substituto_unitario NUMERIC(14,4),
    ADD COLUMN st_icms_retido_unitario     NUMERIC(14,4),
    ADD CONSTRAINT ck_produto_st CHECK (coalesce(st_base_unitaria, 0) >= 0 AND coalesce(st_aliquota, 0) BETWEEN 0 AND 100
                                        AND coalesce(st_icms_substituto_unitario, 0) >= 0
                                        AND coalesce(st_icms_retido_unitario, 0) >= 0);
COMMENT ON COLUMN produto.st_base_unitaria IS 'ST retido (F22.4): base do ST retido por unidade de venda, da última NF-e de compra.';
COMMENT ON COLUMN produto.st_icms_retido_unitario IS 'ST retido (F22.4): ICMS ST retido por unidade de venda, da última NF-e de compra.';

-- 4. Tabela IBPT ----------------------------------------------------------------------------

CREATE TABLE ibpt (
    codigo                      VARCHAR(8)   NOT NULL,
    ex                          VARCHAR(3)   NOT NULL DEFAULT '',
    descricao                   TEXT         NOT NULL,
    aliquota_nacional_federal   NUMERIC(7,4) NOT NULL,
    aliquota_importados_federal NUMERIC(7,4) NOT NULL,
    aliquota_estadual           NUMERIC(7,4) NOT NULL,
    aliquota_municipal          NUMERIC(7,4) NOT NULL,
    vigencia_inicio             DATE         NOT NULL,
    vigencia_fim                DATE         NOT NULL,
    chave                       VARCHAR(20)  NOT NULL DEFAULT '',
    versao                      VARCHAR(20)  NOT NULL DEFAULT '',
    fonte                       VARCHAR(100) NOT NULL DEFAULT '',
    CONSTRAINT pk_ibpt PRIMARY KEY (codigo, ex),
    CONSTRAINT ck_ibpt_aliquotas CHECK (aliquota_nacional_federal BETWEEN 0 AND 100
                                        AND aliquota_importados_federal BETWEEN 0 AND 100
                                        AND aliquota_estadual BETWEEN 0 AND 100 AND aliquota_municipal BETWEEN 0 AND 100),
    CONSTRAINT ck_ibpt_vigencia CHECK (vigencia_fim >= vigencia_inicio)
);
COMMENT ON TABLE ibpt IS 'Tabela do IBPT (De Olho no Imposto), só as linhas de NCM (tipo 0). Importar troca a tabela inteira (F22.3).';
COMMENT ON COLUMN ibpt.ex IS 'Exceção da TIPI; vazio = a linha do NCM usada nos tributos aproximados.';

-- 5. Meio de pagamento da NFC-e -------------------------------------------------------------

ALTER TABLE forma_pagamento ADD COLUMN tpag VARCHAR(2);
UPDATE forma_pagamento
   SET tpag = CASE tipo
                  WHEN 'DINHEIRO'        THEN '01'
                  WHEN 'CARTAO_CREDITO'  THEN '03'
                  WHEN 'CARTAO_DEBITO'   THEN '04'
                  WHEN 'CREDIARIO'       THEN '05'
                  WHEN 'PIX'             THEN '20'
                  WHEN 'CREDITO_CLIENTE' THEN '21'
                  ELSE CASE WHEN descricao = 'Vale-alimentação' THEN '10' ELSE '99' END
              END;

-- As formas novas recebem o tPag do tipo; a tela de Configurações troca depois, se for o caso.
CREATE FUNCTION fn_forma_pagamento_tpag() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tpag IS NULL THEN
        NEW.tpag := CASE NEW.tipo
                        WHEN 'DINHEIRO'        THEN '01'
                        WHEN 'CARTAO_CREDITO'  THEN '03'
                        WHEN 'CARTAO_DEBITO'   THEN '04'
                        WHEN 'CREDIARIO'       THEN '05'
                        WHEN 'PIX'             THEN '20'
                        WHEN 'CREDITO_CLIENTE' THEN '21'
                        ELSE '99'
                    END;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_forma_pagamento_tpag BEFORE INSERT ON forma_pagamento
    FOR EACH ROW EXECUTE FUNCTION fn_forma_pagamento_tpag();

ALTER TABLE forma_pagamento
    ALTER COLUMN tpag SET NOT NULL,
    ADD CONSTRAINT ck_fp_tpag CHECK (tpag IN ('01', '02', '03', '04', '05', '10', '11', '12', '13', '14', '15', '16',
                                              '17', '18', '19', '20', '21', '22', '23', '24', '99'));
COMMENT ON COLUMN forma_pagamento.tpag IS 'Meio de pagamento da NFC-e (tPag), da tabela oficial; preenchido pelo tipo e editável em Configurações.';

-- 6. Configurações ------------------------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('fiscal.aliquota_cbs',                 '0.9',  'Alíquota da CBS (%), de teste em 2026'),
    ('fiscal.aliquota_ibs_uf',              '0.1',  'Alíquota do IBS da UF (%), de teste em 2026'),
    ('fiscal.aliquota_ibs_mun',             '0',    'Alíquota do IBS do município (%), de teste em 2026'),
    ('fiscal.excluir_icms_base_pis_cofins', 'true', 'Excluir o ICMS da base do PIS e da COFINS (Tema 69 do STF)');
