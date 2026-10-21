package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Produto cuja margem ficou abaixo de {@code relatorio.margem_minima_pct} com o novo custo (6.10): margem atual (nula
 * com preço zero) e o preço que dá a margem mínima (nulo se a margem mínima for 100% ou mais).
 */
public record SugestaoReajuste(long produtoId, String produtoNome, BigDecimal custoNovo, BigDecimal precoVenda,
                               BigDecimal margem, BigDecimal precoSugerido) {
}
