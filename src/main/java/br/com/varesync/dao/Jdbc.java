package br.com.varesync.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Apoio comum às consultas JDBC: valores opcionais e termo do LIKE. */
public final class Jdbc {

    private Jdbc() {
    }

    /** {@code null} vira {@code NULL} do tipo BIGINT. */
    public static void definirLong(PreparedStatement ps, int indice, Long valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.BIGINT);
        } else {
            ps.setLong(indice, valor);
        }
    }

    /** Coluna BIGINT que aceita {@code NULL}. */
    public static Long lerLong(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    /** "%", "_" e "\" digitados valem como texto, não como curinga do LIKE (usar com {@code ESCAPE '\'}). */
    public static String escaparLike(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
