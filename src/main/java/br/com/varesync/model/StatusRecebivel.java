package br.com.varesync.model;

/** Situações de {@code recebivel.status} (mesmos valores de {@code ck_rcv_status}). */
public enum StatusRecebivel {
    PREVISTO("Previsto"),
    RECEBIDO("Recebido"),
    CANCELADO("Cancelado");

    private final String rotulo;

    StatusRecebivel(String rotulo) {
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
