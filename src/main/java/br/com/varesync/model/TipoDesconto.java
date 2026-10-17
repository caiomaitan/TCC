package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;

/** Desconto no item ou na venda (6.6): em reais ou em percentual do valor. */
public enum TipoDesconto {
    VALOR("R$"),
    PERCENTUAL("%");

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private final String simbolo;

    TipoDesconto(String simbolo) {
        this.simbolo = simbolo;
    }

    /** Desconto em reais: o próprio valor, ou base × percentual ÷ 100 arredondado no centavo (HALF_UP). */
    public BigDecimal emReais(BigDecimal informado, BigDecimal base) {
        return this == VALOR ? Dinheiro.dinheiro(informado)
                : Dinheiro.dinheiro(base.multiply(informado).divide(CEM, 6, Dinheiro.ARREDONDAMENTO));
    }

    @Override
    public String toString() {
        return simbolo;
    }
}
