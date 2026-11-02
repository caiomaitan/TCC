package br.com.varesync.model;

/** Situações de {@code titulo_pagar.status} (mesmos valores de {@code ck_tp_status}). */
public enum StatusTituloPagar {
    ABERTO("Aberto"),
    PAGO("Pago"),
    CANCELADO("Cancelado");

    private final String rotulo;

    StatusTituloPagar(String rotulo) {
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
