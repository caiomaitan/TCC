package br.com.varesync.model;

import br.com.varesync.util.CpfCnpj;

/**
 * Cliente como o PDV o vê (F12.2): código, nome, CPF/CNPJ e situação. Nas listas e no comprovante o CPF aparece
 * mascarado ({@link #documentoOculto()}).
 */
public record ClienteVenda(long id, String nome, String cpfCnpj, boolean ativo) {

    /** "***.982.247-**" (CPF) ou o CNPJ com máscara; vazio sem documento. */
    public String documentoOculto() {
        return cpfCnpj == null ? "" : CpfCnpj.ocultar(cpfCnpj);
    }

    /** "Maria Silva (***.982.247-**)" ou só o nome. */
    public String descricao() {
        return cpfCnpj == null ? nome : nome + " (" + documentoOculto() + ")";
    }
}
