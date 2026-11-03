package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Fornecedor de um produto (F16.1): código do produto no fornecedor ({@code cProd} da NF-e), unidade de compra, fator
 * (unidades de venda em 1 unidade de compra), último custo por unidade de venda, prazo de entrega e se é o principal.
 * Opcionais nulos quando não informados.
 */
public record ProdutoFornecedor(long id, long produtoId, long fornecedorId, String fornecedorNome,
                                boolean fornecedorAtivo, String codigoNoFornecedor, String unidadeCompra,
                                BigDecimal fator, BigDecimal ultimoCusto, int prazoEntregaDias, boolean principal) {
}
