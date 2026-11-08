-- =====================================================================
-- VareSync — V17: etiquetas de gôndola, parte B (Fase 18: F18.2)
--   1. Dias de validade do pesável na balança (opcional, 1 a 990): vão no campo VVV do ITENSMGV.TXT. 991 a 999 ficam
--      de fora porque têm significado especial no MGV (998 = não imprime datas; 999 = validade digitada na balança).
--   2. Registro de cada carga de balança (carga_balanca) e do que foi em cada item (carga_balanca_item). A carga fica
--      pendente quando um pesável nunca foi carregado ou tem preço vigente, descrição ou dias de validade diferentes
--      do último item carregado.
--   3. Configuração balanca.pasta_carga (pasta onde o ITENSMGV.TXT é gravado; vazia = não configurada).
--   4. Correção da V16: o ck_produto_conteudo deixava passar quantidade sem unidade e unidade sem quantidade (a
--      comparação com nulo dá nulo, e o CHECK aceita). As linhas pela metade são limpas antes do CHECK novo.
-- =====================================================================

-- 1. Validade na balança ----------------------------------------------------------------

ALTER TABLE produto
    ADD COLUMN validade_balanca_dias SMALLINT,
    ADD CONSTRAINT ck_produto_validade_balanca CHECK (validade_balanca_dias BETWEEN 1 AND 990);
COMMENT ON COLUMN produto.validade_balanca_dias IS 'Dias de validade impressos pela balança (F18.2), só no pesável com PLU. Nulo = a balança não imprime validade.';

-- 2. Cargas de balança ------------------------------------------------------------------

CREATE TABLE carga_balanca (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    arquivo    VARCHAR(500),
    itens      INTEGER      NOT NULL,
    usuario_id BIGINT       NOT NULL,
    criado_em  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_cb_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_cb_itens   CHECK (itens > 0)
);
COMMENT ON TABLE  carga_balanca         IS 'Cada carga de itens para a balança (F18.2).';
COMMENT ON COLUMN carga_balanca.arquivo IS 'Caminho do ITENSMGV.TXT gravado; nulo na carga registrada pelo gerador de dados, sem arquivo.';

CREATE TABLE carga_balanca_item (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    carga_id      BIGINT        NOT NULL,
    produto_id    BIGINT        NOT NULL,
    plu           INTEGER       NOT NULL,
    preco         NUMERIC(12,2) NOT NULL,
    descricao     VARCHAR(51)   NOT NULL,
    validade_dias SMALLINT,
    CONSTRAINT fk_cbi_carga   FOREIGN KEY (carga_id)   REFERENCES carga_balanca (id) ON DELETE CASCADE,
    -- Produto excluído (só sem vínculos de venda, estoque etc.) leva os itens de carga junto, como as etiquetas.
    CONSTRAINT fk_cbi_produto FOREIGN KEY (produto_id) REFERENCES produto (id) ON DELETE CASCADE,
    CONSTRAINT ck_cbi_preco   CHECK (preco > 0),
    CONSTRAINT ck_cbi_plu     CHECK (plu > 0)
);
CREATE INDEX ix_cbi_produto ON carga_balanca_item (produto_id, carga_id DESC);
CREATE INDEX ix_cbi_carga   ON carga_balanca_item (carga_id);
COMMENT ON TABLE  carga_balanca_item           IS 'O que foi para a balança em cada carga: PLU, preço por kg vigente, descrição (as duas linhas de 25, sem os espaços do fim) e dias de validade.';
COMMENT ON COLUMN carga_balanca_item.descricao IS 'Descrição como foi no arquivo: maiúsculas, sem acentos, linha 1 e linha 2 juntas por um espaço.';

-- 3. Configuração -----------------------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('balanca.pasta_carga', '', 'Pasta onde o arquivo de itens da balança (ITENSMGV.TXT) é gravado');

-- 4. Correção do CHECK do conteúdo líquido (V16) ----------------------------------------

UPDATE produto
   SET conteudo_quantidade = NULL, conteudo_unidade = NULL
 WHERE (conteudo_quantidade IS NULL) <> (conteudo_unidade IS NULL);

ALTER TABLE produto
    DROP CONSTRAINT ck_produto_conteudo,
    ADD CONSTRAINT ck_produto_conteudo CHECK (
        (conteudo_quantidade IS NULL) = (conteudo_unidade IS NULL)
        AND (conteudo_quantidade IS NULL OR (conteudo_quantidade > 0 AND conteudo_unidade IN ('g', 'kg', 'ml', 'L'))));
