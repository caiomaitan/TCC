package br.com.varesync.dao;

import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.StatusEntrada;
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
import java.util.Optional;

/**
 * Entradas de mercadoria ({@code entrada_mercadoria}) e seus itens. O estoque e o custo médio mudam no service
 * (pelo {@code EstoqueService.movimentar} e pelo {@code ProdutoDAO}); aqui só a nota e os itens.
 */
public class EntradaDAO {

    /** Dados da nota já validados (opcionais nulos). */
    public record Gravacao(long fornecedorId, String numeroDocumento, LocalDate dataEmissao,
                           BigDecimal valorTotalNota) {
    }

    private static final String SELECT_RESUMO = """
            SELECT e.id, e.fornecedor_id, f.razao_social, e.numero_documento, e.data_emissao, e.valor_total_nota,
                   e.status, e.criado_em, uc.nome AS criado_por_nome, e.confirmado_em,
                   ucf.nome AS confirmado_por_nome, e.pedido_id,
                   (SELECT count(*) FROM entrada_mercadoria_item i WHERE i.entrada_id = e.id) AS total_itens,
                   (SELECT coalesce(sum(i.valor_total), 0) FROM entrada_mercadoria_item i
                     WHERE i.entrada_id = e.id) AS soma_itens
              FROM entrada_mercadoria e
              JOIN fornecedor f     ON f.id = e.fornecedor_id
              JOIN usuario uc       ON uc.id = e.criado_por
              LEFT JOIN usuario ucf ON ucf.id = e.confirmado_por
            """;

    private static final String SELECT_ITEM = """
            SELECT i.id, i.produto_id, p.nome, p.unidade, u.fracionada, i.quantidade, i.custo_unitario,
                   i.valor_total, p.custo AS custo_atual, p.preco_venda,
                   (SELECT c.codigo FROM produto_codigo_barras c WHERE c.produto_id = p.id AND c.principal)
                       AS codigo_barras
              FROM entrada_mercadoria_item i
              JOIN produto p        ON p.id = i.produto_id
              JOIN unidade_medida u ON u.sigla = p.unidade
            """;

