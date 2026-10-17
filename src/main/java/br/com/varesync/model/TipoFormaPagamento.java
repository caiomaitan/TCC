package br.com.varesync.model;

/** {@code forma_pagamento.tipo} (mesmos valores de {@code ck_forma_pagamento_tipo}). */
public enum TipoFormaPagamento {
    DINHEIRO,
    PIX,
    CARTAO_DEBITO,
    CARTAO_CREDITO,
    OUTROS;

    /** Só dinheiro pode passar do que falta e gerar troco (6.6); é também o único que entra na gaveta. */
    public boolean aceitaTroco() {
        return this == DINHEIRO;
    }
}
