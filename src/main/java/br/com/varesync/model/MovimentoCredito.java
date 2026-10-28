package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Linha do extrato de crédito do cliente ({@code cliente_credito_movimento}, F12.3). */
public record MovimentoCredito(long id, OffsetDateTime criadoEm, TipoCreditoCliente tipo, BigDecimal valor,
                               BigDecimal saldoAnterior, BigDecimal saldoPosterior, Long vendaId, String motivo,
                               String usuario) {

    /** "Venda nº 12" ou o motivo do ajuste. */
    public String origem() {
        return vendaId != null ? "Venda nº " + vendaId : motivo == null ? "" : motivo;
    }
}
