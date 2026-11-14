package br.com.varesync.model;

/**
 * O que a NF-e traz para um produto (F19.3 e F22.4): o NCM, o CEST e a origem só nos campos vazios no produto e
 * preenchidos (com formato válido) na nota; o ST retido por unidade de venda sempre que a nota o traz, porque muda a
 * cada compra. O que não entra fica nulo.
 *
 * @param itens números dos itens da nota ("3" ou "3, 5")
 * @param st    ST por unidade de venda, em 4 casas; nulo sem ST na nota
 */
public record SugestaoFiscal(long produtoId, String produtoNome, String itens, String ncm, String cest,
                             Integer origem, ItemTributavel.StRetido st) {

    /** Sem o ST. */
    public SugestaoFiscal(long produtoId, String produtoNome, String itens, String ncm, String cest, Integer origem) {
        this(produtoId, produtoNome, itens, ncm, cest, origem, null);
    }

    public boolean vazia() {
        return ncm == null && cest == null && origem == null && st == null;
    }
}
