package br.com.varesync.model;

/** Filtro da lista de promoções (6.8), em relação a hoje. */
public enum FiltroPromocao {
    VIGENTES("Vigentes"),
    FUTURAS("Futuras"),
    ENCERRADAS("Encerradas e desativadas"),
    TODAS("Todas");

    private final String rotulo;

    FiltroPromocao(String rotulo) {
        this.rotulo = rotulo;
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
