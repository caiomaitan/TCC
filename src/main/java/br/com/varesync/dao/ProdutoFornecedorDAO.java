package br.com.varesync.dao;

import br.com.varesync.model.ProdutoFornecedor;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Fornecedores dos produtos ({@code produto_fornecedor}, F16.1). As regras ficam no service. */
public class ProdutoFornecedorDAO {

    /** Dados já validados (opcionais nulos). */
    public record Gravacao(long fornecedorId, String codigoNoFornecedor, String unidadeCompra, BigDecimal fator,
                           int prazoEntregaDias, boolean principal) {
    }

    private static final String SELECT = """
            SELECT pf.id, pf.produto_id, pf.fornecedor_id, f.razao_social, f.ativo, pf.codigo_no_fornecedor,
                   pf.unidade_compra, pf.fator, pf.ultimo_custo, pf.prazo_entrega_dias, pf.principal
              FROM produto_fornecedor pf
              JOIN fornecedor f ON f.id = pf.fornecedor_id
            """;

    /** Do produto: o principal primeiro, depois pela razão social. */
    public List<ProdutoFornecedor> listar(Connection con, long produtoId) throws SQLException {
        List<ProdutoFornecedor> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(SELECT
                + " WHERE pf.produto_id = ? ORDER BY pf.principal DESC, f.razao_social, pf.id")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(ler(rs));
                }
            }
        }
        return lista;
    }

    public Optional<ProdutoFornecedor> buscar(Connection con, long id) throws SQLException {
        return um(con, SELECT + " WHERE pf.id = ?", id);
    }

    /** Vínculo do produto com o fornecedor, se houver. */
    public Optional<ProdutoFornecedor> doProduto(Connection con, long produtoId, long fornecedorId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT + " WHERE pf.produto_id = ? AND pf.fornecedor_id = ?")) {
            ps.setLong(1, produtoId);
            ps.setLong(2, fornecedorId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Vínculo pelo código do produto no fornecedor ({@code cProd} da NF-e), exato. */
    public Optional<ProdutoFornecedor> porCodigo(Connection con, long fornecedorId, String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT
                + " WHERE pf.fornecedor_id = ? AND pf.codigo_no_fornecedor = ?")) {
            ps.setLong(1, fornecedorId);
            ps.setString(2, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Trava os vínculos do produto até o fim da transação (troca do principal). */
    public void travarDoProduto(Connection con, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM produto_fornecedor WHERE produto_id = ? ORDER BY id FOR UPDATE")) {
            ps.setLong(1, produtoId);
            ps.executeQuery().close();
        }
    }

    public boolean temPrincipal(Connection con, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM produto_fornecedor WHERE produto_id = ? AND principal")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long inserir(Connection con, long produtoId, Gravacao g, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        String sql = """
                INSERT INTO produto_fornecedor (produto_id, fornecedor_id, codigo_no_fornecedor, unidade_compra, fator,
                                                prazo_entrega_dias, principal, criado_em, atualizado_em, atualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setLong(2, g.fornecedorId());
            ps.setString(3, g.codigoNoFornecedor());
            ps.setString(4, g.unidadeCompra());
            ps.setBigDecimal(5, g.fator());
            ps.setInt(6, g.prazoEntregaDias());
            ps.setBoolean(7, g.principal());
            ps.setObject(8, agora);
            ps.setObject(9, agora);
            ps.setLong(10, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** O fornecedor do vínculo não muda (remova e inclua outro). */
    public void alterar(Connection con, long id, Gravacao g, long usuarioId, OffsetDateTime agora) throws SQLException {
        String sql = """
                UPDATE produto_fornecedor
                   SET codigo_no_fornecedor = ?, unidade_compra = ?, fator = ?, prazo_entrega_dias = ?, principal = ?,
                       atualizado_em = ?, atualizado_por = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, g.codigoNoFornecedor());
            ps.setString(2, g.unidadeCompra());
            ps.setBigDecimal(3, g.fator());
            ps.setInt(4, g.prazoEntregaDias());
            ps.setBoolean(5, g.principal());
            ps.setObject(6, agora);
            ps.setLong(7, usuarioId);
            ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    /** Tira a marca de principal dos outros vínculos do produto. */
    public void desmarcarPrincipal(Connection con, long produtoId, long exceto, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE produto_fornecedor SET principal = FALSE, atualizado_em = ?, atualizado_por = ?
                 WHERE produto_id = ? AND id <> ? AND principal
                """)) {
            ps.setObject(1, agora);
            ps.setLong(2, usuarioId);
            ps.setLong(3, produtoId);
            ps.setLong(4, exceto);
            ps.executeUpdate();
        }
    }

    /** Tira o código do vínculo de outro produto do mesmo fornecedor (o código passou para o produto escolhido). */
    public void liberarCodigo(Connection con, long fornecedorId, String codigo, long produtoMantido, long usuarioId,
                              OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE produto_fornecedor SET codigo_no_fornecedor = NULL, atualizado_em = ?, atualizado_por = ?
                 WHERE fornecedor_id = ? AND codigo_no_fornecedor = ? AND produto_id <> ?
                """)) {
            ps.setObject(1, agora);
            ps.setLong(2, usuarioId);
            ps.setLong(3, fornecedorId);
            ps.setString(4, codigo);
            ps.setLong(5, produtoMantido);
            ps.executeUpdate();
        }
    }

    /** @return linhas apagadas */
    public int remover(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM produto_fornecedor WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    /** Custo por unidade de venda da entrada confirmada; sem vínculo do produto com o fornecedor, nada muda. */
    public void atualizarUltimoCusto(Connection con, long produtoId, long fornecedorId, BigDecimal custo,
                                     long usuarioId, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE produto_fornecedor SET ultimo_custo = ?, atualizado_em = ?, atualizado_por = ?
                 WHERE produto_id = ? AND fornecedor_id = ?
                """)) {
            ps.setBigDecimal(1, custo);
            ps.setObject(2, agora);
            ps.setLong(3, usuarioId);
            ps.setLong(4, produtoId);
            ps.setLong(5, fornecedorId);
            ps.executeUpdate();
        }
    }

    private static Optional<ProdutoFornecedor> um(Connection con, String sql, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    private static ProdutoFornecedor ler(ResultSet rs) throws SQLException {
        return new ProdutoFornecedor(rs.getLong("id"), rs.getLong("produto_id"), rs.getLong("fornecedor_id"),
                rs.getString("razao_social"), rs.getBoolean("ativo"), rs.getString("codigo_no_fornecedor"),
                rs.getString("unidade_compra"), rs.getBigDecimal("fator"), rs.getBigDecimal("ultimo_custo"),
                rs.getInt("prazo_entrega_dias"), rs.getBoolean("principal"));
    }
}
