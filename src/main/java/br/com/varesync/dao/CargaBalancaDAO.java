package br.com.varesync.dao;

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
 * Carga de balança (F18.2): os pesáveis que vão para a balança, com o preço vigente na data (a mesma regra do
 * {@code PromocaoDAO.vigente} e das etiquetas: promoção ativa cujo período contém a data, a de menor preço) e o
 * último item carregado de cada um; e o registro de cada carga ({@code carga_balanca} e {@code carga_balanca_item}).
 */
public class CargaBalancaDAO {

    /** Pesável ativo vendido em KG, com PLU e preço vigente maior que zero, como está agora e na última carga. */
    public record Linha(long produtoId, String nome, int plu, BigDecimal preco, boolean emPromocao,
                        Integer validadeDias, Integer ultimoPlu, BigDecimal ultimoPreco, String ultimaDescricao,
                        Integer ultimaValidade, OffsetDateTime ultimaCargaEm) {
    }

    /** O que foi gravado para um produto na carga. */
    public record Item(long produtoId, int plu, BigDecimal preco, String descricao, Integer validadeDias) {
    }

    public List<Linha> pesaveis(Connection con, LocalDate data) throws SQLException {
        String sql = """
                SELECT p.id, p.nome, p.plu, p.preco_venda, p.validade_balanca_dias,
                       pr.preco_promocional,
                       uc.plu AS ultimo_plu, uc.preco AS ultimo_preco, uc.descricao AS ultima_descricao,
                       uc.validade_dias AS ultima_validade, uc.criado_em AS ultima_em
                  FROM produto p
                  LEFT JOIN LATERAL (SELECT preco_promocional
                                       FROM promocao
                                      WHERE produto_id = p.id AND ativa AND data_inicio <= ? AND data_fim >= ?
                                      ORDER BY preco_promocional, id DESC
                                      LIMIT 1) pr ON TRUE
                  LEFT JOIN LATERAL (SELECT i.plu, i.preco, i.descricao, i.validade_dias, c.criado_em
                                       FROM carga_balanca_item i
                                       JOIN carga_balanca c ON c.id = i.carga_id
                                      WHERE i.produto_id = p.id
                                      ORDER BY i.carga_id DESC
                                      LIMIT 1) uc ON TRUE
                 WHERE p.ativo AND p.unidade = 'KG' AND p.plu IS NOT NULL
                   AND coalesce(pr.preco_promocional, p.preco_venda) > 0
                 ORDER BY p.plu
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, data);
            ps.setObject(2, data);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal promocional = rs.getBigDecimal("preco_promocional");
                    linhas.add(new Linha(rs.getLong("id"), rs.getString("nome"), rs.getInt("plu"),
                            promocional != null ? promocional : rs.getBigDecimal("preco_venda"), promocional != null,
                            inteiro(rs, "validade_balanca_dias"), inteiro(rs, "ultimo_plu"),
                            rs.getBigDecimal("ultimo_preco"), rs.getString("ultima_descricao"),
                            inteiro(rs, "ultima_validade"), rs.getObject("ultima_em", OffsetDateTime.class)));
                }
            }
        }
        return linhas;
    }

    /**
     * Registra a carga e os itens.
     *
     * @param arquivo nulo = carga registrada sem arquivo (gerador de dados)
     * @return id da carga
     */
    public long registrar(Connection con, String arquivo, List<Item> itens, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        long id;
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO carga_balanca (arquivo, itens, usuario_id, criado_em)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)) {
            ps.setString(1, arquivo);
            ps.setInt(2, itens.size());
            ps.setLong(3, usuarioId);
            ps.setObject(4, agora);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO carga_balanca_item (carga_id, produto_id, plu, preco, descricao, validade_dias)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (Item item : itens) {
                ps.setLong(1, id);
                ps.setLong(2, item.produtoId());
                ps.setInt(3, item.plu());
                ps.setBigDecimal(4, item.preco());
                ps.setString(5, item.descricao());
                if (item.validadeDias() == null) {
                    ps.setNull(6, Types.SMALLINT);
                } else {
                    ps.setInt(6, item.validadeDias());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return id;
    }

    /** Quando foi a última carga (nula se nenhuma). */
    public OffsetDateTime ultimaCarga(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT max(criado_em) FROM carga_balanca");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class);
        }
    }

    private static Integer inteiro(ResultSet rs, String coluna) throws SQLException {
        int valor = rs.getInt(coluna);
        return rs.wasNull() ? null : valor;
    }
}
