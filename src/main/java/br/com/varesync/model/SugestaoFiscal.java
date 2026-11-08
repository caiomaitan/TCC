package br.com.varesync.model;

/**
 * O que a NF-e traz para um produto com NCM, CEST ou origem vazios (F19.3). Só os campos vazios no produto e
 * preenchidos (com formato válido) na nota entram; os demais ficam nulos.
 *
 * @param itens números dos itens da nota ("3" ou "3, 5")
 */
public record SugestaoFiscal(long produtoId, String produtoNome, String itens, String ncm, String cest,
                             Integer origem) {

    public boolean vazia() {
        return ncm == null && cest == null && origem == null;
    }
}
