-- =====================================================================
-- VareSync — V9: resultado comercial com as devoluções (Fase 13, parte B: F13.3)
--   vw_resultado_item: base dos relatórios de venda e lucro, da tela do cliente e do painel a partir da F13.
--   - linhas de venda: as de vw_venda_item_rentabilidade (itens não cancelados de vendas finalizadas), na data da
--     venda, com sinal +;
--   - linhas de devolução: cada devolucao_item, na data da devolução, com quantidade, receita, CMV e lucro com sinal
--     − e o operador e o cliente da venda original.
--   Receita = vendas − devoluções. As colunas têm os nomes de vw_venda_item_rentabilidade, na mesma ordem; tipo e
--   devolucao_id vêm no fim.
-- =====================================================================

CREATE VIEW vw_resultado_item AS
SELECT r.venda_id,
       r.data_hora,
       r.caixa_sessao_id,
       r.usuario_id,
       r.venda_item_id,
       r.sequencia,
       r.produto_id,
       r.produto_nome,
       r.categoria_id,
       r.categoria_nome,
       r.unidade,
       r.quantidade,
       r.preco_unitario,
       r.valor_bruto,
       r.desconto_total,
       r.receita,
       r.cmv,
       r.lucro_bruto,
       r.em_promocao,
       r.cliente_id,
       'VENDA'::VARCHAR(9)                    AS tipo,
       NULL::BIGINT                           AS devolucao_id
  FROM vw_venda_item_rentabilidade r
UNION ALL
SELECT v.id                                   AS venda_id,
       d.data_hora,
       d.caixa_sessao_id,
       v.usuario_id,
       vi.id                                  AS venda_item_id,
       vi.sequencia,
       vi.produto_id,
       p.nome                                 AS produto_nome,
       p.categoria_id,
       c.nome                                 AS categoria_nome,
       p.unidade,
       -di.quantidade                         AS quantidade,
       vi.preco_unitario,
       -di.valor                              AS valor_bruto,
       0::NUMERIC(12,2)                       AS desconto_total,
       -di.valor                              AS receita,
       -di.custo_total                        AS cmv,
       di.custo_total - di.valor              AS lucro_bruto,
       vi.promocao_id IS NOT NULL             AS em_promocao,
       v.cliente_id,
       'DEVOLUCAO'::VARCHAR(9)                AS tipo,
       d.id                                   AS devolucao_id
  FROM devolucao d
  JOIN devolucao_item di ON di.devolucao_id = d.id
  JOIN venda_item vi     ON vi.id = di.venda_item_id
  JOIN venda v           ON v.id = vi.venda_id
  JOIN produto p         ON p.id = vi.produto_id
  LEFT JOIN categoria c  ON c.id = p.categoria_id;

COMMENT ON VIEW vw_resultado_item IS
    'Base dos relatórios de venda e lucro a partir da F13: itens vendidos (+) e devolvidos (−, na data da devolução). '
    'Receita = vendas − devoluções. Margem agregada = sum(lucro_bruto)/sum(receita)*100 (nunca média de margens).';
COMMENT ON COLUMN vw_resultado_item.tipo IS 'VENDA ou DEVOLUCAO.';
COMMENT ON COLUMN vw_resultado_item.usuario_id IS 'Operador da venda (também nas linhas de devolução).';
COMMENT ON COLUMN vw_resultado_item.caixa_sessao_id IS 'Caixa da venda; na devolução, o caixa de onde saiu o dinheiro (nulo nas outras formas).';
