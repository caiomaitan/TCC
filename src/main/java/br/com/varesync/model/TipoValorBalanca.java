package br.com.varesync.model;

/** O que a etiqueta de balança traz depois do PLU (6.9, {@code balanca.tipo_valor}). */
public enum TipoValorBalanca {
    PRECO("Preço total (centavos)"),
    PESO("Peso (gramas)");

    private final String rotulo;

    TipoValorBalanca(String rotulo) {
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
