package br.com.varesync.report;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R12 — Produtos sem giro (7.1, 7.3): produtos ativos com saldo &gt; 0 e nenhuma venda há N dias ou mais (a última
 * venda foi em hoje − N ou antes, ou nunca houve). Saldo e valor parado (saldo × custo médio) de
 * {@code vw_estoque_atual}; última venda de {@code vw_venda_item_rentabilidade}. Do maior para o menor valor parado.
 */
public final class RelSemGiro implements Relatorio<RelSemGiro.Linha> {

    /** Limite do filtro de dias. */
    public static final int DIAS_MAXIMO = 3650;

    /**
     * @param ultimaVenda nulo se o produto nunca foi vendido
     * @param diasSemVenda nulo se nunca foi vendido
     */
    public record Linha(long produtoId, String produto, String categoria, String unidade, boolean fracionada,
                        BigDecimal saldo, BigDecimal valorParado, OffsetDateTime ultimaVenda, Long diasSemVenda) {
    }

    @Override
    public String codigo() {
        return "R12";
    }

    @Override
    public String nome() {
        return "Produtos sem giro";
    }

    @Override
    public String descricao() {
        return "Produtos com estoque e sem venda há N dias ou mais (ou nunca vendidos), com o valor parado a custo.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.DIAS, TipoFiltro.CATEGORIA);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT e.produto_id, e.nome, e.categoria_nome, e.unidade, um.fracionada, e.saldo,
                       e.valor_em_estoque, uv.ultima
                  FROM vw_estoque_atual e
                  JOIN unidade_medida um ON um.sigla = e.unidade
                  LEFT JOIN (SELECT r.produto_id, max(r.data_hora) AS ultima
                               FROM vw_venda_item_rentabilidade r
                              GROUP BY r.produto_id) uv ON uv.produto_id = e.produto_id
                 WHERE e.ativo
                   AND e.saldo > 0
                   AND (uv.ultima IS NULL OR uv.ultima < ?)
                   AND (CAST(? AS BIGINT) IS NULL OR e.categoria_id = ?)
                 ORDER BY e.valor_em_estoque DESC, e.nome
                """;
        if (filtros.dias() == null) {
            throw new IllegalArgumentException("Dias sem giro não informados");
        }
        LocalDate hoje = parametros.hoje();
        // Sem venda há N dias ou mais = nenhuma venda a partir de (hoje − N + 1) 00:00.
        OffsetDateTime limite = hoje.minusDays(filtros.dias() - 1L).atStartOfDay(parametros.zona()).toOffsetDateTime();
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, limite);
            Consultas.opcional(ps, 2, filtros.categoriaId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OffsetDateTime ultima = rs.getObject("ultima", OffsetDateTime.class);
                    Long dias = ultima == null ? null : ChronoUnit.DAYS.between(
                            ultima.atZoneSameInstant(parametros.zona()).toLocalDate(), hoje);
                    linhas.add(new Linha(rs.getLong("produto_id"), rs.getString("nome"),
                            rs.getString("categoria_nome"), rs.getString("unidade"), rs.getBoolean("fracionada"),
                            rs.getBigDecimal("saldo"), rs.getBigDecimal("valor_em_estoque"), ultima, dias));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>codigo("Código", Linha::produtoId).comLargura(0.8),
                Coluna.texto("Produto", Linha::produto),
                Coluna.<Linha>texto("Categoria", l -> l.categoria() == null ? "" : l.categoria()).comLargura(1.6),
                Coluna.texto("Un.", Linha::unidade).comLargura(0.6),
                Coluna.quantidade("Saldo", Linha::saldo, Linha::fracionada),
                Coluna.moeda("Valor parado", Linha::valorParado),
                Coluna.<Linha>de("Última venda", TipoColuna.DATA_HORA, Linha::ultimaVenda),
                Coluna.<Linha>de("Dias sem venda", TipoColuna.INTEIRO, Linha::diasSemVenda).comLargura(1.1));
    }
}
