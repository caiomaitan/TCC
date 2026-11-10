package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.Arrays;

/** Tamanho da fonte da interface (F21.1), em % do padrão; {@code usuario_preferencia.fonte} guarda o percentual. */
public enum TamanhoFonte {
    NORMAL(100, "Normal (100%)"),
    GRANDE(115, "Grande (115%)"),
    MAIOR(130, "Maior (130%)");

    private final int percentual;
    private final String rotulo;

    TamanhoFonte(int percentual, String rotulo) {
        this.percentual = percentual;
        this.rotulo = rotulo;
    }

    public int percentual() {
        return percentual;
    }

    /** Fator que multiplica tamanhos e larguras (1, 1,15 ou 1,3). */
    public BigDecimal escala() {
        return BigDecimal.valueOf(percentual).movePointLeft(2);
    }

    public String rotulo() {
        return rotulo;
    }

    /** @throws IllegalArgumentException percentual fora de 100, 115 e 130 (o CHECK do banco também recusa) */
    public static TamanhoFonte dePercentual(int percentual) {
        return Arrays.stream(values()).filter(t -> t.percentual == percentual).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tamanho de fonte inválido: " + percentual));
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
