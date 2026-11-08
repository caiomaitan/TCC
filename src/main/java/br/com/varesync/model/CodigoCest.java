package br.com.varesync.model;

/**
 * Linha da tabela CEST importada (F19.3): CEST de 7 dígitos e um NCM que ele abrange (o código inteiro ou o começo
 * dele, de 2 a 8 dígitos), sem pontos.
 */
public record CodigoCest(String cest, String ncm, String descricao) {
}
