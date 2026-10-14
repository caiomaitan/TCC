package br.com.varesync.model;

/** Código de barras de um produto (GTIN-8/12/13/14); um produto pode ter vários e um principal. */
public record CodigoBarras(String codigo, boolean principal) {
}
