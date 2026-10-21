package br.com.varesync.util;

import java.math.BigDecimal;

/**
 * Custo médio ponderado da entrada de mercadoria (6.10, A.5): com saldo positivo, (saldo × custo atual + quantidade ×
 * custo da entrada) ÷ (saldo + quantidade), 4 casas, HALF_UP; com saldo zero ou negativo, vale o custo da entrada
 * (o saldo negativo não tem custo de verdade para ponderar).
 */
public final class CustoMedio {

    private CustoMedio() {
    }

    /**
     * @param saldo        saldo do produto antes da entrada (lido com a linha travada)
     * @param custoAtual   custo médio do produto antes da entrada
     * @param quantidade   quantidade que entra (&gt; 0)
     * @param custoEntrada custo unitário da nota
     * @return o novo custo médio, com 4 casas
     */
    public static BigDecimal calcular(BigDecimal saldo, BigDecimal custoAtual, BigDecimal quantidade,
                                      BigDecimal custoEntrada) {
        if (quantidade.signum() <= 0) {
            throw new IllegalArgumentException("A quantidade da entrada deve ser maior que zero.");
        }
        if (saldo.signum() <= 0) {
            return Dinheiro.custo(custoEntrada);
        }
        BigDecimal valor = saldo.multiply(custoAtual).add(quantidade.multiply(custoEntrada));
        return valor.divide(saldo.add(quantidade), Dinheiro.ESCALA_CUSTO, Dinheiro.ARREDONDAMENTO);
    }
}
