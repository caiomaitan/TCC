package br.com.varesync.model;

/** Filtro de situação da busca de produtos. Por padrão só os ativos (6.3). */
public enum SituacaoProduto {
    ATIVOS("Ativos"),
    INATIVOS("Inativos"),
    TODOS("Todos");

    private final String rotulo;

    SituacaoProduto(String rotulo) {
        this.rotulo = rotulo;
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
