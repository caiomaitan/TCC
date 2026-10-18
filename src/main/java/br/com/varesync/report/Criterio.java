package br.com.varesync.report;

/** Critério de ordenação do ranking de produtos (R02). */
public enum Criterio {
    QUANTIDADE("Quantidade"),
    RECEITA("Receita"),
    LUCRO("Lucro");

    private final String rotulo;

    Criterio(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
