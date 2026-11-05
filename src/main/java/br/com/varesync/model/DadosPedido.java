package br.com.varesync.model;

import java.time.LocalDate;

/** Dados do cabeçalho de um pedido de compra (F16.3): fornecedor (obrigatório), previsão de entrega e observação. */
public record DadosPedido(Long fornecedorId, LocalDate previsaoEntrega, String observacao) {
}
