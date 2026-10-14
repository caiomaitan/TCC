package br.com.varesync.model;

import java.math.BigDecimal;

/** Produto cujo saldo registrado difere da soma dos movimentos ({@code vw_estoque_inconsistente}). */
public record InconsistenciaEstoque(long produtoId, String nome, BigDecimal saldoRegistrado,
                                    BigDecimal saldoPelosMovimentos) {

    public BigDecimal diferenca() {
        return saldoRegistrado.subtract(saldoPelosMovimentos);
    }
}
