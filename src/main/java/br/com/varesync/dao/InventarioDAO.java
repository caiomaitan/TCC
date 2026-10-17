package br.com.varesync.dao;

import br.com.varesync.model.InventarioResumo;
import br.com.varesync.model.ItemContagem;
import br.com.varesync.model.ItemDivergencia;
import br.com.varesync.model.StatusInventario;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Inventários ({@code inventario}) e seus itens ({@code inventario_item}). A coluna {@code diferenca} é gerada pelo
 * banco e nunca é escrita; o estoque só muda pelo {@code EstoqueService.movimentar} ao aplicar.
 */
public class InventarioDAO {

    /**
     * Chave da trava de transação ({@code pg_advisory_xact_lock}) que serializa as inclusões de produtos em
     * inventários: sem ela, dois computadores poderiam colocar o mesmo produto em dois inventários abertos.
     */
    static final long TRAVA_INCLUSAO = 5_000_001L;

    /** Produto que se quer incluir e o inventário aberto em que ele já está (nulo se nenhum). */
    public record Candidato(long produtoId, String nome, boolean ativo, Long inventarioAbertoId) {
    }

    private static final String SELECT_RESUMO = """
            SELECT i.id, i.descricao, i.status, i.criado_em, uc.nome AS criado_por_nome, i.aplicado_em,
                   ua.nome AS aplicado_por_nome,
                   (SELECT count(*) FROM inventario_item ii WHERE ii.inventario_id = i.id) AS total_itens,
                   (SELECT count(*) FROM inventario_item ii
                     WHERE ii.inventario_id = i.id AND ii.quantidade_contada IS NOT NULL) AS itens_contados
              FROM inventario i
              JOIN usuario uc      ON uc.id = i.criado_por
              LEFT JOIN usuario ua ON ua.id = i.aplicado_por
            """;

    private static final String SELECT_ITEM = """
            SELECT ii.id, ii.produto_id, p.nome, p.unidade, u.fracionada, ii.quantidade_contada, ii.estoque_sistema,
                   ii.diferenca, ii.custo_unitario, ii.contado_em, uc.nome AS contado_por_nome,
                   (SELECT b.codigo FROM produto_codigo_barras b
                     WHERE b.produto_id = p.id AND b.principal) AS codigo_principal
              FROM inventario_item ii
              JOIN produto p        ON p.id = ii.produto_id
              JOIN unidade_medida u ON u.sigla = p.unidade
              LEFT JOIN usuario uc  ON uc.id = ii.contado_por
            """;

