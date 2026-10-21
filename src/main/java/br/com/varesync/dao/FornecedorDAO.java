package br.com.varesync.dao;

import br.com.varesync.model.Fornecedor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Fornecedores ({@code fornecedor}). Os horários vêm do Clock (V3: o trigger só usa {@code now()} fora do sistema). */
public class FornecedorDAO {

    /** Valores gravados no INSERT/UPDATE, já normalizados e validados pelo service (opcionais nulos). */
    public record Gravacao(String razaoSocial, String nomeFantasia, String cpfCnpj, String telefone, String email,
                           String contato, String cidade, String uf, String observacao) {
    }

    /** Fornecedor que já usa um CPF/CNPJ. */
    public record Dono(long id, String razaoSocial) {
    }

    private static final String COLUNAS = """
            SELECT id, razao_social, nome_fantasia, cpf_cnpj, telefone, email, contato, cidade, uf, observacao,
                   ativo, criado_em, atualizado_em
              FROM fornecedor
            """;

    public long inserir(Connection con, Gravacao g, OffsetDateTime agora) throws SQLException {
        String sql = """
                INSERT INTO fornecedor (razao_social, nome_fantasia, cpf_cnpj, telefone, email, contato, cidade, uf,
                                        observacao, criado_em, atualizado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g);
            ps.setObject(i++, agora);
            ps.setObject(i, agora);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void alterar(Connection con, long id, Gravacao g, OffsetDateTime agora) throws SQLException {
        String sql = """
                UPDATE fornecedor
                   SET razao_social = ?, nome_fantasia = ?, cpf_cnpj = ?, telefone = ?, email = ?, contato = ?,
                       cidade = ?, uf = ?, observacao = ?, atualizado_em = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g);
            ps.setObject(i++, agora);
            ps.setLong(i, id);
            ps.executeUpdate();
        }
    }

    public void alterarSituacao(Connection con, long id, boolean ativo, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE fornecedor SET ativo = ?, atualizado_em = ? WHERE id = ?")) {
            ps.setBoolean(1, ativo);
            ps.setObject(2, agora);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void excluir(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM fornecedor WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Fornecedor> buscar(Connection con, long id) throws SQLException {
        return buscar(con, id, false);
    }

    /** Busca e trava a linha até o fim da transação. */
    public Optional<Fornecedor> buscarParaAtualizar(Connection con, long id) throws SQLException {
        return buscar(con, id, true);
    }

    private Optional<Fornecedor> buscar(Connection con, long id, boolean travar) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE id = ?" + (travar ? " FOR UPDATE" : ""))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Ordenados pela razão social; sem {@code incluirInativos}, só os ativos. */
    public List<Fornecedor> listar(Connection con, boolean incluirInativos) throws SQLException {
        String sql = COLUNAS + (incluirInativos ? "" : " WHERE ativo") + " ORDER BY lower(razao_social), id";
        List<Fornecedor> fornecedores = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                fornecedores.add(ler(rs));
            }
        }
        return fornecedores;
    }

    /** Outro fornecedor com o mesmo CPF/CNPJ ({@code ignorarId} = o próprio, na alteração). */
    public Optional<Dono> donoDoDocumento(Connection con, String cpfCnpj, Long ignorarId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT id, razao_social FROM fornecedor
                 WHERE cpf_cnpj = ? AND (CAST(? AS BIGINT) IS NULL OR id <> ?)
                """)) {
            ps.setString(1, cpfCnpj);
            for (int i = 2; i <= 3; i++) {
                if (ignorarId == null) {
                    ps.setNull(i, Types.BIGINT);
                } else {
                    ps.setLong(i, ignorarId);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Dono(rs.getLong(1), rs.getString(2))) : Optional.empty();
            }
        }
    }

    /** Quantas entradas de mercadoria usam o fornecedor (com alguma, ele não pode ser excluído). */
    public int contarEntradas(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM entrada_mercadoria WHERE fornecedor_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int definir(PreparedStatement ps, Gravacao g) throws SQLException {
        int i = 1;
        ps.setString(i++, g.razaoSocial());
        ps.setString(i++, g.nomeFantasia());
        ps.setString(i++, g.cpfCnpj());
        ps.setString(i++, g.telefone());
        ps.setString(i++, g.email());
        ps.setString(i++, g.contato());
        ps.setString(i++, g.cidade());
        ps.setString(i++, g.uf());
        ps.setString(i++, g.observacao());
        return i;
    }

    private static Fornecedor ler(ResultSet rs) throws SQLException {
        return new Fornecedor(rs.getLong("id"), rs.getString("razao_social"), rs.getString("nome_fantasia"),
                rs.getString("cpf_cnpj"), rs.getString("telefone"), rs.getString("email"), rs.getString("contato"),
                rs.getString("cidade"), rs.getString("uf"), rs.getString("observacao"), rs.getBoolean("ativo"),
                rs.getObject("criado_em", OffsetDateTime.class), rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
