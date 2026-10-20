package br.com.varesync.report;

import br.com.varesync.model.TipoMovimento;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R14 — Perdas e ajustes (7.3): movimentos de perda, ajuste de entrada, ajuste de saída e inventário no período,
 * agrupados por tipo, motivo e produto, com a quantidade (da unidade do produto) e o valor a custo do movimento
 * (quantidade × custo, 2 casas por movimento; entrada positiva, saída negativa).
 *
 * <p>O motivo é o texto antes de ": " (perda "Vencimento: lote 12" e ajuste "Contagem: prateleira" agrupam em
 * "Vencimento" e "Contagem", como gravados pelo Estoque); no inventário, "Inventário nº X".
 */
public final class RelPerdasAjustes implements Relatorio<RelPerdasAjustes.Linha> {

    static final List<TipoMovimento> TIPOS = List.of(TipoMovimento.PERDA, TipoMovimento.AJUSTE_SAIDA,
            TipoMovimento.AJUSTE_ENTRADA, TipoMovimento.INVENTARIO);

    public record Linha(TipoMovimento tipo, String motivo, long produtoId, String produto, String unidade,
                        boolean fracionada, long movimentos, BigDecimal quantidade, BigDecimal valor) {
    }

    /** Um movimento, antes do agrupamento. */
    record Movimento(TipoMovimento tipo, String motivo, long produtoId, String produto, String unidade,
                     boolean fracionada, BigDecimal quantidade, BigDecimal custo) {
    }

    @Override
    public String codigo() {
        return "R14";
    }

    @Override
    public String nome() {
        return "Perdas e ajustes";
    }

    @Override
    public String descricao() {
        return "Perdas, ajustes manuais e ajustes de inventário do período por motivo e produto, com o valor a "
                + "custo (saídas negativas).";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.TIPO_MOVIMENTO);
    }

    @Override
    public List<TipoMovimento> tiposMovimento() {
        return TIPOS;
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT m.tipo, m.motivo, ii.inventario_id, m.produto_id, p.nome, p.unidade, um.fracionada,
                       m.quantidade, m.custo_unitario
                  FROM movimento_estoque m
                  JOIN produto p               ON p.id = m.produto_id
                  JOIN unidade_medida um       ON um.sigla = p.unidade
                  LEFT JOIN inventario_item ii ON ii.id = m.inventario_item_id
                 WHERE m.criado_em >= ? AND m.criado_em < ?
                   AND m.tipo IN ('PERDA', 'AJUSTE_SAIDA', 'AJUSTE_ENTRADA', 'INVENTARIO')
                   AND (CAST(? AS VARCHAR) IS NULL OR m.tipo = ?)
                 ORDER BY m.criado_em, m.id
                """;
        if (filtros.tipo() != null && !TIPOS.contains(filtros.tipo())) {
            throw new IllegalArgumentException("Tipo fora do R14: " + filtros.tipo());
        }
        List<Movimento> movimentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.opcional(ps, 3, filtros.tipo() == null ? null : filtros.tipo().name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TipoMovimento tipo = TipoMovimento.valueOf(rs.getString("tipo"));
                    long inventario = rs.getLong("inventario_id");
                    String motivo = tipo == TipoMovimento.INVENTARIO ? "Inventário nº " + inventario
                            : motivoPrincipal(rs.getString("motivo"));
                    movimentos.add(new Movimento(tipo, motivo, rs.getLong("produto_id"), rs.getString("nome"),
                            rs.getString("unidade"), rs.getBoolean("fracionada"), rs.getBigDecimal("quantidade"),
                            rs.getBigDecimal("custo_unitario")));
                }
            }
        }
        return agrupar(movimentos);
    }

    /** "Vencimento: lote 12" → "Vencimento"; sem ": ", o texto todo. */
    static String motivoPrincipal(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return "(sem motivo)";
        }
        int separador = motivo.indexOf(": ");
        return (separador > 0 ? motivo.substring(0, separador) : motivo).strip();
    }

    /** Soma por tipo, motivo e produto; valor = Σ round(quantidade × custo, 2). Ordem: tipo, motivo, maior perda. */
    static List<Linha> agrupar(List<Movimento> movimentos) {
        Map<String, Linha> grupos = new LinkedHashMap<>();
        for (Movimento m : movimentos) {
            Linha nova = new Linha(m.tipo(), m.motivo(), m.produtoId(), m.produto(), m.unidade(), m.fracionada(), 1,
                    m.quantidade(), Dinheiro.multiplicar(m.quantidade(), m.custo()));
            grupos.merge(m.tipo() + "|" + m.motivo().toLowerCase() + "|" + m.produtoId(), nova,
                    (a, b) -> new Linha(a.tipo(), a.motivo(), a.produtoId(), a.produto(), a.unidade(),
                            a.fracionada(), a.movimentos() + 1, a.quantidade().add(b.quantidade()),
                            a.valor().add(b.valor())));
        }
        return grupos.values().stream()
                .sorted(Comparator.comparing((Linha l) -> TIPOS.indexOf(l.tipo()))
                        .thenComparing(Linha::motivo, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Linha::valor)
                        .thenComparing(Linha::produto, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("Tipo", l -> l.tipo().descricao()).comLargura(1.5),
                Coluna.<Linha>texto("Motivo", Linha::motivo).comLargura(2.0),
                Coluna.texto("Produto", Linha::produto),
                Coluna.inteiro("Movimentos", Linha::movimentos),
                Coluna.quantidade("Quantidade", Linha::quantidade, Linha::fracionada),
                Coluna.texto("Un.", Linha::unidade).comLargura(0.6),
                Coluna.moeda("Valor a custo", Linha::valor));
    }
}
