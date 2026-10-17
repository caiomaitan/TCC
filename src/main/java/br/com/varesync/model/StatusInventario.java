package br.com.varesync.model;

/** Situação de {@code inventario.status} (mesmos valores de {@code ck_inventario_status}). */
public enum StatusInventario {
    ABERTO("Aberto"),
    APLICADO("Aplicado"),
    CANCELADO("Cancelado");

    private final String rotulo;

    StatusInventario(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
