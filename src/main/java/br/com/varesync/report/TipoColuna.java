package br.com.varesync.report;

/** Tipo de uma coluna de relatório (7.4): define a formatação, o alinhamento padrão e a largura relativa no PDF. */
public enum TipoColuna {
    TEXTO(Alinhamento.ESQUERDA, 3.0),
    INTEIRO(Alinhamento.DIREITA, 0.9),
    MOEDA(Alinhamento.DIREITA, 1.4),
    CUSTO(Alinhamento.DIREITA, 1.4),
    QUANTIDADE(Alinhamento.DIREITA, 1.1),
    PERCENTUAL(Alinhamento.DIREITA, 1.0),
    DATA(Alinhamento.CENTRO, 1.2),
    DATA_HORA(Alinhamento.CENTRO, 1.7);

    public enum Alinhamento { ESQUERDA, CENTRO, DIREITA }

    private final Alinhamento alinhamento;
    private final double largura;

    TipoColuna(Alinhamento alinhamento, double largura) {
        this.alinhamento = alinhamento;
        this.largura = largura;
    }

    public Alinhamento alinhamento() {
        return alinhamento;
    }

    public double largura() {
        return largura;
    }
}
