package br.com.varesync.model;

import java.math.BigDecimal;

/** Linha de {@code vw_conta_inconsistente} (deve vir vazia), para o Diagnóstico. */
public record InconsistenciaConta(long contaId, String nome, BigDecimal saldoRegistrado,
                                  BigDecimal saldoPelosMovimentos) {

    public BigDecimal diferenca() {
        return saldoRegistrado.subtract(saldoPelosMovimentos);
    }
}
