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
 * R06 — Rentabilidade por categoria (7.3), de {@code vw_resultado_item}: receita (vendas − devoluções), CMV, lucro, margem e o %
 * de cada categoria no lucro total do período. Produto sem categoria entra em "Sem categoria". Do maior para o
 * menor lucro.
 */
public final class RelRentabilidadeCategoria implements Relatorio<RelRentabilidadeCategoria.Linha> {

    static final String SEM_CATEGORIA = "Sem categoria";

    /** @param percentualLucro % no lucro total; nulo se o lucro total não for positivo */
    public record Linha(String categoria, BigDecimal receita, BigDecimal cmv, BigDecimal lucro,
                        BigDecimal percentualLucro) {

        public BigDecimal margem() {
            return Dinheiro.percentualDe(lucro, receita).orElse(null);
        }
    }

    @Override
    public String codigo() {
        return "R06";
    }

    @Override
    public String nome() {
        return "Rentabilidade por categoria";
    }

    @Override
    public String descricao() {
        return "Receita, CMV, lucro e margem de cada categoria no período, e quanto ela representa do lucro total.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT r.categoria_nome, sum(r.receita) AS receita, sum(r.cmv) AS cmv, sum(r.lucro_bruto) AS lucro
                  FROM vw_resultado_item r
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY r.categoria_id, r.categoria_nome
                 ORDER BY sum(r.lucro_bruto) DESC, r.categoria_nome NULLS LAST
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String categoria = rs.getString("categoria_nome");
                    linhas.add(new Linha(categoria == null ? SEM_CATEGORIA : categoria, rs.getBigDecimal("receita"),
                            rs.getBigDecimal("cmv"), rs.getBigDecimal("lucro"), null));
                }
            }
        }
        return comPercentual(linhas);
    }

    /** % de cada categoria no lucro total; com lucro total zero ou negativo o % não faz sentido ("—"). */
    static List<Linha> comPercentual(List<Linha> linhas) {
        BigDecimal total = Coluna.soma(linhas, Linha::lucro);
        return linhas.stream()
                .map(l -> new Linha(l.categoria(), l.receita(), l.cmv(), l.lucro(), total.signum() > 0
                        ? Dinheiro.percentualDe(l.lucro(), total).orElse(null) : null))
                .toList();
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.texto("Categoria", Linha::categoria),
                Coluna.moeda("Receita", Linha::receita),
                Coluna.moeda("CMV", Linha::cmv),
                Coluna.moeda("Lucro", Linha::lucro),
                Coluna.percentual("Margem %", Linha::margem).comTotal(linhas -> Dinheiro.percentualDe(
                        Coluna.soma(linhas, Linha::lucro), Coluna.soma(linhas, Linha::receita)).orElse(null)),
                Coluna.percentual("% do lucro", Linha::percentualLucro).comTotal(linhas ->
                        Coluna.soma(linhas, Linha::lucro).signum() > 0 ? Dinheiro.percentual(BigDecimal.valueOf(100))
                                : null));
    }
}
