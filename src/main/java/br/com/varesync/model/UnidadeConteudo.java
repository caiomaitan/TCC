package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Unidade do conteúdo líquido do produto (F18.1). A {@link #sigla()} é o valor gravado em
 * {@code produto.conteudo_unidade} e o do CHECK {@code ck_produto_conteudo} (g, kg, ml, L): os nomes Java ficam em
 * maiúsculas, e um teste compara as siglas com o CHECK.
 */
public enum UnidadeConteudo {
    G("g", "kg", BigDecimal.valueOf(1000)),
    KG("kg", "kg", BigDecimal.ONE),
    ML("ml", "L", BigDecimal.valueOf(1000)),
    L("L", "L", BigDecimal.ONE);

    private final String sigla;
    private final String medida;
    private final BigDecimal porMedida;

    UnidadeConteudo(String sigla, String medida, BigDecimal porMedida) {
        this.sigla = sigla;
        this.medida = medida;
        this.porMedida = porMedida;
    }

    public String sigla() {
        return sigla;
    }

    /** A medida do preço por medida: "kg" ou "L". */
    public String medida() {
        return medida;
    }

    /** Quantas unidades desta fazem uma {@link #medida()} (1000 g = 1 kg). */
    public BigDecimal porMedida() {
        return porMedida;
    }

    public static Optional<UnidadeConteudo> daSigla(String sigla) {
        for (UnidadeConteudo u : values()) {
            if (u.sigla.equals(sigla)) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return sigla;
    }
}
