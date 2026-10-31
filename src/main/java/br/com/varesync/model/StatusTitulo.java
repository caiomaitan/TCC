package br.com.varesync.model;

/** {@code titulo_receber.status} (mesmos valores de {@code ck_tr_status}), F14.1. */
public enum StatusTitulo {
    ABERTO("Em aberto"),
    QUITADO("Quitado"),
    CANCELADO("Cancelado");

    private final String rotulo;

    StatusTitulo(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
