package br.com.varesync.report;

/** Critério de ordenação ou valor mostrado: ranking de produtos (R02), curva ABC (R07), grade hora × dia (R10). */
public enum Criterio {
    QUANTIDADE("Quantidade"),
    RECEITA("Receita"),
    LUCRO("Lucro"),
    VENDAS("Nº de vendas");

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
