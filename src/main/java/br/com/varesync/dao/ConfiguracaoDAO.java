package br.com.varesync.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Parâmetros chave/valor da tabela {@code configuracao}. As chaves vêm da V2. */
public class ConfiguracaoDAO {

    public Optional<String> buscar(Connection con, String chave) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT valor FROM configuracao WHERE chave = ?")) {
            ps.setString(1, chave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
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
