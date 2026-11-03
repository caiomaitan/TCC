package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * O que a aba Fornecedores do produto grava (F16.1). Código e unidade vazios = não informados.
 *
 * @param fator unidades de venda em 1 unidade de compra (≥ 1)
 */
public record DadosVinculo(long fornecedorId, String codigoNoFornecedor, String unidadeCompra, BigDecimal fator,
                           int prazoEntregaDias, boolean principal) {
}
