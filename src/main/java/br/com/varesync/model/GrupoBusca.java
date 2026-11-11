package br.com.varesync.model;

/** Grupos de resultado da busca global (Ctrl+K, F21.3), na ordem em que aparecem. */
public enum GrupoBusca {
    TELAS("Telas"),
    PRODUTOS("Produtos"),
    CLIENTES("Clientes"),
    VENDAS("Vendas"),
    FORNECEDORES("Fornecedores");

    private final String rotulo;

    GrupoBusca(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
