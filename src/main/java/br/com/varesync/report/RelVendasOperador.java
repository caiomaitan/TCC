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
 * R09 — Vendas por operador (7.3 e F13.3): nº de vendas, vendas, devoluções, receita líquida, ticket médio e descontos
 * (de {@code vw_resultado_item}: vendas finalizadas e, na data delas, as devoluções das vendas do operador) e os
 * cancelamentos do operador no período: vendas canceladas e itens cancelados durante a venda. O operador é quem fez a
 * venda ({@code venda.usuario_id}), também nas devoluções.
 */
public final class RelVendasOperador implements Relatorio<RelVendasOperador.Linha> {

    /** @param devolvido valor das devoluções das vendas do operador (positivo) */
    public record Linha(String operador, long vendas, BigDecimal vendido, BigDecimal devolvido, BigDecimal descontos,
                        long vendasCanceladas, long itensCancelados) {

        /** Receita líquida = vendas − devoluções. */
        public BigDecimal receita() {
            return vendido.subtract(devolvido);
        }

        /** Só sobre as vendas, como no R01. */
        public BigDecimal ticketMedio() {
            return Dinheiro.dividir(vendido, BigDecimal.valueOf(vendas), Dinheiro.ESCALA_DINHEIRO).orElse(null);
        }
    }

    @Override
    public String codigo() {
        return "R09";
    }

    @Override
    public String nome() {
        return "Vendas por operador";
    }

    @Override
    public String descricao() {
        return "Por operador: vendas, devoluções das vendas dele, receita líquida, ticket médio, descontos "
                + "concedidos, vendas canceladas e itens cancelados durante a venda.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                WITH vendido AS (
                    SELECT r.usuario_id, count(DISTINCT r.venda_id) FILTER (WHERE r.tipo = 'VENDA') AS vendas,
                           coalesce(sum(r.receita) FILTER (WHERE r.tipo = 'VENDA'), 0) AS vendido,
                           coalesce(-sum(r.receita) FILTER (WHERE r.tipo = 'DEVOLUCAO'), 0) AS devolvido,
                           sum(r.desconto_total) AS descontos
                      FROM vw_resultado_item r
                     WHERE r.data_hora >= ? AND r.data_hora < ?
                     GROUP BY r.usuario_id),
                cancelado AS (
                    SELECT v.usuario_id,
                           count(DISTINCT v.id) FILTER (WHERE v.status = 'CANCELADA') AS vendas_canceladas,
                           count(vi.id) FILTER (WHERE vi.cancelado) AS itens_cancelados
                      FROM venda v
                      LEFT JOIN venda_item vi ON vi.venda_id = v.id AND vi.cancelado
                     WHERE v.data_hora >= ? AND v.data_hora < ?
                     GROUP BY v.usuario_id)
                SELECT u.nome, coalesce(vd.vendas, 0) AS vendas, coalesce(vd.vendido, 0) AS vendido,
                       coalesce(vd.devolvido, 0) AS devolvido, coalesce(vd.descontos, 0) AS descontos, coalesce(c.vendas_canceladas, 0) AS vendas_canceladas,
                       coalesce(c.itens_cancelados, 0) AS itens_cancelados
                  FROM usuario u
                  LEFT JOIN vendido vd  ON vd.usuario_id = u.id
                  LEFT JOIN cancelado c ON c.usuario_id = u.id
                 WHERE vd.usuario_id IS NOT NULL
                    OR coalesce(c.vendas_canceladas, 0) + coalesce(c.itens_cancelados, 0) > 0
                 ORDER BY coalesce(vd.vendido - vd.devolvido, 0) DESC, u.nome
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.periodo(ps, 3, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getString("nome"), rs.getLong("vendas"), rs.getBigDecimal("vendido"),
                            rs.getBigDecimal("devolvido"), rs.getBigDecimal("descontos"),
                            rs.getLong("vendas_canceladas"), rs.getLong("itens_cancelados")));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("Operador", Linha::operador).comLargura(1.4),
                Coluna.<Linha>inteiro("Nº vendas", Linha::vendas).comLargura(1.1),
                Coluna.moeda("Vendas", Linha::vendido),
                Coluna.moeda("Devoluções", Linha::devolvido),
                Coluna.moeda("Receita líquida", Linha::receita),
                Coluna.<Linha>de("Ticket médio", TipoColuna.MOEDA, Linha::ticketMedio).comTotal(linhas ->
                        Dinheiro.dividir(Coluna.soma(linhas, Linha::vendido),
                                BigDecimal.valueOf(linhas.stream().mapToLong(Linha::vendas).sum()),
                                Dinheiro.ESCALA_DINHEIRO).orElse(null)),
                Coluna.moeda("Descontos", Linha::descontos),
                Coluna.inteiro("Vendas canceladas", Linha::vendasCanceladas).comLargura(1.7),
                Coluna.inteiro("Itens cancelados", Linha::itensCancelados).comLargura(1.5));
    }
}
