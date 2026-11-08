package br.com.varesync.model;

/** Linha da tabela NCM importada (F19.3): código de 8 dígitos, sem pontos, e descrição. */
public record CodigoNcm(String codigo, String descricao) {
}
