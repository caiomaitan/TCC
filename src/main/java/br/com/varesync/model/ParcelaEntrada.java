package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Parcela (duplicata) da nota de uma entrada de mercadoria ({@code entrada_mercadoria_parcela}). */
public record ParcelaEntrada(int numero, LocalDate vencimento, BigDecimal valor) {
}
