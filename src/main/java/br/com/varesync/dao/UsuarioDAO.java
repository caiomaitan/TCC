package br.com.varesync.dao;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Usuários e suas permissões ({@code usuario}, {@code usuario_permissao}). */
public class UsuarioDAO {

    /** Usuário com o hash da senha, para conferir credenciais. Não sai do service de acesso. */
    public record Credencial(Usuario usuario, String senhaHash) {
        @Override
        public String toString() {
            return "Credencial[" + usuario + "]"; // nunca expõe o hash
        }
    }

    private static final String COLUNAS = """
            SELECT u.id, u.login, u.nome, u.senha_hash, u.supervisor, u.ativo, u.deve_trocar_senha,
                   u.tentativas_falhas, u.bloqueado_ate, u.ultimo_login_em,
                   ARRAY(SELECT up.permissao_codigo FROM usuario_permissao up
                          WHERE up.usuario_id = u.id ORDER BY up.permissao_codigo) AS permissoes
              FROM usuario u
            """;

    public long contar(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM usuario");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Impede que outra transação crie usuários até o fim desta.
     * Usado no primeiro acesso: dois computadores ao mesmo tempo não criam dois supervisores iniciais.
     */
    public void travarCadastro(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("LOCK TABLE usuario IN SHARE ROW EXCLUSIVE MODE")) {
            ps.execute();
        }
    }

    /** Busca pelo login (já normalizado) e trava a linha até o fim da transação. */
    public Optional<Credencial> buscarCredencialParaAtualizar(Connection con, String login) throws SQLException {
        String sql = COLUNAS + """
                 WHERE u.login = ?
                   FOR UPDATE OF u
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, login);
            return lerCredencial(ps);
        }
    }

    /** Busca pelo id e trava a linha até o fim da transação. */
    public Optional<Credencial> buscarCredencialPorIdParaAtualizar(Connection con, long id) throws SQLException {
        String sql = COLUNAS + """
                 WHERE u.id = ?
                   FOR UPDATE OF u
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            return lerCredencial(ps);
        }
    }

    public Optional<Usuario> buscarPorIdParaAtualizar(Connection con, long id) throws SQLException {
        return buscarCredencialPorIdParaAtualizar(con, id).map(Credencial::usuario);
    }

    public List<Usuario> listar(Connection con) throws SQLException {
        String sql = COLUNAS + " ORDER BY u.ativo DESC, lower(u.nome), u.login";
        List<Usuario> usuarios = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                usuarios.add(lerUsuario(rs));
            }
        }
        return usuarios;
    }

    /** Trava e devolve os ids dos supervisores ativos (regra "sempre ao menos um supervisor ativo"). */
    public List<Long> travarSupervisoresAtivos(Connection con) throws SQLException {
        String sql = """
                SELECT id FROM usuario
                 WHERE supervisor AND ativo
                 ORDER BY id
                   FOR UPDATE
                """;
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    public long inserir(Connection con, String login, String nome, String senhaHash, boolean supervisor,
                        boolean deveTrocarSenha) throws SQLException {
        String sql = """
                INSERT INTO usuario (login, nome, senha_hash, supervisor, deve_trocar_senha)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, login);
            ps.setString(2, nome);
            ps.setString(3, senhaHash);
            ps.setBoolean(4, supervisor);
            ps.setBoolean(5, deveTrocarSenha);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void alterarCadastro(Connection con, long id, String nome, boolean supervisor) throws SQLException {
        String sql = """
                UPDATE usuario
                   SET nome = ?, supervisor = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nome);
            ps.setBoolean(2, supervisor);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void alterarSituacao(Connection con, long id, boolean ativo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuario SET ativo = ? WHERE id = ?")) {
            ps.setBoolean(1, ativo);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Substitui as permissões gravadas do usuário. */
    public void definirPermissoes(Connection con, long id, Set<Permissao> permissoes) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM usuario_permissao WHERE usuario_id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        if (permissoes.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO usuario_permissao (usuario_id, permissao_codigo)
                VALUES (?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (Permissao permissao : permissoes) {
                ps.setLong(1, id);
                ps.setString(2, permissao.name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Grava o novo hash e zera tentativas e bloqueio. */
    public void alterarSenha(Connection con, long id, String senhaHash, boolean deveTrocarSenha) throws SQLException {
        String sql = """
                UPDATE usuario
                   SET senha_hash = ?, deve_trocar_senha = ?, tentativas_falhas = 0, bloqueado_ate = NULL
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, senhaHash);
            ps.setBoolean(2, deveTrocarSenha);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void registrarFalha(Connection con, long id, int tentativas, OffsetDateTime bloqueadoAte)
            throws SQLException {
        String sql = """
                UPDATE usuario
                   SET tentativas_falhas = ?, bloqueado_ate = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, tentativas);
            ps.setObject(2, bloqueadoAte);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void registrarLogin(Connection con, long id, OffsetDateTime quando) throws SQLException {
        String sql = """
                UPDATE usuario
                   SET tentativas_falhas = 0, bloqueado_ate = NULL, ultimo_login_em = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, quando);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void desbloquear(Connection con, long id) throws SQLException {
        String sql = """
                UPDATE usuario
                   SET tentativas_falhas = 0, bloqueado_ate = NULL
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static Optional<Credencial> lerCredencial(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new Credencial(lerUsuario(rs), rs.getString("senha_hash")));
        }
    }

    private static Usuario lerUsuario(ResultSet rs) throws SQLException {
        return new Usuario(
                rs.getLong("id"),
                rs.getString("login"),
                rs.getString("nome"),
                rs.getBoolean("supervisor"),
                rs.getBoolean("ativo"),
                rs.getBoolean("deve_trocar_senha"),
                rs.getInt("tentativas_falhas"),
                rs.getObject("bloqueado_ate", OffsetDateTime.class),
                rs.getObject("ultimo_login_em", OffsetDateTime.class),
                lerPermissoes(rs.getArray("permissoes")));
    }

    private static Set<Permissao> lerPermissoes(Array array) throws SQLException {
        Set<Permissao> permissoes = EnumSet.noneOf(Permissao.class);
        if (array != null) {
            for (Object codigo : (Object[]) array.getArray()) {
                permissoes.add(Permissao.valueOf((String) codigo));
            }
            array.free();
        }
        return permissoes;
    }
}
