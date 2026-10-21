package br.com.varesync.report;

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
 * R03 — Estoque atual (7.3), de {@code vw_estoque_atual}. Valor em estoque = saldo × custo médio; no total, saldo
 * negativo conta como zero (7.1).
 */
public final class RelEstoqueAtual implements Relatorio<RelEstoqueAtual.Linha> {

    public record Linha(long produtoId, String produto, String categoria, String unidade, boolean fracionada,
                        boolean ativo, BigDecimal saldo, BigDecimal minimo, BigDecimal custo,
                        BigDecimal valorEmEstoque, boolean abaixoDoMinimo) {
    }

    @Override
    public String codigo() {
        return "R03";
    }

    @Override
    public String nome() {
        return "Estoque atual";
    }

    @Override
    public String descricao() {
        return "Saldo, mínimo, custo médio e valor em estoque de cada produto. No total, saldo negativo vale zero.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.CATEGORIA, TipoFiltro.SO_ABAIXO_MINIMO, TipoFiltro.INCLUIR_INATIVOS);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT e.produto_id, e.nome, e.categoria_nome, e.unidade, um.fracionada, e.ativo, e.saldo,
                       e.estoque_minimo, e.custo, e.valor_em_estoque, e.abaixo_do_minimo
                  FROM vw_estoque_atual e
                  JOIN unidade_medida um ON um.sigla = e.unidade
                 WHERE (CAST(? AS BIGINT) IS NULL OR e.categoria_id = ?)
                   AND (? OR e.ativo)
                   AND (NOT ? OR e.abaixo_do_minimo)
                 ORDER BY e.nome, e.produto_id
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.opcional(ps, 1, filtros.categoriaId());
            ps.setBoolean(3, filtros.incluirInativos());
            ps.setBoolean(4, filtros.soAbaixoDoMinimo());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getLong("produto_id"), rs.getString("nome"),
                            rs.getString("categoria_nome"), rs.getString("unidade"), rs.getBoolean("fracionada"),
                            rs.getBoolean("ativo"), rs.getBigDecimal("saldo"), rs.getBigDecimal("estoque_minimo"),
                            rs.getBigDecimal("custo"), rs.getBigDecimal("valor_em_estoque"),
                            rs.getBoolean("abaixo_do_minimo")));
                }
            }
        }
        return linhas;
    }

    /** Σ valor em estoque, com saldo negativo contando como zero (7.1). */
    static BigDecimal valorTotal(List<Linha> linhas) {
        return Coluna.soma(linhas, l -> l.valorEmEstoque().max(BigDecimal.ZERO));
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>codigo("Código", Linha::produtoId).comLargura(0.8),
                Coluna.texto("Produto", Linha::produto),
                Coluna.<Linha>texto("Categoria", l -> l.categoria() == null ? "" : l.categoria()).comLargura(1.6),
                Coluna.texto("Un.", Linha::unidade).comLargura(0.6),
                Coluna.quantidade("Saldo", Linha::saldo, Linha::fracionada),
                Coluna.quantidade("Mínimo", Linha::minimo, Linha::fracionada),
                Coluna.<Linha>de("Custo médio", TipoColuna.CUSTO, Linha::custo),
                Coluna.<Linha>de("Valor em estoque", TipoColuna.MOEDA, Linha::valorEmEstoque)
                        .comTotal(RelEstoqueAtual::valorTotal));
    }
}
