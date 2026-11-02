package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Forma de pagamento como o cadastro de Configurações a mostra (ativas e desativadas), com o recebimento (F15.2).
 *
 * @param contaDestinoId nula = o pagamento não gera recebível
 */
public record FormaPagamentoCadastro(int id, String descricao, TipoFormaPagamento tipo, boolean ativa,
                                     BigDecimal taxaPct, int diasRecebimento, Long contaDestinoId) {

    /** Dinheiro vai para a gaveta; crédito do cliente e crediário não movimentam dinheiro na venda. */
    public boolean aceitaContaDestino() {
        return tipo != TipoFormaPagamento.DINHEIRO && tipo != TipoFormaPagamento.CREDITO_CLIENTE
                && tipo != TipoFormaPagamento.CREDIARIO;
    }
}
