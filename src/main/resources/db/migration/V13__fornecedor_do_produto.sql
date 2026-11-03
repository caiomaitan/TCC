-- =====================================================================
-- VareSync — V13: compras, parte A (Fase 16: F16.1 e F16.2)
--   1. Fornecedor do produto (produto_fornecedor): código do produto no fornecedor, unidade de compra, fator, último
--      custo, prazo de entrega e o fornecedor principal (no máximo um por produto).
--   2. Configurações da sugestão de compra: compras.dias_media e compras.cobertura_dias.
-- =====================================================================

-- 1. Fornecedor do produto ------------------------------------------------------------

CREATE TABLE produto_fornecedor (
    id                   BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id           BIGINT        NOT NULL,
    fornecedor_id        BIGINT        NOT NULL,
    codigo_no_fornecedor VARCHAR(60),
    unidade_compra       VARCHAR(6),
    fator                NUMERIC(14,3) NOT NULL DEFAULT 1,
    ultimo_custo         NUMERIC(14,4),
    prazo_entrega_dias   INTEGER       NOT NULL DEFAULT 0,
    principal            BOOLEAN       NOT NULL DEFAULT FALSE,
    criado_em            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_em        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_por       BIGINT        NOT NULL,
    CONSTRAINT fk_pf_produto        FOREIGN KEY (produto_id)     REFERENCES produto (id)    ON DELETE CASCADE,
    CONSTRAINT fk_pf_fornecedor     FOREIGN KEY (fornecedor_id)  REFERENCES fornecedor (id) ON DELETE CASCADE,
    CONSTRAINT fk_pf_atualizado_por FOREIGN KEY (atualizado_por) REFERENCES usuario (id),
    CONSTRAINT uq_pf_produto_fornecedor UNIQUE (produto_id, fornecedor_id),
    CONSTRAINT uq_pf_codigo             UNIQUE (fornecedor_id, codigo_no_fornecedor),
    CONSTRAINT ck_pf_codigo  CHECK (codigo_no_fornecedor IS NULL OR btrim(codigo_no_fornecedor) <> ''),
    CONSTRAINT ck_pf_unidade CHECK (unidade_compra IS NULL OR btrim(unidade_compra) <> ''),
    CONSTRAINT ck_pf_fator   CHECK (fator >= 1),
    CONSTRAINT ck_pf_custo   CHECK (ultimo_custo IS NULL OR ultimo_custo >= 0),
    CONSTRAINT ck_pf_prazo   CHECK (prazo_entrega_dias BETWEEN 0 AND 365)
);
CREATE UNIQUE INDEX ux_pf_principal ON produto_fornecedor (produto_id) WHERE principal;
CREATE INDEX ix_pf_fornecedor ON produto_fornecedor (fornecedor_id);
CREATE TRIGGER trg_produto_fornecedor_atualizado_em BEFORE UPDATE ON produto_fornecedor
    FOR EACH ROW EXECUTE FUNCTION fn_set_atualizado_em();
COMMENT ON TABLE  produto_fornecedor                      IS 'Fornecedores de cada produto (F16.1).';
COMMENT ON COLUMN produto_fornecedor.codigo_no_fornecedor IS 'Código do produto no fornecedor (cProd da NF-e); único por fornecedor.';
COMMENT ON COLUMN produto_fornecedor.unidade_compra       IS 'Unidade em que o fornecedor vende (ex.: CX); texto livre.';
COMMENT ON COLUMN produto_fornecedor.fator                IS 'Unidades de venda em 1 unidade de compra (ex.: caixa com 12 = 12).';
COMMENT ON COLUMN produto_fornecedor.ultimo_custo         IS 'Custo por unidade de venda na última entrada confirmada deste fornecedor.';
COMMENT ON COLUMN produto_fornecedor.principal            IS 'Fornecedor principal: dá o prazo e o fator da sugestão de compra.';

-- 2. Configurações --------------------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('compras.dias_media',     '30', 'Dias de venda usados na média diária da sugestão de compra'),
    ('compras.cobertura_dias', '15', 'Dias de venda que a sugestão de compra quer cobrir, além do prazo de entrega');
