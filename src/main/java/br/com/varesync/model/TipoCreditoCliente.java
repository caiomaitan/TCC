package br.com.varesync.model;

/**
 * {@code cliente_credito_movimento.tipo} (mesmos valores de {@code ck_ccm_tipo}), com o sinal do valor (F12.3).
 * {@code CREDITO_DEVOLUCAO} só é gravado a partir da F13.
 */
public enum TipoCreditoCliente {
    CASHBACK("Cashback", 1),
    ESTORNO_CASHBACK("Estorno de cashback", -1),
    CREDITO_DEVOLUCAO("Crédito de devolução", 1),
    USO_EM_VENDA("Uso em venda", -1),
    ESTORNO_USO("Estorno de uso", 1),
    AJUSTE("Ajuste", 0);

    private final String rotulo;
    private final int sinal;

    TipoCreditoCliente(String rotulo, int sinal) {
        this.rotulo = rotulo;
        this.sinal = sinal;
    }

    public String rotulo() {
        return rotulo;
    }

    /** 1 credita, −1 debita, 0 qualquer sinal (ajuste). */
    public int sinal() {
        return sinal;
    }

    /** Todos, menos o ajuste, nascem de uma venda. */
    public boolean exigeVenda() {
        return this != AJUSTE;
    }
}
