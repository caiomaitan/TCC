package br.com.varesync.model;

import java.math.BigDecimal;

/** Movimento gerado por um ajuste manual: tipo, quantidade com sinal e o saldo antes e depois. */
public record ResultadoAjuste(long movimentoId, TipoMovimento tipo, BigDecimal quantidade, BigDecimal saldoAnterior,
                              BigDecimal saldoPosterior) {
}
