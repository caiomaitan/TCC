package br.com.varesync.model;

/**
 * Um resultado da busca global (Ctrl+K, F21.3): o registro achado e uma linha de detalhe para reconhecê-lo.
 *
 * @param id      código do produto, do cliente, do fornecedor ou o número da venda
 * @param titulo  o que aparece em destaque (nome, razão social, "Venda nº 123")
 * @param detalhe por que ele apareceu e o que ajuda a reconhecê-lo (código, preço, CPF oculto, data e total)
 */
public record ItemBusca(GrupoBusca grupo, long id, String titulo, String detalhe) {
}
