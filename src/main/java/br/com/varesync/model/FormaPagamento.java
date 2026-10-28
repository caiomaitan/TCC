package br.com.varesync.model;

/** Forma de pagamento ativa ({@code forma_pagamento}). */
public record FormaPagamento(int id, String descricao, TipoFormaPagamento tipo) {

    public boolean dinheiro() {
        return tipo.aceitaTroco();
    }

    /** Paga com o crédito do cliente identificado (F12.4). */
    public boolean creditoCliente() {
        return tipo == TipoFormaPagamento.CREDITO_CLIENTE;
    }

    @Override
    public String toString() {
        return descricao;
    }
}
