package br.com.varesync.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Painel da tela principal (7.5). Cartões, gráfico e top 5 só para quem tem RELATORIOS ({@link #comIndicadores()});
 * sem a permissão, vêm vazios e o painel mostra só os alertas e os atalhos.
 *
 * @param hoje            vendas de hoje até agora
 * @param semanaPassada   vendas do mesmo dia da semana anterior, até a mesma hora
 * @param ultimos30Dias   faturamento e lucro por dia, de hoje − 29 até hoje
 * @param topMes          até 5 produtos do mês (dia 1º até hoje), do maior para o menor lucro
 * @param atualizadoEm    quando foi calculado
 * @param financeiro      saldos e resultado do mês (F15.6); nulo sem a permissão FINANCEIRO
 */
public record Painel(boolean comIndicadores, IndicadoresDia hoje, IndicadoresDia semanaPassada,
                     LocalDate diaComparado, List<DiaPainel> ultimos30Dias, List<ProdutoPainel> topMes,
                     AlertasPainel alertas, OffsetDateTime atualizadoEm, FinanceiroPainel financeiro) {

    public Painel {
        ultimos30Dias = List.copyOf(ultimos30Dias);
        topMes = List.copyOf(topMes);
    }
}
