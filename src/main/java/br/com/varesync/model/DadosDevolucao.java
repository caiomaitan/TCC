package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * O que a tela pede para devolver (F13.2): itens e quantidades, motivo, forma de reembolso e, no vale-troca de uma
 * venda sem cliente, o cliente identificado ou cadastrado na hora.
 *
 * @param clienteId  cliente do vale-troca quando a venda não tem cliente; nulo nos demais casos
 * @param observacao opcional
 */
public record DadosDevolucao(long vendaId, List<Item> itens, String motivo, FormaReembolso forma, Long clienteId,
                             String observacao) {

    /** @param avariado o item não volta ao estoque (entra e sai como perda) */
    public record Item(long vendaItemId, BigDecimal quantidade, boolean avariado) {
    }

    public DadosDevolucao {
        itens = List.copyOf(itens);
    }
}