    public long inserir(Connection con, Gravacao g, long usuarioId, OffsetDateTime criadoEm) throws SQLException {
        String sql = """
                INSERT INTO entrada_mercadoria (fornecedor_id, numero_documento, data_emissao, valor_total_nota,
                                                criado_por, criado_em)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g);
            ps.setLong(i++, usuarioId);
            ps.setObject(i, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void alterar(Connection con, long id, Gravacao g) throws SQLException {
        String sql = """
                UPDATE entrada_mercadoria
                   SET fornecedor_id = ?, numero_documento = ?, data_emissao = ?, valor_total_nota = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g);
            ps.setLong(i, id);
            ps.executeUpdate();
        }
    }

    /** Apaga a entrada aberta; os itens saem em cascata. */
    public void excluir(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM entrada_mercadoria WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Parcelas da nota, na ordem do número (F15.3). */
    public List<ParcelaEntrada> parcelas(Connection con, long entradaId) throws SQLException {
        String sql = """
                SELECT numero, vencimento, valor
                  FROM entrada_mercadoria_parcela
                 WHERE entrada_id = ?
                 ORDER BY numero
                """;
        List<ParcelaEntrada> parcelas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, entradaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parcelas.add(new ParcelaEntrada(rs.getInt("numero"), rs.getObject("vencimento", LocalDate.class),
                            rs.getBigDecimal("valor")));
                }
            }
        }
        return parcelas;
    }

    /** Troca todas as parcelas da entrada pelas informadas. */
    public void substituirParcelas(Connection con, long entradaId, List<ParcelaEntrada> parcelas) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM entrada_mercadoria_parcela WHERE entrada_id = ?")) {
            ps.setLong(1, entradaId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO entrada_mercadoria_parcela (entrada_id, numero, vencimento, valor)
                VALUES (?, ?, ?, ?)
                """)) {
            for (ParcelaEntrada p : parcelas) {
                ps.setLong(1, entradaId);
                ps.setInt(2, p.numero());
                ps.setObject(3, p.vencimento());
                ps.setBigDecimal(4, p.valor());
                ps.executeUpdate();
            }
        }
    }

    /** Trava a entrada até o fim da transação e devolve a situação. */
    public Optional<StatusEntrada> travar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT status FROM entrada_mercadoria WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(StatusEntrada.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    public Optional<EntradaResumo> buscar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_RESUMO + " WHERE e.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerResumo(rs)) : Optional.empty();
            }
        }
    }

    /** Mais recentes primeiro; sem {@code incluirConfirmadas}, só as abertas. */
    public List<EntradaResumo> listar(Connection con, boolean incluirConfirmadas) throws SQLException {
        String sql = SELECT_RESUMO + (incluirConfirmadas ? "" : " WHERE e.status = 'ABERTA'") + " ORDER BY e.id DESC";
        List<EntradaResumo> entradas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                entradas.add(lerResumo(rs));
            }
        }
        return entradas;
    }

    /** Outra entrada com a mesma nota do mesmo fornecedor ({@code ux_em_documento}). */
    public Optional<Long> mesmaNota(Connection con, long fornecedorId, String numeroDocumento, Long ignorarId)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT id FROM entrada_mercadoria
                 WHERE fornecedor_id = ? AND numero_documento = ? AND (CAST(? AS BIGINT) IS NULL OR id <> ?)
                """)) {
            ps.setLong(1, fornecedorId);
            ps.setString(2, numeroDocumento);
            for (int i = 3; i <= 4; i++) {
                if (ignorarId == null) {
                    ps.setNull(i, Types.BIGINT);
                } else {
                    ps.setLong(i, ignorarId);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** Itens na ordem em que foram lançados. */
    public List<ItemEntrada> itens(Connection con, long entradaId) throws SQLException {
        List<ItemEntrada> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(SELECT_ITEM + " WHERE i.entrada_id = ? ORDER BY i.id")) {
            ps.setLong(1, entradaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(lerItem(rs));
                }
            }
        }
        return itens;
    }

    public Optional<ItemEntrada> buscarItem(Connection con, long entradaId, long itemId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_ITEM + " WHERE i.entrada_id = ? AND i.id = ?")) {
            ps.setLong(1, entradaId);
            ps.setLong(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerItem(rs)) : Optional.empty();
            }
        }
    }

    /** Item da entrada com o produto, se já houver. */
    public Optional<Long> itemDoProduto(Connection con, long entradaId, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM entrada_mercadoria_item WHERE entrada_id = ? AND produto_id = ?")) {
            ps.setLong(1, entradaId);
            ps.setLong(2, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public long inserirItem(Connection con, long entradaId, long produtoId, BigDecimal quantidade,
                            BigDecimal custoUnitario, BigDecimal valorTotal) throws SQLException {
        String sql = """
                INSERT INTO entrada_mercadoria_item (entrada_id, produto_id, quantidade, custo_unitario, valor_total)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, entradaId);
            ps.setLong(2, produtoId);
            ps.setBigDecimal(3, quantidade);
            ps.setBigDecimal(4, custoUnitario);
            ps.setBigDecimal(5, valorTotal);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** @return linhas alteradas (0 se o item não é desta entrada) */
    public int alterarItem(Connection con, long entradaId, long itemId, BigDecimal quantidade,
                           BigDecimal custoUnitario, BigDecimal valorTotal) throws SQLException {
        String sql = """
                UPDATE entrada_mercadoria_item SET quantidade = ?, custo_unitario = ?, valor_total = ?
                 WHERE id = ? AND entrada_id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, quantidade);
            ps.setBigDecimal(2, custoUnitario);
            ps.setBigDecimal(3, valorTotal);
            ps.setLong(4, itemId);
            ps.setLong(5, entradaId);
            return ps.executeUpdate();
        }
    }

    /** @return linhas apagadas (0 se o item não é desta entrada) */
    public int removerItem(Connection con, long entradaId, long itemId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM entrada_mercadoria_item WHERE id = ? AND entrada_id = ?")) {
            ps.setLong(1, itemId);
            ps.setLong(2, entradaId);
            return ps.executeUpdate();
        }
    }

    public void marcarConfirmada(Connection con, long id, long usuarioId, OffsetDateTime confirmadoEm)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE entrada_mercadoria SET status = 'CONFIRMADA', confirmado_por = ?, confirmado_em = ?
                 WHERE id = ?
                """)) {
            ps.setLong(1, usuarioId);
            ps.setObject(2, confirmadoEm);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** Vincula a entrada ao pedido de compra (nulo desvincula), F16.3. */
    public void vincularPedido(Connection con, long id, Long pedidoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE entrada_mercadoria SET pedido_id = ? WHERE id = ?")) {
            if (pedidoId == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, pedidoId);
            }
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static int definir(PreparedStatement ps, Gravacao g) throws SQLException {
        int i = 1;
        ps.setLong(i++, g.fornecedorId());
        ps.setString(i++, g.numeroDocumento());
        ps.setObject(i++, g.dataEmissao());
        ps.setBigDecimal(i++, g.valorTotalNota());
        return i;
    }

    private static EntradaResumo lerResumo(ResultSet rs) throws SQLException {
        return new EntradaResumo(rs.getLong("id"), rs.getLong("fornecedor_id"), rs.getString("razao_social"),
                rs.getString("numero_documento"), rs.getObject("data_emissao", LocalDate.class),
                rs.getBigDecimal("valor_total_nota"), StatusEntrada.valueOf(rs.getString("status")),
                rs.getObject("criado_em", OffsetDateTime.class), rs.getString("criado_por_nome"),
                rs.getObject("confirmado_em", OffsetDateTime.class), rs.getString("confirmado_por_nome"),
                rs.getInt("total_itens"), rs.getBigDecimal("soma_itens"),
                rs.getObject("pedido_id") == null ? null : rs.getLong("pedido_id"));
    }

    private static ItemEntrada lerItem(ResultSet rs) throws SQLException {
        return new ItemEntrada(rs.getLong("id"), rs.getLong("produto_id"), rs.getString("nome"),
                rs.getString("codigo_barras"), rs.getString("unidade"), rs.getBoolean("fracionada"),
                rs.getBigDecimal("quantidade"), rs.getBigDecimal("custo_unitario"), rs.getBigDecimal("valor_total"),
                rs.getBigDecimal("custo_atual"), rs.getBigDecimal("preco_venda"));
    }
}
