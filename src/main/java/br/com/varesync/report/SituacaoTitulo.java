package br.com.varesync.report;

/** Filtro de situação do R21 (F14.4). */
public enum SituacaoTitulo {
    EM_ABERTO("Em aberto"),
    VENCIDOS("Vencidos"),
    QUITADOS("Quitados"),
    CANCELADOS("Cancelados"),
    TODOS("Todos");

    private final String rotulo;

    SituacaoTitulo(String rotulo) {
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
