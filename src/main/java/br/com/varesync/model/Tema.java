package br.com.varesync.model;

/** Tema da interface (F21.1), em {@code usuario_preferencia.tema}. */
public enum Tema {
    CLARO("Claro"),
    ESCURO("Escuro");

    private final String rotulo;

    Tema(String rotulo) {
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
