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
 * R05 — Rentabilidade por produto (7.3), de {@code vw_venda_item_rentabilidade}: receita, CMV (custo gravado na
 * venda), lucro, margem (lucro ÷ receita), markup (lucro ÷ CMV) e a situação: negativa (lucro &lt; 0), baixa
 * (0 ≤ margem &lt; {@code relatorio.margem_minima_pct}) ou ok. Do maior para o menor lucro.
 */
public final class RelRentabilidadeProduto implements Relatorio<RelRentabilidadeProduto.Linha> {

    public static final String NEGATIVA = "Negativa";
    public static final String BAIXA = "Baixa";
    public static final String OK = "OK";

    public record Linha(long produtoId, String produto, String categoria, String unidade, boolean fracionada,
                        BigDecimal quantidade, BigDecimal receita, BigDecimal cmv, BigDecimal lucro,
                        String situacao) {

        public BigDecimal margem() {
            return Dinheiro.percentualDe(lucro, receita).orElse(null);
        }

        public BigDecimal markup() {
            return Dinheiro.percentualDe(lucro, cmv).orElse(null);
        }
    }

    @Override
    public String codigo() {
        return "R05";
    }

    @Override
    public String nome() {
        return "Rentabilidade por produto";
    }

    @Override
    public String descricao() {
        return "Receita, CMV, lucro, margem e markup de cada produto vendido no período, com a situação: negativa, "
                + "baixa (abaixo da margem mínima) ou ok.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.CATEGORIA);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT r.produto_id, r.produto_nome, r.categoria_nome, r.unidade, um.fracionada,
                       sum(r.quantidade) AS quantidade, sum(r.receita) AS receita, sum(r.cmv) AS cmv,
                       sum(r.lucro_bruto) AS lucro
                  FROM vw_venda_item_rentabilidade r
                  JOIN unidade_medida um ON um.sigla = r.unidade
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                   AND (CAST(? AS BIGINT) IS NULL OR r.categoria_id = ?)
                 GROUP BY r.produto_id, r.produto_nome, r.categoria_nome, r.unidade, um.fracionada
                 ORDER BY sum(r.lucro_bruto) DESC, r.produto_nome
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.categoriaId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal receita = rs.getBigDecimal("receita");
                    BigDecimal lucro = rs.getBigDecimal("lucro");
                    linhas.add(new Linha(rs.getLong("produto_id"), rs.getString("produto_nome"),
                            rs.getString("categoria_nome"), rs.getString("unidade"), rs.getBoolean("fracionada"),
                            rs.getBigDecimal("quantidade"), receita, rs.getBigDecimal("cmv"), lucro,
                            situacao(lucro, receita, parametros.margemMinimaPct())));
                }
            }
        }
        return linhas;
    }

    /** Negativa: lucro &lt; 0; baixa: 0 ≤ margem &lt; mínima; senão ok (7.1). Margem comparada sem arredondar. */
    static String situacao(BigDecimal lucro, BigDecimal receita, BigDecimal margemMinimaPct) {
        if (lucro.signum() < 0) {
            return NEGATIVA;
        }
        if (receita.signum() > 0
                && lucro.multiply(BigDecimal.valueOf(100)).compareTo(margemMinimaPct.multiply(receita)) < 0) {
            return BAIXA;
        }
        return OK;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>codigo("Código", Linha::produtoId).comLargura(0.8),
                Coluna.texto("Produto", Linha::produto),
                Coluna.<Linha>texto("Categoria", l -> l.categoria() == null ? "" : l.categoria()).comLargura(1.6),
                Coluna.quantidade("Quantidade", Linha::quantidade, Linha::fracionada),
                Coluna.moeda("Receita", Linha::receita),
                Coluna.moeda("CMV", Linha::cmv),
                Coluna.moeda("Lucro", Linha::lucro),
                Coluna.percentual("Margem %", Linha::margem).comTotal(linhas -> Dinheiro.percentualDe(
                        Coluna.soma(linhas, Linha::lucro), Coluna.soma(linhas, Linha::receita)).orElse(null)),
                Coluna.percentual("Markup %", Linha::markup).comTotal(linhas -> Dinheiro.percentualDe(
                        Coluna.soma(linhas, Linha::lucro), Coluna.soma(linhas, Linha::cmv)).orElse(null)),
                Coluna.<Linha>texto("Situação", Linha::situacao).comLargura(1.0));
    }
}
