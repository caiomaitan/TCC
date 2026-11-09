-- =====================================================================
-- VareSync — V19: embalagens de venda e preço de atacado (Fase 20: F20.1)
--   1. Embalagem no código de barras: quantas unidades de venda a leitura do código representa (caixa, fardo) e,
--      opcional, o preço próprio da embalagem. O código principal tem quantidade 1.
--   2. Faixas de preço por quantidade (produto_preco_quantidade): a partir de N unidades no carrinho, o preço da faixa.
--   3. A faixa usada no item da venda (venda_item.preco_quantidade_id).
--   4. A faixa que saiu na etiqueta de gôndola (etiqueta_impressao.atacado_quantidade e atacado_preco), para a F20.3.
-- Linhas antigas: os códigos existentes ficam com quantidade 1 e sem preço de embalagem; itens e etiquetas antigos,
-- sem faixa.
-- =====================================================================

-- 1. Embalagem no código de barras ------------------------------------------------------

ALTER TABLE produto_codigo_barras
    ADD COLUMN quantidade      NUMERIC(14,3) NOT NULL DEFAULT 1,
    ADD COLUMN preco_embalagem NUMERIC(12,2),
    ADD CONSTRAINT ck_pcb_quantidade      CHECK (quantidade > 0),
    ADD CONSTRAINT ck_pcb_preco_embalagem CHECK (preco_embalagem IS NULL OR preco_embalagem > 0),
    ADD CONSTRAINT ck_pcb_principal_unitario CHECK (NOT principal OR quantidade = 1);
COMMENT ON COLUMN produto_codigo_barras.quantidade      IS 'Quantas unidades de venda a leitura deste código representa (F20.1): 1 no código da unidade, 12 na caixa com 12.';
COMMENT ON COLUMN produto_codigo_barras.preco_embalagem IS 'Preço próprio da embalagem (opcional). No PDV vale o menor entre ele e a quantidade x o preço unitário vigente.';

-- 2. Faixas de preço por quantidade -----------------------------------------------------

CREATE TABLE produto_preco_quantidade (
    id                BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    produto_id        BIGINT        NOT NULL,
    quantidade_minima NUMERIC(14,3) NOT NULL,
    preco             NUMERIC(12,2) NOT NULL,
    -- Faixa é parte do cadastro, como o código de barras: o produto excluído (só sem vínculos) a leva junto.
    CONSTRAINT fk_ppq_produto    FOREIGN KEY (produto_id) REFERENCES produto (id) ON DELETE CASCADE,
    CONSTRAINT uq_ppq_quantidade UNIQUE (produto_id, quantidade_minima),
    CONSTRAINT ck_ppq_quantidade CHECK (quantidade_minima > 1),
    CONSTRAINT ck_ppq_preco      CHECK (preco > 0)
);
COMMENT ON TABLE produto_preco_quantidade IS 'Preço por quantidade (atacado, F20.1): com a quantidade do produto no carrinho a partir de quantidade_minima, os itens passam ao preço da maior faixa alcançada.';

-- 3. Faixa usada no item da venda -------------------------------------------------------

ALTER TABLE venda_item
    ADD COLUMN preco_quantidade_id BIGINT,
    -- O valor pago fica no item (preco_unitario, valor_bruto); se a faixa sair do cadastro, o item só perde a referência.
    ADD CONSTRAINT fk_vi_preco_quantidade FOREIGN KEY (preco_quantidade_id)
        REFERENCES produto_preco_quantidade (id) ON DELETE SET NULL;
CREATE INDEX ix_vi_preco_quantidade ON venda_item (preco_quantidade_id) WHERE preco_quantidade_id IS NOT NULL;
COMMENT ON COLUMN venda_item.preco_quantidade_id IS 'Faixa de preço por quantidade que deu o preço do item (F20.2); nula sem faixa.';

-- 4. Faixa na etiqueta de gôndola (F20.3) -----------------------------------------------

ALTER TABLE etiqueta_impressao
    ADD COLUMN atacado_quantidade NUMERIC(14,3),
    ADD COLUMN atacado_preco      NUMERIC(12,2),
    ADD CONSTRAINT ck_ei_atacado CHECK (
        (atacado_quantidade IS NULL) = (atacado_preco IS NULL)
        AND (atacado_quantidade IS NULL OR (atacado_quantidade > 1 AND atacado_preco > 0)));
COMMENT ON COLUMN etiqueta_impressao.atacado_quantidade IS 'Quantidade mínima da faixa de atacado que saiu na etiqueta (F20.3); nula sem faixa.';
COMMENT ON COLUMN etiqueta_impressao.atacado_preco      IS 'Preço da faixa de atacado que saiu na etiqueta (F20.3); nulo sem faixa.';
