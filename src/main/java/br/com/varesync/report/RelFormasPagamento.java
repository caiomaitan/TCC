package br.com.varesync.report;

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
 * R08 — Vendas por forma de pagamento (7.3): vendas finalizadas do período, com o valor de {@code venda_pagamento}
 * (no dinheiro, já sem o troco), como no fechamento de caixa (R11). A soma é igual à receita do R01: a soma dos
 * pagamentos de cada venda é o total dela.
 *
 * <p>Uma venda paga em duas formas conta nas duas; no total, conta uma vez só ({@link Linha#vendasNoPeriodo()}).
 */
public final class RelFormasPagamento implements Relatorio<RelFormasPagamento.Linha> {

    /** @param vendasNoPeriodo vendas distintas do período, para o total (igual em todas as linhas) */
    public record Linha(String forma, long vendas, BigDecimal valor, BigDecimal percentual, long vendasNoPeriodo) {
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
        return "Quanto entrou em cada forma de pagamento no período (dinheiro já sem o troco). Uma venda paga em "
                + "duas formas conta nas duas, mas só uma vez no total.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT fp.descricao, count(DISTINCT v.id) AS vendas, sum(vp.valor) AS valor,
                       (SELECT count(*) FROM venda v2
                         WHERE v2.status = 'FINALIZADA' AND v2.data_hora >= ? AND v2.data_hora < ?) AS no_periodo
                  FROM venda v
                  JOIN venda_pagamento vp ON vp.venda_id = v.id
                  JOIN forma_pagamento fp ON fp.id = vp.forma_pagamento_id
                 WHERE v.status = 'FINALIZADA'
                   AND v.data_hora >= ? AND v.data_hora < ?
                 GROUP BY fp.id, fp.descricao
                 ORDER BY sum(vp.valor) DESC, fp.descricao
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.periodo(ps, 3, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getString("descricao"), rs.getLong("vendas"), rs.getBigDecimal("valor"),
                            null, rs.getLong("no_periodo")));
                }
            }
        }
        return comPercentual(linhas);
    }

    static List<Linha> comPercentual(List<Linha> linhas) {
        BigDecimal total = Coluna.soma(linhas, Linha::valor);
        return linhas.stream()
                .map(l -> new Linha(l.forma(), l.vendas(), l.valor(),
                        Dinheiro.percentualDe(l.valor(), total).orElse(null), l.vendasNoPeriodo()))
                .toList();
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.texto("Forma de pagamento", Linha::forma),
                Coluna.<Linha>inteiro("Nº de vendas", Linha::vendas)
                        .comTotal(linhas -> linhas.isEmpty() ? 0L : linhas.get(0).vendasNoPeriodo()),
                Coluna.moeda("Valor", Linha::valor),
                Coluna.percentual("%", Linha::percentual).comTotal(linhas -> linhas.isEmpty() ? null
                        : Dinheiro.percentual(BigDecimal.valueOf(100))));
    }
}
