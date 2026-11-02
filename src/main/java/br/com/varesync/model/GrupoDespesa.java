package br.com.varesync.model;

/** Grupos de {@code categoria_despesa.grupo} (mesmos valores de {@code ck_cd_grupo}). */
public enum GrupoDespesa {
    MERCADORIA("Mercadoria"),
    OPERACIONAL("Operacional"),
    IMPOSTO("Imposto"),
    FINANCEIRA("Financeira"),
    OUTRA("Outra");

    private final String rotulo;

    GrupoDespesa(String rotulo) {
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
