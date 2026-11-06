package br.com.varesync.model;

/** Como a etiqueta foi para a gôndola (F18.1); valores iguais aos do CHECK {@code ck_ei_origem}. */
public enum OrigemEtiqueta {
    PDF("PDF"),
    TERMICA("impressora térmica"),
    EM_DIA("marcada como em dia");

    private final String rotulo;

    OrigemEtiqueta(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
