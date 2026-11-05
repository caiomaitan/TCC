package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;

/**
 * Item de um pedido de compra (F16.3), na unidade de venda, com o vínculo do produto com o fornecedor do pedido
 * (código no fornecedor, unidade de compra e fator; sem vínculo, fator 1 e os outros nulos).
 */
public record ItemPedido(long id, long produtoId, String produtoNome, String unidade, boolean fracionada,
                         BigDecimal quantidade, BigDecimal custoPrevisto, BigDecimal quantidadeRecebida,
                         String codigoNoFornecedor, String unidadeCompra, BigDecimal fator) {

    /** Quanto falta receber: quantidade − recebida, nunca negativo. */
    public BigDecimal pendente() {
        return quantidade.subtract(quantidadeRecebida).max(BigDecimal.ZERO);
    }

    /** round(quantidade × custo previsto, 2). */
    public BigDecimal valor() {
        return Dinheiro.multiplicar(quantidade, custoPrevisto);
    }

    /** Quantidade na unidade de compra (quantidade ÷ fator, 3 casas). */
    public BigDecimal quantidadeNaUnidadeDeCompra() {
        return quantidade.divide(fator, Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
    }
}
