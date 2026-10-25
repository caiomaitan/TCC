package br.com.varesync.dao;

import br.com.varesync.model.UnidadeCadastro;
import br.com.varesync.model.UnidadeMedida;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Unidades de medida ({@code unidade_medida}). A sigla é a chave e o produto aponta para ela
 * ({@code fk_produto_unidade}): o cadastro (F10.5) só muda a sigla ou exclui a unidade sem produtos.
 */
public class UnidadeMedidaDAO {

    private static final String COLUNAS = "SELECT sigla, descricao, fracionada FROM unidade_medida";

    /** UN primeiro (padrão do cadastro); as demais pela sigla. */
    public List<UnidadeMedida> listar(Connection con) throws SQLException {
        String sql = COLUNAS + " ORDER BY (sigla <> 'UN'), sigla";
        List<UnidadeMedida> unidades = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                unidades.add(ler(rs));
            }
        }
        return unidades;
    }

    /** Cadastro: todas as unidades com o número de produtos (ativos ou não) que as usam, na ordem de {@link #listar}. */
    public List<UnidadeCadastro> listarComUso(Connection con) throws SQLException {
        String sql = """
                SELECT um.sigla, um.descricao, um.fracionada,
                       (SELECT count(*) FROM produto p WHERE p.unidade = um.sigla) AS produtos
                  FROM unidade_medida um
                 ORDER BY (um.sigla <> 'UN'), um.sigla
                """;
        List<UnidadeCadastro> unidades = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                unidades.add(new UnidadeCadastro(ler(rs), rs.getLong("produtos")));
            }
        }
        return unidades;
    }

    public Optional<UnidadeMedida> buscar(Connection con, String sigla) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE sigla = ?")) {
            ps.setString(1, sigla);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Trava a unidade ({@code FOR UPDATE}) antes de conferir o uso: o INSERT ou UPDATE de produto que aponta para
     * ela trava a mesma linha ({@code FOR KEY SHARE}, pela chave estrangeira) e espera.
     */
    public Optional<UnidadeMedida> travar(Connection con, String sigla) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE sigla = ? FOR UPDATE")) {
            ps.setString(1, sigla);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Produtos (ativos ou não) com a unidade. */
    public long contarProdutos(Connection con, String sigla) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM produto WHERE unidade = ?")) {
            ps.setString(1, sigla);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void inserir(Connection con, UnidadeMedida unidade) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO unidade_medida (sigla, descricao, fracionada) VALUES (?, ?, ?)")) {
            ps.setString(1, unidade.sigla());
            ps.setString(2, unidade.descricao());
            ps.setBoolean(3, unidade.fracionada());
            ps.executeUpdate();
        }
    }

    /** Grava sigla, descrição e fracionada da unidade {@code siglaAtual}. */
    public void alterar(Connection con, String siglaAtual, UnidadeMedida unidade) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE unidade_medida SET sigla = ?, descricao = ?, fracionada = ? WHERE sigla = ?")) {
            ps.setString(1, unidade.sigla());
            ps.setString(2, unidade.descricao());
            ps.setBoolean(3, unidade.fracionada());
            ps.setString(4, siglaAtual);
            ps.executeUpdate();
        }
    }

    public void excluir(Connection con, String sigla) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM unidade_medida WHERE sigla = ?")) {
            ps.setString(1, sigla);
            ps.executeUpdate();
        }
    }

    private static UnidadeMedida ler(ResultSet rs) throws SQLException {
        return new UnidadeMedida(rs.getString("sigla"), rs.getString("descricao"), rs.getBoolean("fracionada"));
    }
}
