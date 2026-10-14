package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Total recebido numa forma de pagamento durante a sessão de caixa (vendas finalizadas). No dinheiro, o valor já é
 * líquido de troco ({@code venda_pagamento.valor}).
 */
public record VendasPorForma(String forma, long quantidadeVendas, BigDecimal valor) {
}
