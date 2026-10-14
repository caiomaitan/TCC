package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Linha do kardex (6.4): data/hora, tipo, quantidade com sinal, saldo após, custo, usuário e o documento de origem.
 * Os ids de venda, inventário e entrada são os números dos documentos (não os ids dos itens).
 */
public record MovimentoKardex(long id, OffsetDateTime criadoEm, TipoMovimento tipo, BigDecimal quantidade,
                              BigDecimal saldoAnterior, BigDecimal saldoPosterior, BigDecimal custoUnitario,
                              String usuarioNome, Long vendaId, Long inventarioId, Long entradaId, String motivo) {

    /** "Venda nº 123", "Inventário nº 4", "Entrada nº 9" ou o motivo do ajuste. */
    public String origem() {
        if (vendaId != null) {
            return "Venda nº " + vendaId;
        }
        if (inventarioId != null) {
            return "Inventário nº " + inventarioId;
        }
        if (entradaId != null) {
            return "Entrada nº " + entradaId;
        }
        return motivo == null ? "" : motivo;
    }
}
