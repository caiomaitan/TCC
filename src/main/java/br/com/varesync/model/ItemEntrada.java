package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Item de uma entrada de mercadoria: produto, quantidade, custo unitário (4 casas) e valor total da linha. Traz também
 * o custo médio e o preço atuais do produto, para comparar antes de confirmar.
 */
public record ItemEntrada(long id, long produtoId, String produtoNome, String codigoBarras, String unidade,
                          boolean unidadeFracionada, BigDecimal quantidade, BigDecimal custoUnitario,
                          BigDecimal valorTotal, BigDecimal custoAtual, BigDecimal precoVenda) {
}
