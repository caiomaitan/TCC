package br.com.varesync.dao;

import br.com.varesync.model.Categoria;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Categorias de produto ({@code categoria}). Nome único sem diferenciar maiúsculas ({@code ux_categoria_nome}). */
public class CategoriaDAO {

    private static final String COLUNAS = "SELECT id, nome, ativa FROM categoria";

    public List<Categoria> listar(Connection con, boolean incluirInativas) throws SQLException {
        String sql = COLUNAS + (incluirInativas ? "" : " WHERE ativa") + " ORDER BY lower(nome)";
        List<Categoria> categorias = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                categorias.add(ler(rs));
            }
        }
        return categorias;
    }

    public Optional<Categoria> buscar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Outra categoria com o mesmo nome, sem diferenciar maiúsculas; {@code ignorarId} nulo no cadastro novo. */
    public Optional<Categoria> buscarPorNome(Connection con, String nome, Long ignorarId) throws SQLException {
        String sql = COLUNAS + """
                 WHERE lower(nome) = lower(?)
                   AND (CAST(? AS BIGINT) IS NULL OR id <> ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nome);
            Jdbc.definirLong(ps, 2, ignorarId);
            Jdbc.definirLong(ps, 3, ignorarId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    public long inserir(Connection con, String nome) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO categoria (nome) VALUES (?) RETURNING id")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void renomear(Connection con, long id, String nome) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE categoria SET nome = ? WHERE id = ?")) {
            ps.setString(1, nome);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void alterarSituacao(Connection con, long id, boolean ativa) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE categoria SET ativa = ? WHERE id = ?")) {
            ps.setBoolean(1, ativa);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Quantos produtos (ativos ou não) usam a categoria. */
    public long contarProdutos(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM produto WHERE categoria_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void excluir(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM categoria WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static Categoria ler(ResultSet rs) throws SQLException {
        return new Categoria(rs.getLong("id"), rs.getString("nome"), rs.getBoolean("ativa"));
    }
}
