package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Linha do razão de um lote (F17.3): data, tipo ("Venda", "Transferência"…), documento ou motivo e a quantidade com
 * sinal (positiva entrou no lote).
 */
public record MovimentoLote(OffsetDateTime criadoEm, String tipo, String documento, BigDecimal quantidade,
                            String usuarioNome) {

    /** Tipo da linha: o tipo do movimento de estoque, "Saldo inicial" ou "Transferência". */
    public static String tipo(TipoLoteMovimento tipoLote, TipoMovimento movimento) {
        return switch (tipoLote) {
            case MOVIMENTO -> movimento == null ? "" : movimento.descricao();
            case SALDO_INICIAL -> "Saldo inicial";
            case TRANSFERENCIA -> "Transferência";
        };
    }
}