    /** @return número (id) do inventário criado */
    public long inserir(Connection con, String descricao, long usuarioId, OffsetDateTime criadoEm)
            throws SQLException {
        String sql = """
                INSERT INTO inventario (descricao, status, criado_por, criado_em)
                VALUES (?, 'ABERTO', ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, descricao);
            ps.setLong(2, usuarioId);
            ps.setObject(3, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Mais recentes primeiro; sem {@code incluirEncerrados}, só os abertos. */
    public List<InventarioResumo> listar(Connection con, boolean incluirEncerrados) throws SQLException {
        String sql = SELECT_RESUMO + (incluirEncerrados ? "" : " WHERE i.status = 'ABERTO'") + " ORDER BY i.id DESC";
        List<InventarioResumo> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(lerResumo(rs));
            }
        }
        return lista;
    }

    public Optional<InventarioResumo> buscar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_RESUMO + " WHERE i.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerResumo(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Lê a situação travando a linha até o fim da transação: {@code exclusiva} (aplicar, cancelar, incluir e remover
     * itens) espera as contagens em andamento e as impede; a compartilhada (contagem) deixa vários computadores
     * contarem ao mesmo tempo, mas espera uma aplicação ou um cancelamento terminar.
     */
    public Optional<StatusInventario> travar(Connection con, long id, boolean exclusiva) throws SQLException {
        String sql = "SELECT status FROM inventario WHERE id = ? " + (exclusiva ? "FOR UPDATE" : "FOR SHARE");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(StatusInventario.valueOf(rs.getString(1))) : Optional.empty();
            }
        }
    }

    /** Serializa as inclusões de produtos em inventários até o fim da transação ({@link #TRAVA_INCLUSAO}). */
    public void travarInclusoes(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, TRAVA_INCLUSAO);
            ps.executeQuery().close();
        }
    }

    /**
     * Produtos a incluir, pelo nome sem acento, com o inventário aberto em que cada um já está.
     *
     * @param produtoId   um produto (ativo ou não; quem chama decide)
     * @param categoriaId os ativos da categoria (quando {@code produtoId} é nulo)
     *                    — com os dois nulos, todos os ativos
     */
    public List<Candidato> candidatos(Connection con, Long produtoId, Long categoriaId) throws SQLException {
        String filtro;
        Long parametro;
        if (produtoId != null) {
            filtro = "p.id = ?";
            parametro = produtoId;
        } else if (categoriaId != null) {
            filtro = "p.ativo AND p.categoria_id = ?";
            parametro = categoriaId;
        } else {
            filtro = "p.ativo";
            parametro = null;
        }
        String sql = """
                SELECT p.id, p.nome, p.ativo,
                       (SELECT min(ii.inventario_id)
                          FROM inventario_item ii
                          JOIN inventario i ON i.id = ii.inventario_id
                         WHERE ii.produto_id = p.id AND i.status = 'ABERTO') AS inventario_aberto
                  FROM produto p
                 WHERE %s
                 ORDER BY p.nome_busca, p.id
                """.formatted(filtro);
        List<Candidato> candidatos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            if (parametro != null) {
                ps.setLong(1, parametro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidatos.add(new Candidato(rs.getLong("id"), rs.getString("nome"), rs.getBoolean("ativo"),
                            lerLong(rs, "inventario_aberto")));
                }
            }
        }
        return candidatos;
    }

    /** Inclui os produtos sem contagem (todos os campos da contagem nulos, {@code ck_ii_contagem}). */
    public void inserirItens(Connection con, long inventarioId, List<Long> produtoIds) throws SQLException {
        if (produtoIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO inventario_item (inventario_id, produto_id) VALUES (?, ?)")) {
            for (long produtoId : produtoIds) {
                ps.setLong(1, inventarioId);
                ps.setLong(2, produtoId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** @return linhas removidas (0 se o item não é deste inventário) */
    public int removerItem(Connection con, long inventarioId, long itemId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM inventario_item WHERE id = ? AND inventario_id = ?")) {
            ps.setLong(1, itemId);
            ps.setLong(2, inventarioId);
            return ps.executeUpdate();
        }
    }

    /** Itens da aba Contagem, pelo nome sem acento. Não lê o estoque do sistema (contagem cega). */
    public List<ItemContagem> itensContagem(Connection con, long inventarioId) throws SQLException {
        List<ItemContagem> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                SELECT_ITEM + " WHERE ii.inventario_id = ? ORDER BY p.nome_busca, ii.id")) {
            ps.setLong(1, inventarioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(lerContagem(rs));
                }
            }
        }
        return itens;
    }

    public Optional<ItemContagem> buscarItem(Connection con, long inventarioId, long itemId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                SELECT_ITEM + " WHERE ii.inventario_id = ? AND ii.id = ?")) {
            ps.setLong(1, inventarioId);
            ps.setLong(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(lerContagem(rs)) : Optional.empty();
            }
        }
    }

    /** Item do inventário pelo código de barras exato (qualquer código do produto, não só o principal). */
    public Optional<Long> itemPorCodigoBarras(Connection con, long inventarioId, String codigo) throws SQLException {
        String sql = """
                SELECT ii.id
                  FROM inventario_item ii
                  JOIN produto_codigo_barras b ON b.produto_id = ii.produto_id
                 WHERE ii.inventario_id = ? AND b.codigo = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, inventarioId);
            ps.setString(2, codigo);
            return umId(ps);
        }
    }

    public Optional<Long> itemPorProduto(Connection con, long inventarioId, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM inventario_item WHERE inventario_id = ? AND produto_id = ?")) {
            ps.setLong(1, inventarioId);
            ps.setLong(2, produtoId);
            return umId(ps);
        }
    }

    /**
     * Grava (ou sobrescreve, na recontagem) os cinco campos da contagem; o custo é o atual do produto, lido no
     * próprio UPDATE.
     *
     * @return linhas alteradas (0 se o item não é deste inventário)
     */
    public int registrarContagem(Connection con, long inventarioId, long itemId, BigDecimal quantidadeContada,
                                 BigDecimal estoqueSistema, long usuarioId, OffsetDateTime contadoEm)
            throws SQLException {
        String sql = """
                UPDATE inventario_item ii
                   SET quantidade_contada = ?,
                       estoque_sistema    = ?,
                       custo_unitario     = (SELECT p.custo FROM produto p WHERE p.id = ii.produto_id),
                       contado_em         = ?,
                       contado_por        = ?
                 WHERE ii.id = ? AND ii.inventario_id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, quantidadeContada);
            ps.setBigDecimal(2, estoqueSistema);
            ps.setObject(3, contadoEm);
            ps.setLong(4, usuarioId);
            ps.setLong(5, itemId);
            ps.setLong(6, inventarioId);
            return ps.executeUpdate();
        }
    }

    /** Itens com sistema, contado, diferença e custo (aba Divergências e R13), pelo nome sem acento. */
    public List<ItemDivergencia> divergencias(Connection con, long inventarioId) throws SQLException {
        List<ItemDivergencia> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                SELECT_ITEM + " WHERE ii.inventario_id = ? ORDER BY p.nome_busca, ii.id")) {
            ps.setLong(1, inventarioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(new ItemDivergencia(
                            rs.getLong("id"),
                            rs.getLong("produto_id"),
                            rs.getString("nome"),
                            rs.getString("unidade"),
                            rs.getBoolean("fracionada"),
                            rs.getBigDecimal("estoque_sistema"),
                            rs.getBigDecimal("quantidade_contada"),
                            rs.getBigDecimal("diferenca"),
                            rs.getBigDecimal("custo_unitario"),
                            rs.getObject("contado_em", OffsetDateTime.class),
                            rs.getString("contado_por_nome")));
                }
            }
        }
        return itens;
    }

    public void marcarAplicado(Connection con, long id, long usuarioId, OffsetDateTime aplicadoEm)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE inventario SET status = 'APLICADO', aplicado_por = ?, aplicado_em = ? WHERE id = ?
                """)) {
            ps.setLong(1, usuarioId);
            ps.setObject(2, aplicadoEm);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void marcarCancelado(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE inventario SET status = 'CANCELADO' WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static InventarioResumo lerResumo(ResultSet rs) throws SQLException {
        return new InventarioResumo(
                rs.getLong("id"),
                rs.getString("descricao"),
                StatusInventario.valueOf(rs.getString("status")),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getString("criado_por_nome"),
                rs.getObject("aplicado_em", OffsetDateTime.class),
                rs.getString("aplicado_por_nome"),
                rs.getInt("total_itens"),
                rs.getInt("itens_contados"));
    }

    private static ItemContagem lerContagem(ResultSet rs) throws SQLException {
        return new ItemContagem(
                rs.getLong("id"),
                rs.getLong("produto_id"),
                rs.getString("codigo_principal"),
                rs.getString("nome"),
                rs.getString("unidade"),
                rs.getBoolean("fracionada"),
                rs.getBigDecimal("quantidade_contada"),
                rs.getObject("contado_em", OffsetDateTime.class),
                rs.getString("contado_por_nome"));
    }

    private static Optional<Long> umId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        }
    }

    private static Long lerLong(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
