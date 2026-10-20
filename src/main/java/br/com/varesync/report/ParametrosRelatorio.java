package br.com.varesync.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * O que a consulta precisa além dos filtros: o fuso do período (7.2), a margem mínima
 * ({@code relatorio.margem_minima_pct}) que separa "baixa rentabilidade" de "ok" e o dia de hoje (produtos sem giro).
 */
public record ParametrosRelatorio(ZoneId zona, BigDecimal margemMinimaPct, LocalDate hoje) {
}
