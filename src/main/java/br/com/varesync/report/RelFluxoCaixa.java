package br.com.varesync.report;

import br.com.varesync.model.Permissao;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R24 — Fluxo de caixa (F15.4): o saldo das contas no início do período e, por dia, semana ou mês, o realizado por
 * tipo de lançamento e o previsto (recebíveis, crediário e contas a pagar; o que está vencido em aberto, na linha "Em
 * atraso" do primeiro grupo da projeção), com o saldo depois de cada linha. Exige também a permissão FINANCEIRO.
 */
public final class RelFluxoCaixa implements Relatorio<RelFluxoCaixa.Linha> {

    static final String SALDO_NO_INICIO = "Saldo no início do período";

    /**
     * @param grupo  início do dia, da semana ou do mês; nulo na linha do saldo de partida
     * @param saidas positivas; zero aparece como "—"
     */
    public record Linha(LocalDate grupo, String periodo, String parte, String lancamento, BigDecimal entradas,
                        BigDecimal saidas, BigDecimal saldo) {
    }

    @Override
    public String codigo() {
        return "R24";
    }

    @Override
    public String nome() {
        return "Fluxo de caixa";
    }

    @Override
    public String descricao() {
        return "Entradas e saídas das contas por dia, semana ou mês: o realizado (sem transferências nem saldo "
                + "inicial) e, de hoje em diante, o previsto, com o saldo projetado.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.AGRUPAMENTO);
    }

    @Override
    public Set<Permissao> permissoesExtras() {
        return Set.of(Permissao.FINANCEIRO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        FluxoCaixa fluxo = FluxoCaixa.calcular(con, filtros.periodo(), filtros.financeiro().agrupamentoOuPadrao(),
                parametros.hoje(), parametros.zona());
        return linhas(fluxo);
    }

    static List<Linha> linhas(FluxoCaixa fluxo) {
        List<Linha> linhas = new ArrayList<>();
        BigDecimal saldo = fluxo.saldoInicial();
        linhas.add(new Linha(null, "", "", SALDO_NO_INICIO, null, null, saldo));
        for (FluxoCaixa.Lancamento l : fluxo.lancamentos()) {
            saldo = saldo.add(l.liquido());
            linhas.add(new Linha(l.grupo(), fluxo.agrupamento().rotuloDoGrupo(l.grupo()), l.parte().rotulo(),
                    l.descricao(), l.entradas().signum() == 0 ? null : l.entradas(),
                    l.saidas().signum() == 0 ? null : l.saidas(), saldo));
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("Período", Linha::periodo).comLargura(1.4),
                Coluna.<Linha>texto("Parte", Linha::parte).comLargura(1.0),
                Coluna.<Linha>texto("Lançamento", Linha::lancamento).comLargura(2.6),
                Coluna.moeda("Entradas", Linha::entradas).comLargura(1.3),
                Coluna.moeda("Saídas", Linha::saidas).comLargura(1.3),
                Coluna.<Linha>de("Saldo", TipoColuna.MOEDA, Linha::saldo).comLargura(1.4)
                        .comTotal(l -> l.isEmpty() ? null : l.get(l.size() - 1).saldo()));
    }
}
