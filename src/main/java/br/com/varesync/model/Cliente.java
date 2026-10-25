package br.com.varesync.model;

import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente (F12.1). CPF/CNPJ, telefone e CEP gravados sem máscara; a tela mostra com máscara. Campos opcionais são
 * nulos quando não informados. Anonimizado (F12.6): nome "Cliente anonimizado nº id", sem dados pessoais e inativo.
 */
public record Cliente(long id, String nome, String cpfCnpj, String telefone, String email, LocalDate dataNascimento,
                      String cep, String logradouro, String numero, String complemento, String bairro, String cidade,
                      String uf, String observacao, boolean consentimentoMarketing, OffsetDateTime consentimentoEm,
                      boolean anonimizado, boolean ativo, OffsetDateTime criadoEm, String criadoPorNome,
                      OffsetDateTime atualizadoEm, String atualizadoPorNome) {

    /** CPF/CNPJ com máscara, ou vazio. */
    public String documentoFormatado() {
        return cpfCnpj == null ? "" : CpfCnpj.formatar(cpfCnpj);
    }

    public String telefoneFormatado() {
        return telefone == null ? "" : Formatos.telefone(telefone);
    }

    /** "14000-000", ou vazio. */
    public String cepFormatado() {
        return cep == null ? "" : cep.substring(0, 5) + "-" + cep.substring(5);
    }

    /** "Ribeirão Preto/SP", só a cidade, só a UF ou vazio. */
    public String cidadeUf() {
        if (cidade == null) {
            return uf == null ? "" : uf;
        }
        return uf == null ? cidade : cidade + "/" + uf;
    }

    /** Endereço numa linha: "Rua A, 10, apto 2 — Centro — Ribeirão Preto/SP — CEP 14000-000"; vazio sem endereço. */
    public String endereco() {
        List<String> partes = new ArrayList<>();
        StringBuilder rua = new StringBuilder();
        for (String parte : new String[] {logradouro, numero, complemento}) {
            if (parte != null) {
                rua.append(rua.isEmpty() ? "" : ", ").append(parte);
            }
        }
        if (!rua.isEmpty()) {
            partes.add(rua.toString());
        }
        if (bairro != null) {
            partes.add(bairro);
        }
        if (!cidadeUf().isEmpty()) {
            partes.add(cidadeUf());
        }
        if (cep != null) {
            partes.add("CEP " + cepFormatado());
        }
        return String.join(" — ", partes);
    }

    /** "Ativo", "Inativo" ou "Anonimizado". */
    public String situacao() {
        return anonimizado ? "Anonimizado" : ativo ? "Ativo" : "Inativo";
    }

    @Override
    public String toString() {
        return nome;
    }
}
