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

    /** A parte paga vira títulos a receber do cliente identificado (F14.2). */
    public boolean crediario() {
        return tipo == TipoFormaPagamento.CREDIARIO;
    }

    @Override
    public String toString() {
        return descricao;
    }
}
