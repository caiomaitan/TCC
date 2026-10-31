package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Uma linha de {@code vw_caixa_resumo}: só vendas FINALIZADAS entram no total e no dinheiro esperado.
 * {@code dinheiroEsperado} = abertura + suprimentos − sangrias + vendas em dinheiro (já líquidas de troco) −
 * devoluções reembolsadas em dinheiro (F13, V8) + recebimentos em dinheiro não estornados (F14, V10).
 */
public record CaixaResumo(long caixaSessaoId, BigDecimal valorAbertura, BigDecimal suprimentos, BigDecimal sangrias,
                          BigDecimal vendasDinheiro, long quantidadeVendas, BigDecimal totalVendido,
                          long quantidadeCanceladas, BigDecimal dinheiroEsperado, BigDecimal devolucoesDinheiro,
                          BigDecimal recebimentosDinheiro) {

    /** Sessão sem recebimentos. */
    public CaixaResumo(long caixaSessaoId, BigDecimal valorAbertura, BigDecimal suprimentos, BigDecimal sangrias,
                       BigDecimal vendasDinheiro, long quantidadeVendas, BigDecimal totalVendido,
                       long quantidadeCanceladas, BigDecimal dinheiroEsperado, BigDecimal devolucoesDinheiro) {
        this(caixaSessaoId, valorAbertura, suprimentos, sangrias, vendasDinheiro, quantidadeVendas, totalVendido,
                quantidadeCanceladas, dinheiroEsperado, devolucoesDinheiro, BigDecimal.ZERO);
    }
}
