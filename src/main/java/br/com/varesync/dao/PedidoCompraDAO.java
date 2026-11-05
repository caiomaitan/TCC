package br.com.varesync.dao;

import br.com.varesync.model.ItemPedido;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.model.StatusPedido;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Pedidos de compra ({@code pedido_compra}) e seus itens (F16.3). As regras de situação ficam no service; aqui só o
 * SQL.
 */
public class PedidoCompraDAO {

    /** Cabeçalho já validado (opcionais nulos). */
    public record Gravacao(long fornecedorId, LocalDate previsaoEntrega, String observacao) {
    }

    private static final String SELECT_RESUMO = """
            SELECT p.id, p.fornecedor_id, f.razao_social, f.ativo AS fornecedor_ativo, p.status, p.previsao_entrega,
                   p.observacao, p.criado_em, uc.nome AS criado_por_nome, p.enviado_em, ue.nome AS enviado_por_nome,
                   p.cancelado_em, ux.nome AS cancelado_por_nome,
                   (SELECT count(*) FROM pedido_compra_item i WHERE i.pedido_id = p.id) AS total_itens,
                   (SELECT coalesce(sum(round(i.quantidade * i.custo_previsto, 2)), 0) FROM pedido_compra_item i
                     WHERE i.pedido_id = p.id) AS valor_previsto,
                   (SELECT coalesce(sum(greatest(i.quantidade - i.quantidade_recebida, 0)), 0)
                      FROM pedido_compra_item i WHERE i.pedido_id = p.id) AS pendente
              FROM pedido_compra p
              JOIN fornecedor f    ON f.id = p.fornecedor_id
              JOIN usuario uc      ON uc.id = p.criado_por
              LEFT JOIN usuario ue ON ue.id = p.enviado_por
              LEFT JOIN usuario ux ON ux.id = p.cancelado_por
            """;

    private static final String SELECT_ITEM = """
            SELECT i.id, i.produto_id, pr.nome, pr.unidade, u.fracionada, i.quantidade, i.custo_previsto,
                   i.quantidade_recebida, pf.codigo_no_fornecedor, pf.unidade_compra, coalesce(pf.fator, 1) AS fator
              FROM pedido_compra_item i
              JOIN pedido_compra p      ON p.id = i.pedido_id
              JOIN produto pr           ON pr.id = i.produto_id
              JOIN unidade_medida u     ON u.sigla = pr.unidade
              LEFT JOIN produto_fornecedor pf ON pf.produto_id = i.produto_id AND pf.fornecedor_id = p.fornecedor_id
            """;

