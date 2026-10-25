package br.com.varesync.model;

import java.time.LocalDate;

/**
 * O que a tela de clientes envia ao salvar; o service normaliza e valida (F12.1). {@code consentimentoMarketing}
 * marcado grava a data do consentimento (F12.6).
 */
public record DadosCliente(String nome, String cpfCnpj, String telefone, String email, LocalDate dataNascimento,
                           String cep, String logradouro, String numero, String complemento, String bairro,
                           String cidade, String uf, String observacao, boolean consentimentoMarketing) {

    /** Cadastro rápido do PDV (F12.2): só nome e CPF/CNPJ. */
    public static DadosCliente rapido(String nome, String cpfCnpj) {
        return new DadosCliente(nome, cpfCnpj, null, null, null, null, null, null, null, null, null, null, null,
                false);
    }
}
