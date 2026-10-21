package br.com.varesync.model;

import java.math.BigDecimal;

/** Custo médio de um produto recalculado na confirmação da entrada (A.5): antes, a entrada e o novo custo. */
public record CustoAtualizado(long produtoId, String produtoNome, BigDecimal saldoAnterior, BigDecimal custoAnterior,
                              BigDecimal quantidade, BigDecimal custoEntrada, BigDecimal custoNovo) {
}
