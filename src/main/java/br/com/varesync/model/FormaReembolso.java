package br.com.varesync.model;

import java.util.List;

/** {@code devolucao.forma_reembolso} (mesmos valores de {@code ck_dev_forma}), F13.2. */
public enum FormaReembolso {
    DINHEIRO("Dinheiro", "Dinheiro"),
    CREDITO_CLIENTE("Vale-troca (crédito do cliente)", "Vale-troca"),
    OUTRO("Outro (estorno fora do sistema)", "Outro"),
    /** A devolução inteira abateu títulos em aberto da venda (F14.2): é o sistema que define, não a pessoa. */
    ABATIMENTO("Abatimento nas parcelas do crediário", "Abatimento");

    private final String rotulo;
    private final String curto;

    /** As formas que a pessoa escolhe para o reembolso. */
    public static List<FormaReembolso> escolhiveis() {
        return List.of(DINHEIRO, CREDITO_CLIENTE, OUTRO);
    }

    FormaReembolso(String rotulo, String curto) {
        this.rotulo = rotulo;
        this.curto = curto;
    }

    public String rotulo() {
        return rotulo;
    }

    /** Para colunas estreitas (R20) e o comprovante de 48 colunas. */
    public String curto() {
        return curto;
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
