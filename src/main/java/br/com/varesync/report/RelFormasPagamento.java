package br.com.varesync.report;

import br.com.varesync.model.FormaReembolso;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R08 — Vendas por forma de pagamento (7.3 e F13.3): vendas finalizadas do período, com o valor de
 * {@code venda_pagamento} (no dinheiro, já sem o troco), como no fechamento de caixa (R11), e, à parte, os reembolsos
 * das devoluções do período por forma de reembolso. A soma das vendas é igual à coluna "Vendas" do R01 (a soma dos
 * pagamentos de cada venda é o total dela) e a dos reembolsos, à coluna "Devoluções".
 *
 * <p>Uma venda paga em duas formas conta nas duas; no total, conta uma vez só ({@link Linha#vendasNoPeriodo()}).
 */
public final class RelFormasPagamento implements Relatorio<RelFormasPagamento.Linha> {

    /** Prefixo das linhas de reembolso, depois das linhas de venda. */
    public static final String REEMBOLSO = "Reembolso: ";

    /**
     * Uma forma de pagamento (vendas e valor preenchidos) ou uma forma de reembolso (devoluções e reembolsos).
     *
     * @param vendasNoPeriodo vendas distintas do período, para o total (igual em todas as linhas)
     * @param devolucoesNoPeriodo devoluções distintas do período, para o total: a que tem abatimento e restante conta
     *                        em duas linhas, mas uma vez só no total; zero nas linhas de venda
     */
    public record Linha(String forma, Long vendas, BigDecimal valor, BigDecimal percentual, long vendasNoPeriodo,
                        Long devolucoes, BigDecimal reembolsos, long devolucoesNoPeriodo) {

        static Linha venda(String forma, long vendas, BigDecimal valor, long vendasNoPeriodo) {
            return new Linha(forma, vendas, valor, null, vendasNoPeriodo, null, null, 0);
        }

        static Linha reembolso(FormaReembolso forma, long devolucoes, BigDecimal valor, long vendasNoPeriodo,
                               long devolucoesNoPeriodo) {
            return new Linha(REEMBOLSO + forma.curto(), null, null, null, vendasNoPeriodo, devolucoes, valor,
                    devolucoesNoPeriodo);
        }

        public boolean ehReembolso() {
            return devolucoes != null;
        }
    }

    @Override
    public String codigo() {
        return "R08";
    }

    @Override
    public String nome() {
        return "Vendas por forma de pagamento";
    }

    @Override
    public String descricao() {
        return "Quanto entrou em cada forma de pagamento no período (dinheiro já sem o troco) e, à parte, quanto "
                + "saiu em reembolsos de devoluções. Uma venda paga em duas formas conta nas duas, mas só uma vez "
                + "no total.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT fp.descricao, count(DISTINCT v.id) AS vendas, sum(vp.valor) AS valor
                  FROM venda v
                  JOIN venda_pagamento vp ON vp.venda_id = v.id
                  JOIN forma_pagamento fp ON fp.id = vp.forma_pagamento_id
                 WHERE v.status = 'FINALIZADA'
                   AND v.data_hora >= ? AND v.data_hora < ?
                 GROUP BY fp.id, fp.descricao
                 ORDER BY sum(vp.valor) DESC, fp.descricao
                """;
        long noPeriodo = vendasNoPeriodo(con, filtros, parametros);
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(Linha.venda(rs.getString("descricao"), rs.getLong("vendas"),
                            rs.getBigDecimal("valor"), noPeriodo));
                }
            }
        }
        // Devolução com abatimento e restante (F14.2): a parte abatida na linha "Abatimento", o resto na forma escolhida.
        String reembolsos = """
                SELECT forma, count(*) AS devolucoes, sum(valor) AS valor,
                       (SELECT count(*) FROM devolucao WHERE data_hora >= ? AND data_hora < ?) AS no_periodo
                  FROM (SELECT forma_reembolso AS forma, valor_total - valor_abatido AS valor
                          FROM devolucao
                         WHERE forma_reembolso <> 'ABATIMENTO' AND data_hora >= ? AND data_hora < ?
                        UNION ALL
                        SELECT 'ABATIMENTO', valor_abatido
                          FROM devolucao
                         WHERE valor_abatido > 0 AND data_hora >= ? AND data_hora < ?) r
                 GROUP BY forma
                 ORDER BY sum(valor) DESC, forma
                """;
        List<Linha> devolucoes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(reembolsos)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.periodo(ps, 3, filtros, parametros);
            Consultas.periodo(ps, 5, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    devolucoes.add(Linha.reembolso(FormaReembolso.valueOf(rs.getString("forma")),
                            rs.getLong("devolucoes"), rs.getBigDecimal("valor"), noPeriodo,
                            rs.getLong("no_periodo")));
                }
            }
        }
        List<Linha> todas = new ArrayList<>(comPercentual(linhas));
        todas.addAll(devolucoes);
        return todas;
    }

    private static long vendasNoPeriodo(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = "SELECT count(*) FROM venda WHERE status = 'FINALIZADA' AND data_hora >= ? AND data_hora < ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** % de cada forma de pagamento no total das vendas (as linhas de reembolso ficam sem %). */
    static List<Linha> comPercentual(List<Linha> linhas) {
        BigDecimal total = Coluna.soma(linhas, Linha::valor);
        return linhas.stream()
                .map(l -> l.ehReembolso() ? l : new Linha(l.forma(), l.vendas(), l.valor(),
                        Dinheiro.percentualDe(l.valor(), total).orElse(null), l.vendasNoPeriodo(), null, null, 0))
                .toList();
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("Forma", Linha::forma).comLargura(1.8),
                Coluna.<Linha>inteiro("Nº de vendas", Linha::vendas)
                        .comTotal(linhas -> linhas.isEmpty() ? 0L : linhas.get(0).vendasNoPeriodo()),
                Coluna.moeda("Vendas", Linha::valor),
                Coluna.percentual("%", Linha::percentual).comTotal(linhas -> linhas.stream()
                        .anyMatch(l -> !l.ehReembolso()) ? Dinheiro.percentual(BigDecimal.valueOf(100)) : null),
                Coluna.<Linha>inteiro("Nº de devoluções", Linha::devolucoes)
                        .comTotal(linhas -> linhas.stream().mapToLong(Linha::devolucoesNoPeriodo).max().orElse(0)),
                Coluna.moeda("Reembolsos", Linha::reembolsos));
    }
}