    public long inserir(Connection con, Gravacao g, long usuarioId, OffsetDateTime criadoEm) throws SQLException {
        String sql = """
                INSERT INTO pedido_compra (fornecedor_id, previsao_entrega, observacao, criado_por, criado_em)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, g.fornecedorId());
            ps.setObject(2, g.previsaoEntrega());
            ps.setString(3, g.observacao());
            ps.setLong(4, usuarioId);
            ps.setObject(5, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void alterar(Connection con, long id, Gravacao g) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE pedido_compra SET fornecedor_id = ?, previsao_entrega = ?, observacao = ? WHERE id = ?
                """)) {
            ps.setLong(1, g.fornecedorId());
            ps.setObject(2, g.previsaoEntrega());
            ps.setString(3, g.observacao());
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    /** Trava o pedido até o fim da transação e devolve a situação. */
    public Optional<StatusPedido> travar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT status FROM pedido_compra WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(StatusPedido.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    public Optional<PedidoCompra> buscar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_RESUMO + " WHERE p.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerResumo(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Mais recentes primeiro.
     *
     * @param situacoes só estas; vazia = todas
     * @param fornecedorId nulo = todos
     */
    public List<PedidoCompra> listar(Connection con, Collection<StatusPedido> situacoes, Long fornecedorId)
            throws SQLException {
        String sql = SELECT_RESUMO + """
                 WHERE (cardinality(CAST(? AS VARCHAR[])) = 0 OR p.status = ANY (CAST(? AS VARCHAR[])))
                   AND (CAST(? AS BIGINT) IS NULL OR p.fornecedor_id = ?)
                 ORDER BY p.id DESC
                """;
        String[] nomes = situacoes.stream().map(Enum::name).toArray(String[]::new);
        List<PedidoCompra> pedidos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setArray(1, con.createArrayOf("varchar", nomes));
            ps.setArray(2, con.createArrayOf("varchar", nomes));
            for (int i = 3; i <= 4; i++) {
                if (fornecedorId == null) {
                    ps.setNull(i, Types.BIGINT);
                } else {
                    ps.setLong(i, fornecedorId);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pedidos.add(lerResumo(rs));
                }
            }
        }
        return pedidos;
    }

    /** Itens na ordem em que foram lançados. */
    public List<ItemPedido> itens(Connection con, long pedidoId) throws SQLException {
        List<ItemPedido> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(SELECT_ITEM + " WHERE i.pedido_id = ? ORDER BY i.id")) {
            ps.setLong(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(lerItem(rs));
                }
            }
        }
        return itens;
    }

    public Optional<ItemPedido> buscarItem(Connection con, long pedidoId, long itemId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_ITEM + " WHERE i.pedido_id = ? AND i.id = ?")) {
            ps.setLong(1, pedidoId);
            ps.setLong(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerItem(rs)) : Optional.empty();
            }
        }
    }

    /** Item do pedido com o produto, se já houver. */
    public Optional<Long> itemDoProduto(Connection con, long pedidoId, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM pedido_compra_item WHERE pedido_id = ? AND produto_id = ?")) {
            ps.setLong(1, pedidoId);
            ps.setLong(2, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public long inserirItem(Connection con, long pedidoId, long produtoId, BigDecimal quantidade,
                            BigDecimal custoPrevisto) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO pedido_compra_item (pedido_id, produto_id, quantidade, custo_previsto)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)) {
            ps.setLong(1, pedidoId);
            ps.setLong(2, produtoId);
            ps.setBigDecimal(3, quantidade);
            ps.setBigDecimal(4, custoPrevisto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** @return linhas alteradas (0 se o item não é deste pedido) */
    public int alterarItem(Connection con, long pedidoId, long itemId, BigDecimal quantidade,
                           BigDecimal custoPrevisto) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE pedido_compra_item SET quantidade = ?, custo_previsto = ? WHERE id = ? AND pedido_id = ?
                """)) {
            ps.setBigDecimal(1, quantidade);
            ps.setBigDecimal(2, custoPrevisto);
            ps.setLong(3, itemId);
            ps.setLong(4, pedidoId);
            return ps.executeUpdate();
        }
    }

    /** @return linhas apagadas (0 se o item não é deste pedido) */
    public int removerItem(Connection con, long pedidoId, long itemId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM pedido_compra_item WHERE id = ? AND pedido_id = ?")) {
            ps.setLong(1, itemId);
            ps.setLong(2, pedidoId);
            return ps.executeUpdate();
        }
    }

    public void marcarEnviado(Connection con, long id, long usuarioId, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE pedido_compra SET status = 'ENVIADO', enviado_por = ?, enviado_em = ? WHERE id = ?
                """)) {
            ps.setLong(1, usuarioId);
            ps.setObject(2, agora);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void marcarCancelado(Connection con, long id, long usuarioId, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE pedido_compra SET status = 'CANCELADO', cancelado_por = ?, cancelado_em = ? WHERE id = ?
                """)) {
            ps.setLong(1, usuarioId);
            ps.setObject(2, agora);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** PARCIAL ou RECEBIDO, pela confirmação de uma entrada vinculada. */
    public void alterarStatus(Connection con, long id, StatusPedido status) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE pedido_compra SET status = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Soma {@code quantidade} na recebida do item do produto; @return linhas alteradas (0 fora do pedido). */
    public int somarRecebida(Connection con, long pedidoId, long produtoId, BigDecimal quantidade)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE pedido_compra_item SET quantidade_recebida = quantidade_recebida + ?
                 WHERE pedido_id = ? AND produto_id = ?
                """)) {
            ps.setBigDecimal(1, quantidade);
            ps.setLong(2, pedidoId);
            ps.setLong(3, produtoId);
            return ps.executeUpdate();
        }
    }

    /** Algum item ainda com recebida menor que a pedida? */
    public boolean temPendente(Connection con, long pedidoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM pedido_compra_item WHERE pedido_id = ? AND quantidade_recebida < quantidade)
                """)) {
            ps.setLong(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** Entradas vinculadas ao pedido (só as abertas, com {@code soAbertas}), na ordem do número. */
    public List<Long> entradas(Connection con, long pedidoId, boolean soAbertas) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM entrada_mercadoria WHERE pedido_id = ? AND (NOT ? OR status = 'ABERTA') ORDER BY id")) {
            ps.setLong(1, pedidoId);
            ps.setBoolean(2, soAbertas);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private static PedidoCompra lerResumo(ResultSet rs) throws SQLException {
        return new PedidoCompra(rs.getLong("id"), rs.getLong("fornecedor_id"), rs.getString("razao_social"),
                rs.getBoolean("fornecedor_ativo"), StatusPedido.valueOf(rs.getString("status")),
                rs.getObject("previsao_entrega", LocalDate.class), rs.getString("observacao"),
                rs.getObject("criado_em", OffsetDateTime.class), rs.getString("criado_por_nome"),
                rs.getObject("enviado_em", OffsetDateTime.class), rs.getString("enviado_por_nome"),
                rs.getObject("cancelado_em", OffsetDateTime.class), rs.getString("cancelado_por_nome"),
                rs.getInt("total_itens"), rs.getBigDecimal("valor_previsto"), rs.getBigDecimal("pendente"));
    }

    private static ItemPedido lerItem(ResultSet rs) throws SQLException {
        return new ItemPedido(rs.getLong("id"), rs.getLong("produto_id"), rs.getString("nome"),
                rs.getString("unidade"), rs.getBoolean("fracionada"), rs.getBigDecimal("quantidade"),
                rs.getBigDecimal("custo_previsto"), rs.getBigDecimal("quantidade_recebida"),
                rs.getString("codigo_no_fornecedor"), rs.getString("unidade_compra"), rs.getBigDecimal("fator"));
    }
}
