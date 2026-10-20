package br.com.varesync.dao;

import br.com.varesync.model.ProdutoPainel;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Consultas do painel (7.5). Vendas e lucro de {@code vw_venda_item_rentabilidade}, como os relatórios, para que o
 * painel bata com o R01 e o R05.
 */
public class PainelDAO {

    /** Receita e lucro de uma venda finalizada. */
    public record VendaPainel(OffsetDateTime dataHora, BigDecimal receita, BigDecimal lucro) {
    }

    /** Vendas com {@code data_hora >= inicio AND data_hora < fim}, uma linha por venda. */
    public List<VendaPainel> vendas(Connection con, OffsetDateTime inicio, OffsetDateTime fim) throws SQLException {
        String sql = """
                SELECT r.venda_id, r.data_hora, sum(r.receita) AS receita, sum(r.lucro_bruto) AS lucro
                  FROM vw_venda_item_rentabilidade r
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY r.venda_id, r.data_hora
                 ORDER BY r.data_hora
                """;
        List<VendaPainel> vendas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, inicio);
            ps.setObject(2, fim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    vendas.add(new VendaPainel(rs.getObject("data_hora", OffsetDateTime.class),
                            rs.getBigDecimal("receita"), rs.getBigDecimal("lucro")));
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
                  FROM vw_venda_item_rentabilidade r
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

    private static int contar(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
