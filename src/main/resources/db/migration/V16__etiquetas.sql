-- =====================================================================
-- VareSync — V16: etiquetas de gôndola, parte A (Fase 18: F18.1)
--   1. Conteúdo líquido do produto (opcional): quantidade e unidade (g, kg, ml ou L), para o preço por kg ou litro
--      da etiqueta.
--   2. Registro de cada impressão de etiqueta (etiqueta_impressao): o preço e a promoção que foram para a gôndola.
--      A etiqueta fica pendente quando o preço vigente ou a promoção vigente difere da última impressão.
--   3. Configurações etiqueta.colunas, etiqueta.linhas, etiqueta.modelo_termica e etiqueta.impressora_termica.
--   (balanca.pasta_carga e a carga de balança entram na parte B.)
-- =====================================================================

-- 1. Conteúdo líquido -------------------------------------------------------------------

ALTER TABLE produto
    ADD COLUMN conteudo_quantidade NUMERIC(12,3),
    ADD COLUMN conteudo_unidade    VARCHAR(2),
    ADD CONSTRAINT ck_produto_conteudo CHECK (
        (conteudo_quantidade IS NULL AND conteudo_unidade IS NULL)
        OR (conteudo_quantidade > 0 AND conteudo_unidade IN ('g', 'kg', 'ml', 'L')));
COMMENT ON COLUMN produto.conteudo_quantidade IS 'Conteúdo líquido da embalagem (F18.1), para o preço por kg ou litro na etiqueta. Opcional.';
COMMENT ON COLUMN produto.conteudo_unidade    IS 'Unidade do conteúdo líquido: g, kg, ml ou L.';

-- 2. Impressões de etiqueta -------------------------------------------------------------

CREATE TABLE etiqueta_impressao (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id  BIGINT        NOT NULL,
    preco       NUMERIC(12,2) NOT NULL,
    promocao_id BIGINT,
    origem      VARCHAR(10)   NOT NULL,
    usuario_id  BIGINT        NOT NULL,
    criado_em   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Produto excluído (só sem vínculos de venda, estoque etc.) leva as impressões junto; promoção excluída (só a que
    -- nunca foi usada) deixa a impressão sem promoção, e a etiqueta volta a ficar pendente.
    CONSTRAINT fk_ei_produto  FOREIGN KEY (produto_id)  REFERENCES produto (id) ON DELETE CASCADE,
    CONSTRAINT fk_ei_promocao FOREIGN KEY (promocao_id) REFERENCES promocao (id) ON DELETE SET NULL,
    CONSTRAINT fk_ei_usuario  FOREIGN KEY (usuario_id)  REFERENCES usuario (id),
    CONSTRAINT ck_ei_preco    CHECK (preco >= 0),
    CONSTRAINT ck_ei_origem   CHECK (origem IN ('PDF', 'TERMICA', 'EM_DIA'))
);
CREATE INDEX ix_ei_produto ON etiqueta_impressao (produto_id, criado_em DESC, id DESC);
COMMENT ON TABLE  etiqueta_impressao        IS 'Cada etiqueta de gôndola impressa (F18.1), com o preço e a promoção que foram para a gôndola.';
COMMENT ON COLUMN etiqueta_impressao.origem IS 'PDF (folha A4), TERMICA (impressora térmica) ou EM_DIA ("Marcar como em dia", sem imprimir).';

-- 3. Configurações ----------------------------------------------------------------------

INSERT INTO configuracao (chave, valor, descricao) VALUES
    ('etiqueta.colunas',            '3', 'Etiquetas por linha na folha A4'),
    ('etiqueta.linhas',             '8', 'Linhas de etiquetas na folha A4'),
    ('etiqueta.modelo_termica',     '',  'Modelo ZPL ou DPL da etiqueta na impressora térmica'),
    ('etiqueta.impressora_termica', '',  'Nome da impressora térmica no Windows');
