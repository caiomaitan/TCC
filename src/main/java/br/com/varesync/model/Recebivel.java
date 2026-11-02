package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Recebível de um pagamento numa forma com conta de destino (F15.2).
 *
 * @param origem "Venda nº 12" ou "Recebimento nº 3"
 */
public record Recebivel(long id, String origem, int formaId, String forma, long contaId, String conta,
                        BigDecimal valorBruto, BigDecimal taxa, BigDecimal valorLiquido, LocalDate previstoPara,
                        StatusRecebivel status, LocalDate recebidoEm) {
}
