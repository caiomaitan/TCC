package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Parte de um item da entrada de mercadoria num lote (F17.2), na unidade do produto: código (pode ser vazio), validade
 * (obrigatória) e quantidade (maior que zero).
 */
public record LoteEntrada(String codigo, LocalDate validade, BigDecimal quantidade) {
}
