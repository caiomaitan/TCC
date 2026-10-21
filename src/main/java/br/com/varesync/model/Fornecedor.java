package br.com.varesync.model;

import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import java.time.OffsetDateTime;

/**
 * Fornecedor (6.10). CPF/CNPJ e telefone gravados sem máscara (só os caracteres); a tela mostra com máscara.
 * Campos opcionais são nulos quando não informados.
 */
public record Fornecedor(long id, String razaoSocial, String nomeFantasia, String cpfCnpj, String telefone,
                         String email, String contato, String cidade, String uf, String observacao, boolean ativo,
                         OffsetDateTime criadoEm, OffsetDateTime atualizadoEm) {

    /** CPF/CNPJ com máscara, ou vazio. */
    public String documentoFormatado() {
        return cpfCnpj == null ? "" : CpfCnpj.formatar(cpfCnpj);
    }

    public String telefoneFormatado() {
        return telefone == null ? "" : Formatos.telefone(telefone);
    }

    /** "Ribeirão Preto/SP", só a cidade, só a UF ou vazio. */
    public String cidadeUf() {
        if (cidade == null) {
            return uf == null ? "" : uf;
        }
        return uf == null ? cidade : cidade + "/" + uf;
    }

    @Override
    public String toString() {
        return razaoSocial; // exibido nas listas de escolha
    }
}
