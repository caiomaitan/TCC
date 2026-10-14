package br.com.varesync.dao;

import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;

/**
 * Kardex ({@code movimento_estoque}). Só INSERT: o trigger preenche os saldos e atualiza {@code produto_estoque};
 * o banco recusa UPDATE e DELETE. Usado somente por {@code EstoqueService.movimentar}.
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

    private static void definirLong(PreparedStatement ps, int indice, Long valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.BIGINT);
        } else {
            ps.setLong(indice, valor);
        }
    }
}
