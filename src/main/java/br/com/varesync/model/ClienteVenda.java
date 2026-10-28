package br.com.varesync.model;

import br.com.varesync.util.CpfCnpj;
import java.math.BigDecimal;

/**
 * Cliente como o PDV o vê (F12.2): código, nome, CPF/CNPJ, situação e o saldo de crédito (F12.3) no momento da
 * leitura. Nas listas e no comprovante o CPF aparece mascarado ({@link #documentoOculto()}).
 */
public record ClienteVenda(long id, String nome, String cpfCnpj, boolean ativo, BigDecimal saldoCredito) {

    /** "***.982.247-**" (CPF) ou o CNPJ com máscara; vazio sem documento. */
    public String documentoOculto() {
        return cpfCnpj == null ? "" : CpfCnpj.ocultar(cpfCnpj);
    }

    /** "Maria Silva (***.982.247-**)" ou só o nome. */
    public String descricao() {
        return cpfCnpj == null ? nome : nome + " (" + documentoOculto() + ")";
    }
}
