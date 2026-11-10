-- =====================================================================
-- VareSync — V20: a faixa de atacado copiada no item da venda (Fase 20, parte B: ajuste da parte A)
--   O comprovante e a segunda via liam a quantidade mínima pela faixa atual (venda_item.preco_quantidade_id), e por
--   isso mudavam quando a faixa era alterada ou apagada no cadastro. A quantidade mínima passa a ser copiada no item
--   no momento da venda, como o preço.
-- Linhas antigas: preenchidas pela faixa ligada (a faixa apagada já deixou o item sem referência e sem a linha de
-- atacado, como antes).
-- =====================================================================

ALTER TABLE venda_item
    ADD COLUMN atacado_a_partir_de NUMERIC(14,3);

UPDATE venda_item vi
   SET atacado_a_partir_de = ppq.quantidade_minima
  FROM produto_preco_quantidade ppq
 WHERE ppq.id = vi.preco_quantidade_id;

ALTER TABLE venda_item
    ADD CONSTRAINT ck_vi_atacado CHECK (
        (atacado_a_partir_de IS NULL OR atacado_a_partir_de > 1)
        AND (preco_quantidade_id IS NULL OR atacado_a_partir_de IS NOT NULL));

COMMENT ON COLUMN venda_item.atacado_a_partir_de IS 'Quantidade mínima da faixa de atacado no momento da venda (F20.3); continua no item se a faixa mudar ou sair do cadastro. Nula sem faixa.';
