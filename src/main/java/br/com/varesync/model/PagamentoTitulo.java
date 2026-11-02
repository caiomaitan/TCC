package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Pagamento de um título a pagar ({@code titulo_pagar_pagamento}). */
public record PagamentoTitulo(long id, long tituloId, long contaId, String conta, LocalDate data, BigDecimal valor,
                              BigDecimal juros, BigDecimal desconto, String usuario, boolean estornado,
                              String motivoEstorno) {

    /** O que sai da conta: valor + juros − desconto. */
    public BigDecimal saida() {
        return valor.add(juros).subtract(desconto);
    }
}
