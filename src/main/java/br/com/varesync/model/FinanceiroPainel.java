package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Parte financeira do painel (F15.6), só para quem tem FINANCEIRO.
 *
 * @param contas       contas ativas com o saldo atual
 * @param saldoTotal   soma dos saldos de todas as contas
 * @param resultadoMes resultado líquido do DRE do dia 1º do mês até hoje
 * @param inicioMes    dia 1º do mês
 */
public record FinanceiroPainel(List<ContaFinanceira> contas, BigDecimal saldoTotal, BigDecimal resultadoMes,
                               LocalDate inicioMes) {

    public FinanceiroPainel {
        contas = List.copyOf(contas);
    }
}
