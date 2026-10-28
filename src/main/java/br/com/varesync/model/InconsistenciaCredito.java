package br.com.varesync.model;

import java.math.BigDecimal;

/** Linha de {@code vw_cliente_credito_inconsistente}: saldo gravado diferente da soma dos movimentos (0.2). */
public record InconsistenciaCredito(long clienteId, String nome, BigDecimal saldoRegistrado,
                                    BigDecimal saldoPelosMovimentos) {

    public BigDecimal diferenca() {
        return saldoRegistrado.subtract(saldoPelosMovimentos);
    }
}
