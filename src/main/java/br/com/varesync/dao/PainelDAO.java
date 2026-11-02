package br.com.varesync.dao;

import br.com.varesync.model.AlertasPainel;
import br.com.varesync.model.ProdutoPainel;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Consultas do painel (7.5). Vendas, devoluções e lucro de {@code vw_resultado_item}, como os relatórios, para que o
 * painel bata com o R01 e o R05 (F13.3).
 */
public class PainelDAO {

    /**
     * Receita e lucro de uma venda finalizada ou de uma devolução.
     *
     * @param venda falso na devolução (receita e lucro negativos, na data da devolução)
     */
    public record VendaPainel(OffsetDateTime dataHora, BigDecimal receita, BigDecimal lucro, boolean venda) {

        public VendaPainel(OffsetDateTime dataHora, BigDecimal receita, BigDecimal lucro) {
            this(dataHora, receita, lucro, true);
        }
    }

    /** Vendas e devoluções com {@code data_hora >= inicio AND data_hora < fim}, uma linha por venda ou devolução. */
    public List<VendaPainel> vendas(Connection con, OffsetDateTime inicio, OffsetDateTime fim) throws SQLException {
        String sql = """
                SELECT r.tipo, r.venda_id, r.devolucao_id, r.data_hora, sum(r.receita) AS receita,
                       sum(r.lucro_bruto) AS lucro
                  FROM vw_resultado_item r
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY r.tipo, r.venda_id, r.devolucao_id, r.data_hora
                 ORDER BY r.data_hora
                """;
        List<VendaPainel> vendas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, inicio);
            ps.setObject(2, fim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    vendas.add(new VendaPainel(rs.getObject("data_hora", OffsetDateTime.class),
                            rs.getBigDecimal("receita"), rs.getBigDecimal("lucro"),
                            "VENDA".equals(rs.getString("tipo"))));
                }
            }
        }
        return vendas;
    }

    /** Os produtos de maior lucro no período (empate pelo nome). */
    public List<ProdutoPainel> maisLucrativos(Connection con, OffsetDateTime inicio, OffsetDateTime fim, int limite)
            throws SQLException {
        String sql = """
                SELECT r.produto_id, r.produto_nome, sum(r.receita) AS receita, sum(r.lucro_bruto) AS lucro
                  FROM vw_resultado_item r
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY r.produto_id, r.produto_nome
                 ORDER BY sum(r.lucro_bruto) DESC, r.produto_nome
                 LIMIT ?
                """;
        List<ProdutoPainel> produtos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, inicio);
            ps.setObject(2, fim);
            ps.setInt(3, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    produtos.add(new ProdutoPainel(rs.getLong("produto_id"), rs.getString("produto_nome"),
                            rs.getBigDecimal("receita"), rs.getBigDecimal("lucro")));
                }
            }
        }
        return produtos;
    }

    /** Produtos ativos com preço de venda menor que o custo (cada venda dá prejuízo). */
    public int contarPrecoAbaixoDoCusto(Connection con) throws SQLException {
        return contar(con, "SELECT count(*) FROM produto WHERE ativo AND preco_venda < custo");
    }

    public int contarInventariosAbertos(Connection con) throws SQLException {
        return contar(con, "SELECT count(*) FROM inventario WHERE status = 'ABERTO'");
    }

    /** Contas a pagar em aberto com vencimento de {@code de} até {@code ate} (F15.6): quantas e quanto falta pagar. */
    public AlertasPainel.ContasAPagar contasAPagar(Connection con, LocalDate de, LocalDate ate) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(*), coalesce(sum(valor - valor_pago), 0) FROM titulo_pagar
                 WHERE status = 'ABERTO' AND (CAST(? AS DATE) IS NULL OR vencimento >= ?) AND vencimento <= ?
                """)) {
            ps.setObject(1, de, Types.DATE);
            ps.setObject(2, de, Types.DATE);
            ps.setObject(3, ate);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new AlertasPainel.ContasAPagar(rs.getInt(1), rs.getBigDecimal(2));
            }
        }
    }

    private static int contar(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
