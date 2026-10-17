package br.com.varesync.model;

/** Forma de pagamento ativa ({@code forma_pagamento}). */
public record FormaPagamento(int id, String descricao, TipoFormaPagamento tipo) {

    public boolean dinheiro() {
        return tipo.aceitaTroco();
    }

    @Override
    public String toString() {
        return descricao;
    }
}
