package br.com.varesync.service.fiscal;

/** Gravidade de uma inconsistência do R30 (F19.4): o painel conta só os produtos com erro (F19.5). */
public enum Gravidade {
    ERRO("Erro"),
    AVISO("Aviso");

    private final String rotulo;

    Gravidade(String rotulo) {
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
