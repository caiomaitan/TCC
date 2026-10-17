package br.com.varesync.model;

/** {@code venda.status} (mesmos valores de {@code ck_venda_status}). Venda finalizada nunca é apagada: cancelar muda o status. */
public enum StatusVenda {
    FINALIZADA("Finalizada"),
    CANCELADA("Cancelada");

    private final String descricao;

    StatusVenda(String descricao) {
        this.descricao = descricao;
    }

    public String descricao() {
        return descricao;
    }
}
