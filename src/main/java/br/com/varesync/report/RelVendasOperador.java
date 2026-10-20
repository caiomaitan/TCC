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
 * R09 — Vendas por operador (7.3): nº de vendas, receita, ticket médio e descontos (de
 * {@code vw_venda_item_rentabilidade}, só vendas finalizadas) e os cancelamentos do operador no período: vendas
 * canceladas e itens cancelados durante a venda. O operador é quem fez a venda ({@code venda.usuario_id}).
 */
public final class RelVendasOperador implements Relatorio<RelVendasOperador.Linha> {

    public record Linha(String operador, long vendas, BigDecimal receita, BigDecimal descontos,
                        long vendasCanceladas, long itensCancelados) {

        public BigDecimal ticketMedio() {
            return Dinheiro.dividir(receita, BigDecimal.valueOf(vendas), Dinheiro.ESCALA_DINHEIRO).orElse(null);
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
        return "Por operador: vendas, receita, ticket médio, descontos concedidos, vendas canceladas e itens "
                + "cancelados durante a venda.";
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
                    SELECT r.usuario_id, count(DISTINCT r.venda_id) AS vendas, sum(r.receita) AS receita,
                           sum(r.desconto_total) AS descontos
                      FROM vw_venda_item_rentabilidade r
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
                SELECT u.nome, coalesce(vd.vendas, 0) AS vendas, coalesce(vd.receita, 0) AS receita,
                       coalesce(vd.descontos, 0) AS descontos, coalesce(c.vendas_canceladas, 0) AS vendas_canceladas,
                       coalesce(c.itens_cancelados, 0) AS itens_cancelados
                  FROM usuario u
                  LEFT JOIN vendido vd  ON vd.usuario_id = u.id
                  LEFT JOIN cancelado c ON c.usuario_id = u.id
                 WHERE vd.usuario_id IS NOT NULL
                    OR coalesce(c.vendas_canceladas, 0) + coalesce(c.itens_cancelados, 0) > 0
                 ORDER BY coalesce(vd.receita, 0) DESC, u.nome
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            Consultas.periodo(ps, 3, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getString("nome"), rs.getLong("vendas"), rs.getBigDecimal("receita"),
                            rs.getBigDecimal("descontos"), rs.getLong("vendas_canceladas"),
                            rs.getLong("itens_cancelados")));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.texto("Operador", Linha::operador),
                Coluna.inteiro("Vendas", Linha::vendas),
                Coluna.moeda("Receita", Linha::receita),
                Coluna.<Linha>de("Ticket médio", TipoColuna.MOEDA, Linha::ticketMedio).comTotal(linhas ->
                        Dinheiro.dividir(Coluna.soma(linhas, Linha::receita),
                                BigDecimal.valueOf(linhas.stream().mapToLong(Linha::vendas).sum()),
                                Dinheiro.ESCALA_DINHEIRO).orElse(null)),
                Coluna.moeda("Descontos", Linha::descontos),
                Coluna.inteiro("Vendas canceladas", Linha::vendasCanceladas).comLargura(1.2),
                Coluna.inteiro("Itens cancelados", Linha::itensCancelados).comLargura(1.2));
    }
}
