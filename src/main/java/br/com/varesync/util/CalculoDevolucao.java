package br.com.varesync.util;

import java.math.BigDecimal;

/**
 * Valores proporcionais da devolução (F13.2). A parte devolvida de um valor (receita, custo ou cashback) é
 * round(valor × parte ÷ todo, 2); a devolução que completa o todo leva o restante, para que as devoluções somem
 * exatamente o valor original. Nenhuma parte passa do que ainda resta.
 */
public final class CalculoDevolucao {

    private CalculoDevolucao() {
    }

    /**
     * @param valor     valor do item (valor líquido ou custo total)
     * @param vendida   quantidade vendida
     * @param devolvida quantidade já devolvida antes
     * @param jaDevolvido parte do valor já devolvida antes
     * @param quantidade quantidade desta devolução
     */
    public static BigDecimal parte(BigDecimal valor, BigDecimal vendida, BigDecimal devolvida, BigDecimal jaDevolvido,
                                   BigDecimal quantidade) {
        BigDecimal resta = valor.subtract(jaDevolvido);
        if (devolvida.add(quantidade).compareTo(vendida) >= 0) {
            return Dinheiro.dinheiro(resta);
        }
        BigDecimal proporcional = valor.multiply(quantidade)
                .divide(vendida, Dinheiro.ESCALA_DINHEIRO, Dinheiro.ARREDONDAMENTO);
        return proporcional.min(resta).max(Dinheiro.ZERO);
    }

    /**
     * Estorno do cashback: round(cashback × valor devolvido ÷ total da venda, 2); na devolução que completa a venda, o
     * restante. Nunca passa do que ainda não foi estornado.
     *
     * @param cashback    cashback gerado pela venda (positivo; zero sem cashback)
     * @param jaEstornado estornado pelas devoluções anteriores
     * @param totalVenda  {@code venda.valor_total}
     * @param devolvido   valor desta devolução
     * @param completa    esta devolução completa a venda (todos os itens devolvidos)?
     */
    public static BigDecimal estornoCashback(BigDecimal cashback, BigDecimal jaEstornado, BigDecimal totalVenda,
                                             BigDecimal devolvido, boolean completa) {
        BigDecimal resta = cashback.subtract(jaEstornado);
        if (resta.signum() <= 0) {
            return Dinheiro.ZERO;
        }
        if (completa) {
            return Dinheiro.dinheiro(resta);
        }
        if (totalVenda.signum() <= 0) {
            return Dinheiro.ZERO;
        }
        BigDecimal proporcional = cashback.multiply(devolvido)
                .divide(totalVenda, Dinheiro.ESCALA_DINHEIRO, Dinheiro.ARREDONDAMENTO);
        return proporcional.min(resta).max(Dinheiro.ZERO);
    }
}
