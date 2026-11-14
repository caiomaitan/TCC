package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Forma de pagamento como o cadastro de Configurações a mostra (ativas e desativadas), com o recebimento (F15.2) e o
 * meio de pagamento da NFC-e (F22.1).
 *
 * @param contaDestinoId nula = o pagamento não gera recebível
 * @param tpag           código do meio de pagamento na NFC-e ({@link MeioPagamentoNfce})
 */
public record FormaPagamentoCadastro(int id, String descricao, TipoFormaPagamento tipo, boolean ativa,
                                     BigDecimal taxaPct, int diasRecebimento, Long contaDestinoId, String tpag) {

    /** Com o tPag do tipo. */
    public FormaPagamentoCadastro(int id, String descricao, TipoFormaPagamento tipo, boolean ativa,
                                  BigDecimal taxaPct, int diasRecebimento, Long contaDestinoId) {
        this(id, descricao, tipo, ativa, taxaPct, diasRecebimento, contaDestinoId, MeioPagamentoNfce.padrao(tipo));
    }

    /** Dinheiro vai para a gaveta; crédito do cliente e crediário não movimentam dinheiro na venda. */
    public boolean aceitaContaDestino() {
        return tipo != TipoFormaPagamento.DINHEIRO && tipo != TipoFormaPagamento.CREDITO_CLIENTE
                && tipo != TipoFormaPagamento.CREDIARIO;
    }
}
