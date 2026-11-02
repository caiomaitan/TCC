package br.com.varesync.model;

/**
 * Tipos de {@code conta_movimento.tipo} (mesmos valores de {@code ck_cm2_tipo}), com o sinal que {@code ck_cm2_sinal}
 * exige: 1 entra, −1 sai, 0 tanto faz (F15.1).
 */
public enum TipoContaMovimento {
    ABERTURA_CAIXA("Abertura de caixa", -1),
    FECHAMENTO_CAIXA("Fechamento de caixa", 1),
    SANGRIA("Sangria", 1),
    SUPRIMENTO("Suprimento", -1),
    RECEBIVEL("Recebível", 1),
    ESTORNO_RECEBIVEL("Estorno de recebível", -1),
    PAGAMENTO("Pagamento", -1),
    ESTORNO_PAGAMENTO("Estorno de pagamento", 1),
    REEMBOLSO("Reembolso de devolução", -1),
    TRANSFERENCIA("Transferência", 0),
    SALDO_INICIAL("Saldo inicial", 0),
    AJUSTE("Ajuste", 0);

    private final String rotulo;
    private final int sinal;

    TipoContaMovimento(String rotulo, int sinal) {
        this.rotulo = rotulo;
        this.sinal = sinal;
    }

    public String rotulo() {
        return rotulo;
    }

    public int sinal() {
        return sinal;
    }
}
