package br.com.varesync.dao;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Conferências do gerador de dados de demonstração (6.14) antes de gravar qualquer coisa. */
public class DemoDAO {

    public long contarVendas(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM venda");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Dos logins informados, os que já existem. */
    public List<String> loginsExistentes(Connection con, Collection<String> logins) throws SQLException {
        return existentes(con, "SELECT login FROM usuario WHERE login = ANY (?) ORDER BY login", "varchar", logins);
    }

    /** Dos códigos de barras informados, os que já pertencem a algum produto. */
    public List<String> codigosExistentes(Connection con, Collection<String> codigos) throws SQLException {
        return existentes(con, "SELECT codigo FROM produto_codigo_barras WHERE codigo = ANY (?) ORDER BY codigo",
                "varchar", codigos);
    }

    /** Dos PLUs informados, os que já pertencem a algum produto. */
    public List<String> plusExistentes(Connection con, Collection<Integer> plus) throws SQLException {
        return existentes(con, "SELECT plu::text FROM produto WHERE plu = ANY (?) ORDER BY plu", "integer", plus);
    }

    /** Dos CPFs/CNPJs informados, os que já pertencem a algum fornecedor. */
    public List<String> documentosExistentes(Connection con, Collection<String> documentos) throws SQLException {
        return existentes(con, "SELECT cpf_cnpj FROM fornecedor WHERE cpf_cnpj = ANY (?) ORDER BY cpf_cnpj",
                "varchar", documentos);
    }

    /** Dos CPFs/CNPJs informados, os que já pertencem a algum cliente. */
    public List<String> documentosDeClientesExistentes(Connection con, Collection<String> documentos)
            throws SQLException {
        return existentes(con, "SELECT cpf_cnpj FROM cliente WHERE cpf_cnpj = ANY (?) ORDER BY cpf_cnpj",
                "varchar", documentos);
    }

    private static List<String> existentes(Connection con, String sql, String tipo, Collection<?> valores)
            throws SQLException {
        List<String> encontrados = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Array array = con.createArrayOf(tipo, valores.toArray());
            ps.setArray(1, array);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    encontrados.add(rs.getString(1));
                }
            }
        }
        return encontrados;
    }
}
