package br.com.varesync.model;

/** {@code forma_pagamento.tipo} (mesmos valores de {@code ck_forma_pagamento_tipo}). */
public enum TipoFormaPagamento {
    DINHEIRO("Dinheiro"),
    PIX("PIX"),
    CARTAO_DEBITO("Cartão de débito"),
    CARTAO_CREDITO("Cartão de crédito"),
    OUTROS("Outros");

    private final String rotulo;

    TipoFormaPagamento(String rotulo) {
        this.rotulo = rotulo;
    }

    /** Nome para a tela (cadastro de formas em Configurações). */
    public String rotulo() {
        return rotulo;
    }

    /** Só dinheiro pode passar do que falta e gerar troco (6.6); é também o único que entra na gaveta. */
    public boolean aceitaTroco() {
        return this == DINHEIRO;
    }
}
