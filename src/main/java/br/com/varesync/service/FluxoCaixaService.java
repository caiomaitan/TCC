package br.com.varesync.service;

import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.report.Agrupamento;
import br.com.varesync.report.FluxoCaixa;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Fluxo de caixa da aba do Financeiro (F15.4), com a permissão FINANCEIRO: o mesmo cálculo do R24
 * ({@link FluxoCaixa}), numa transação de leitura. Pode demorar com muitos lançamentos: a tela chama em {@code Task}.
 */
public class FluxoCaixaService {

    private final Transacao transacao;
    private final Clock clock;

    public FluxoCaixaService(Transacao transacao, Clock clock) {
        this.transacao = transacao;
        this.clock = clock;
    }

    /** Período inicial da aba: do dia 1º deste mês ao fim do mês que vem. */
    public Periodo periodoPadrao() {
        LocalDate hoje = LocalDate.now(clock);
        return new Periodo(hoje.withDayOfMonth(1), hoje.withDayOfMonth(1).plusMonths(2).minusDays(1));
    }

    public FluxoCaixa calcular(Sessao sessao, Periodo periodo, Agrupamento agrupamento) {
        sessao.exigir(Permissao.FINANCEIRO);
        LocalDate hoje = LocalDate.now(clock);
        FluxoCaixa.conferir(periodo, hoje);
        return transacao.executar(con -> FluxoCaixa.calcular(con, periodo,
                agrupamento == null ? Agrupamento.DIA : agrupamento, hoje, clock.getZone()));
    }
}
