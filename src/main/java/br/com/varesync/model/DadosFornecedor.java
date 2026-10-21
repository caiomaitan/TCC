package br.com.varesync.model;

/** O que a tela de fornecedores envia ao salvar; o service normaliza e valida (6.10). */
public record DadosFornecedor(String razaoSocial, String nomeFantasia, String cpfCnpj, String telefone,
                              String email, String contato, String cidade, String uf, String observacao) {
}
