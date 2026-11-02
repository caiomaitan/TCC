package br.com.varesync.model;

import java.math.BigDecimal;

/** Conta financeira com o saldo atual ({@code conta_financeira} e {@code conta_saldo}). */
public record ContaFinanceira(long id, String nome, TipoConta tipo, boolean ativa, boolean padraoCaixa,
                              BigDecimal saldo) {

    @Override
    public String toString() {
        return nome;
    }
}
