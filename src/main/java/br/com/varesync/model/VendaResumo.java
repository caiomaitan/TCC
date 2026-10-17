package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Linha das vendas da sessão (F9): número, hora, operador, total e situação. */
public record VendaResumo(long id, OffsetDateTime dataHora, String operador, int itens, BigDecimal total,
                          StatusVenda status) {

    public boolean cancelada() {
        return status == StatusVenda.CANCELADA;
    }
}
