package br.com.varesync.model;

import java.math.BigDecimal;

/** Faixa de preço por quantidade (F20.1): a partir de {@code quantidadeMinima} no carrinho, o {@code preco}. */
public record FaixaPreco(long id, BigDecimal quantidadeMinima, BigDecimal preco) {
}
