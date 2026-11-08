package br.com.varesync.model;

/**
 * Dados fiscais de um produto (F19.2), para a aba Produtos do cadastro fiscal.
 *
 * @param ncm      8 dígitos sem pontos; nulo = não informado
 * @param cest     7 dígitos sem pontos; nulo = não informado
 * @param origem   0 a 8; nula = não informada
 * @param perfilId nulo = sem perfil tributário
 */
public record ProdutoFiscal(long id, String nome, String categoriaNome, boolean ativo, String ncm, String cest,
                            Integer origem, Long perfilId, String perfilNome) {
}
