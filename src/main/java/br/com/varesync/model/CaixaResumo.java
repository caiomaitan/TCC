package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Uma linha de {@code vw_caixa_resumo}: só vendas FINALIZADAS entram no total e no dinheiro esperado.
 * {@code dinheiroEsperado} = abertura + suprimentos − sangrias + vendas em dinheiro (já líquidas de troco).
 */
public record CaixaResumo(long caixaSessaoId, BigDecimal valorAbertura, BigDecimal suprimentos, BigDecimal sangrias,
                          BigDecimal vendasDinheiro, long quantidadeVendas, BigDecimal totalVendido,
                          long quantidadeCanceladas, BigDecimal dinheiroEsperado) {
}
