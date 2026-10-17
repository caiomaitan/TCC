package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Produto como o PDV o lê: preço, custo atual, situação e saldo.
 *
 * @param codigo o código lido (código de barras) ou, na busca por nome, o código de barras principal ou o código
 *               interno; é o que aparece na lista de itens
 */
public record ProdutoVenda(long id, String codigo, String nome, String unidade, boolean fracionada,
                           BigDecimal precoVenda, BigDecimal custo, boolean ativo, BigDecimal saldo) {

    public ProdutoVenda comCodigo(String outroCodigo) {
        return new ProdutoVenda(id, outroCodigo, nome, unidade, fracionada, precoVenda, custo, ativo, saldo);
    }
}
