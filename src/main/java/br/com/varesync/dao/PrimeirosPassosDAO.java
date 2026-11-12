package br.com.varesync.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** O que o assistente "Primeiros passos" (F21.4) confere nos dados: produtos, usuários e backup. */
public class PrimeirosPassosDAO {

    public int produtosAtivos(Connection con) throws SQLException {
        return contar(con, "SELECT count(*) FROM produto WHERE ativo");
    }

    public int usuariosAtivos(Connection con) throws SQLException {
        return contar(con, "SELECT count(*) FROM usuario WHERE ativo");
    }

    /** Backups feitos pelo sistema (manual ou automático), pela auditoria {@code BACKUP_REALIZADO}. */
    public int backupsFeitos(Connection con) throws SQLException {
        return contar(con, "SELECT count(*) FROM auditoria WHERE acao = 'BACKUP_REALIZADO'");
    }

    private static int contar(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
