package br.com.varesync.dao;

import br.com.varesync.model.Configuracoes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Parâmetros chave/valor da tabela {@code configuracao}. As chaves vêm da V2; o acesso tipado é {@link Configuracoes}. */
public class ConfiguracaoDAO {

    /** Todos os parâmetros, já tipados (chave ausente ou inválida = padrão da V2). */
    public Configuracoes carregar(Connection con) throws SQLException {
        return Configuracoes.de(listar(con));
    }

    public Map<String, String> listar(Connection con) throws SQLException {
        Map<String, String> valores = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT chave, valor FROM configuracao");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                valores.put(rs.getString(1), rs.getString(2));
            }
        }
        return valores;
    }

    public Optional<String> buscar(Connection con, String chave) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT valor FROM configuracao WHERE chave = ?")) {
            ps.setString(1, chave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Trava a tabela para gravação (duas telas de Configurações salvando ao mesmo tempo). */
    public void travar(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT chave FROM configuracao FOR UPDATE")) {
            ps.executeQuery().close();
        }
    }

    /** Altera o valor de uma chave; se a chave não existe (removida fora do sistema), ela é criada. */
    public void gravar(Connection con, String chave, String valor) throws SQLException {
        if (!atualizar(con, chave, valor)) {
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO configuracao (chave, valor) VALUES (?, ?)")) {
                ps.setString(1, chave);
                ps.setString(2, valor);
                ps.executeUpdate();
            }
        }
    }

    /** Altera o valor de uma chave existente. @return false se a chave não existe */
    public boolean atualizar(Connection con, String chave, String valor) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE configuracao SET valor = ? WHERE chave = ?")) {
            ps.setString(1, valor);
            ps.setString(2, chave);
            return ps.executeUpdate() == 1;
        }
    }
}
