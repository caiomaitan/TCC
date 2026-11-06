package br.com.varesync.report;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R29 — Validade (F17.3): lotes com saldo positivo, de produtos ativos com o controle de validade ligado, que vencem
 * em até N dias (padrão {@code validade.alerta_dias}) e, se marcado, os vencidos. O "SEM LOTE" não entra, porque não
 * tem validade. Valor a custo = saldo × custo médio do produto, com 2 casas. Pela validade (os vencidos primeiro).
 */
public final class RelValidade implements Relatorio<RelValidade.Linha> {

    static final String SEM_CODIGO = "(sem código)";

    /**
     * @param diasParaVencer negativo = vencido
     */
    public record Linha(long produtoId, String produto, String categoria, String unidade, boolean fracionada,
                        String lote, LocalDate validade, long diasParaVencer, BigDecimal saldo, BigDecimal custoMedio,
                        BigDecimal valorACusto) {

        public boolean vencido() {
            return diasParaVencer < 0;
        }
    }

    @Override
    public String codigo() {
        return "R29";
    }

    @Override
    public String nome() {
        return "Validade";
    }

    @Override
    public String descricao() {
        return "Lotes com saldo que vencem nos próximos dias e, se marcado, os já vencidos, com o valor a custo. "
                + "Só produtos ativos com o controle de validade ligado; o \"SEM LOTE\" não entra.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.VALIDADE, TipoFiltro.CATEGORIA);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        if (filtros.validade().dias() == null) {
            throw new IllegalArgumentException("Dias para vencer não informados");
        }
        String sql = """
                SELECT p.id, p.nome, c.nome AS categoria, p.unidade, u.fracionada, l.codigo, l.validade, s.saldo,
                       p.custo, round(s.saldo * p.custo, 2) AS valor
                  FROM lote l
                  JOIN lote_saldo s     ON s.lote_id = l.id
                  JOIN produto p        ON p.id = l.produto_id
                  JOIN unidade_medida u ON u.sigla = p.unidade
                  LEFT JOIN categoria c ON c.id = p.categoria_id
                 WHERE p.ativo AND p.controla_validade
                   AND l.validade IS NOT NULL
                   AND s.saldo > 0
                   AND l.validade <= ?
                   AND (? OR l.validade >= ?)
                   AND (CAST(? AS BIGINT) IS NULL OR p.categoria_id = ?)
                 ORDER BY l.validade, p.nome_busca, l.codigo, l.id
                """;
        LocalDate hoje = parametros.hoje();
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, hoje.plusDays(filtros.validade().dias()));
            ps.setBoolean(2, filtros.validade().incluirVencidos());
            ps.setObject(3, hoje);
            Consultas.opcional(ps, 4, filtros.categoriaId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate validade = rs.getObject("validade", LocalDate.class);
                    String codigo = rs.getString("codigo");
                    linhas.add(new Linha(rs.getLong("id"), rs.getString("nome"), rs.getString("categoria"),
                            rs.getString("unidade"), rs.getBoolean("fracionada"),
                            codigo.isEmpty() ? SEM_CODIGO : codigo, validade, ChronoUnit.DAYS.between(hoje, validade), rs.getBigDecimal("saldo"),
                            rs.getBigDecimal("custo"), rs.getBigDecimal("valor")));
                }
            }
        }
        return linhas;
    }

    /** "3 lotes (1 vencido)". */
    static String totalLotes(List<Linha> linhas) {
        long vencidos = linhas.stream().filter(Linha::vencido).count();
        return (linhas.size() == 1 ? "1 lote" : linhas.size() + " lotes")
                + (vencidos == 0 ? "" : " (" + vencidos + (vencidos == 1 ? " vencido)" : " vencidos)"));
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>codigo("Código", Linha::produtoId).comLargura(0.8),
                Coluna.<Linha>texto("Produto", Linha::produto).comLargura(2.3),
                Coluna.<Linha>texto("Categoria", l -> l.categoria() == null ? "" : l.categoria()).comLargura(1.2),
                Coluna.<Linha>texto("Lote", Linha::lote).comLargura(2.5)
                        .comTotal(RelValidade::totalLotes),
                Coluna.<Linha>de("Validade", TipoColuna.DATA, Linha::validade),
                Coluna.<Linha>de("Dias para vencer", TipoColuna.INTEIRO, Linha::diasParaVencer).comLargura(1.9),
                Coluna.texto("Un.", Linha::unidade).comLargura(0.6),
                Coluna.<Linha>quantidade("Saldo", Linha::saldo, Linha::fracionada),
                Coluna.<Linha>de("Custo médio", TipoColuna.CUSTO, Linha::custoMedio),
                Coluna.moeda("Valor a custo", Linha::valorACusto));
    }
}
