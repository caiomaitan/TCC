package br.com.varesync.model;

/** Situação de {@code entrada_mercadoria.status} (mesmos valores de {@code ck_em_status}). */
public enum StatusEntrada {
    ABERTA("Aberta"),
    CONFIRMADA("Confirmada");

    private final String rotulo;

    StatusEntrada(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
