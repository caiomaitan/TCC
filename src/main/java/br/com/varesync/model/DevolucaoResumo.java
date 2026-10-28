package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Devolução já registrada (lista da venda na tela Devoluções). */
public record DevolucaoResumo(long id, OffsetDateTime dataHora, long vendaId, String usuario, FormaReembolso forma,
                              BigDecimal valor, int itens, String motivo) {
}
