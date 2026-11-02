package br.com.varesync.report;

import java.time.YearMonth;

/**
 * Filtros do R23, do R24 e do R25 (F15). Nulo = o padrão.
 *
 * @param categoriaDespesa categoria da conta a pagar (R23); nula = todas
 * @param agrupamento      visão do fluxo de caixa (R24); nulo = por dia
 * @param mes              mês do DRE (R25); nulo = o mês de hoje
 */
public record FiltroFinanceiro(Opcao categoriaDespesa, Agrupamento agrupamento, YearMonth mes) {

    public static final FiltroFinanceiro VAZIO = new FiltroFinanceiro(null, null, null);

    public Agrupamento agrupamentoOuPadrao() {
        return agrupamento == null ? Agrupamento.DIA : agrupamento;
    }

    public Long categoriaDespesaId() {
        return categoriaDespesa == null ? null : categoriaDespesa.id();
    }
}
