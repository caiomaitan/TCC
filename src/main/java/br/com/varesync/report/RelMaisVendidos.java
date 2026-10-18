package br.com.varesync.report;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * R02 — Produtos mais vendidos (7.3): ranking por quantidade, receita ou lucro, com o % de cada produto na receita
 * do período (de todos os produtos do filtro, não só dos que entraram no top N).
 */
public final class RelMaisVendidos implements Relatorio<RelMaisVendidos.Linha> {

    public record Linha(int posicao, long produtoId, String produto, String unidade, boolean fracionada,
                        BigDecimal quantidade, BigDecimal receita, BigDecimal lucro, BigDecimal percentualReceita) {

        public BigDecimal margem() {
            return Dinheiro.percentualDe(lucro, receita).orElse(null);
        }
    }

    @Override
    public String codigo() {
        return "R02";
    }

    @Override
    public String nome() {
        return "Produtos mais vendidos";
    }

    @Override
    public String descricao() {
        return "Ranking dos produtos por quantidade, receita ou lucro no período, com o % de cada um na receita.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.CATEGORIA, TipoFiltro.TOP_N, TipoFiltro.CRITERIO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT r.produto_id, r.produto_nome, r.unidade, um.fracionada, sum(r.quantidade) AS quantidade,
                       sum(r.receita) AS receita, sum(r.lucro_bruto) AS lucro
                  FROM vw_venda_item_rentabilidade r
                  JOIN unidade_medida um ON um.sigla = r.unidade
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                   AND (CAST(? AS BIGINT) IS NULL OR r.categoria_id = ?)
                 GROUP BY r.produto_id, r.produto_nome, r.unidade, um.fracionada
                """;
        List<Linha> produtos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.categoriaId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    produtos.add(new Linha(0, rs.getLong("produto_id"), rs.getString("produto_nome"),
                            rs.getString("unidade"), rs.getBoolean("fracionada"), rs.getBigDecimal("quantidade"),
                            rs.getBigDecimal("receita"), rs.getBigDecimal("lucro"), null));
                }
            }
        }
        return ranquear(produtos, filtros.criterioOuPadrao(), filtros.topN());
    }

    /** Ordena pelo critério (decrescente; empate pelo nome), numera e calcula o % da receita total. */
    static List<Linha> ranquear(List<Linha> produtos, Criterio criterio, Integer topN) {
        Function<Linha, BigDecimal> chave = switch (criterio) {
            case QUANTIDADE -> Linha::quantidade;
            case RECEITA -> Linha::receita;
            case LUCRO -> Linha::lucro;
        };
        BigDecimal receitaTotal = Coluna.soma(produtos, Linha::receita);
        List<Linha> ordenados = produtos.stream()
                .sorted(Comparator.comparing(chave, Comparator.reverseOrder())
                        .thenComparing(Linha::produto, String.CASE_INSENSITIVE_ORDER))
                .limit(topN == null ? Long.MAX_VALUE : topN)
                .toList();
        List<Linha> ranking = new ArrayList<>();
        for (int i = 0; i < ordenados.size(); i++) {
            Linha l = ordenados.get(i);
            ranking.add(new Linha(i + 1, l.produtoId(), l.produto(), l.unidade(), l.fracionada(), l.quantidade(),
                    l.receita(), l.lucro(), Dinheiro.percentualDe(l.receita(), receitaTotal).orElse(null)));
        }
        return ranking;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>de("Posição", TipoColuna.INTEIRO, Linha::posicao).comLargura(0.8),
                Coluna.<Linha>de("Código", TipoColuna.INTEIRO, Linha::produtoId).comLargura(0.8),
                Coluna.texto("Produto", Linha::produto),
                Coluna.texto("Un.", Linha::unidade).comLargura(0.6),
                Coluna.quantidade("Quantidade", Linha::quantidade, Linha::fracionada),
                Coluna.moeda("Receita", Linha::receita),
                Coluna.moeda("Lucro", Linha::lucro),
                Coluna.percentual("Margem %", Linha::margem).comTotal(linhas -> Dinheiro.percentualDe(
                        Coluna.soma(linhas, Linha::lucro), Coluna.soma(linhas, Linha::receita)).orElse(null)),
                Coluna.percentual("% da receita", Linha::percentualReceita)
                        .comTotal(linhas -> Coluna.soma(linhas, Linha::percentualReceita)));
    }
}
