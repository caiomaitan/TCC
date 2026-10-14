package br.com.varesync.dao;

import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Kardex ({@code movimento_estoque}). Só INSERT: o trigger preenche os saldos e atualiza {@code produto_estoque};
 * o banco recusa UPDATE e DELETE. Grava somente por {@code EstoqueService.movimentar}.
 */
public class MovimentoEstoqueDAO {

    /**
     * @param custoUnitario nulo = o trigger usa o custo atual do produto
     * @return id do movimento
     */
    public long inserir(Connection con, long produtoId, TipoMovimento tipo, BigDecimal quantidade,
                        BigDecimal custoUnitario, Long vendaItemId, Long inventarioItemId, Long entradaItemId,
                        String motivo, long usuarioId, OffsetDateTime criadoEm) throws SQLException {
        String sql = """
                INSERT INTO movimento_estoque (produto_id, tipo, quantidade, custo_unitario, venda_item_id,
                                               inventario_item_id, entrada_item_id, motivo, usuario_id, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setString(2, tipo.name());
            ps.setBigDecimal(3, quantidade);
            if (custoUnitario == null) {
                ps.setNull(4, Types.NUMERIC);
            } else {
                ps.setBigDecimal(4, custoUnitario);
            }
            definirLong(ps, 5, vendaItemId);
            definirLong(ps, 6, inventarioItemId);
            definirLong(ps, 7, entradaItemId);
            ps.setString(8, motivo);
            ps.setLong(9, usuarioId);
            ps.setObject(10, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Kardex de um produto no período {@code [inicio, fimExclusivo)}, do mais antigo para o mais recente,
     * com o número do documento de origem (venda, inventário ou entrada).
     */
    public List<MovimentoKardex> listar(Connection con, long produtoId, OffsetDateTime inicio,
                                        OffsetDateTime fimExclusivo) throws SQLException {
        String sql = """
                SELECT m.id, m.criado_em, m.tipo, m.quantidade, m.saldo_anterior, m.saldo_posterior,
                       m.custo_unitario, u.nome AS usuario_nome, vi.venda_id, ii.inventario_id, ei.entrada_id,
                       m.motivo
                  FROM movimento_estoque m
                  JOIN usuario u                       ON u.id = m.usuario_id
                  LEFT JOIN venda_item vi              ON vi.id = m.venda_item_id
                  LEFT JOIN inventario_item ii         ON ii.id = m.inventario_item_id
                  LEFT JOIN entrada_mercadoria_item ei ON ei.id = m.entrada_item_id
                 WHERE m.produto_id = ?
                   AND m.criado_em >= ?
                   AND m.criado_em < ?
                 ORDER BY m.criado_em, m.id
                """;
        List<MovimentoKardex> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setObject(2, inicio);
            ps.setObject(3, fimExclusivo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new MovimentoKardex(
                            rs.getLong("id"),
                            rs.getObject("criado_em", OffsetDateTime.class),
                            TipoMovimento.valueOf(rs.getString("tipo")),
                            rs.getBigDecimal("quantidade"),
                            rs.getBigDecimal("saldo_anterior"),
                            rs.getBigDecimal("saldo_posterior"),
                            rs.getBigDecimal("custo_unitario"),
                            rs.getString("usuario_nome"),
                            lerLong(rs, "venda_id"),
                            lerLong(rs, "inventario_id"),
                            lerLong(rs, "entrada_id"),
                            rs.getString("motivo")));
                }
            }
        }
        return linhas;
    }

    private static Long lerLong(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static void definirLong(PreparedStatement ps, int indice, Long valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.BIGINT);
        } else {
            ps.setLong(indice, valor);
        }
    }
}
