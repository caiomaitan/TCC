package br.com.varesync.model;

/** Tipos de {@code conta_financeira.tipo} (mesmos valores de {@code ck_conta_tipo}). */
public enum TipoConta {
    COFRE("Cofre"),
    BANCO("Banco"),
    OUTRA("Outra");

    private final String rotulo;

    TipoConta(String rotulo) {
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
