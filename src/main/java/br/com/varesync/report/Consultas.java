package br.com.varesync.report;

import br.com.varesync.model.Periodo;
import br.com.varesync.service.NegocioException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/** Apoio às consultas dos relatórios: período (7.2) e filtros opcionais como parâmetros do SQL. */
final class Consultas {

    private Consultas() {
    }

    /** {@code data_hora >= início 00:00 AND data_hora < (fim + 1 dia) 00:00}, nos parâmetros {@code i} e {@code i+1}. */
    static void periodo(PreparedStatement ps, int i, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        Periodo periodo = filtros.periodo();
        if (periodo == null) {
            throw new NegocioException("Informe o período.");
        }
        ps.setObject(i, periodo.inicioEm(parametros.zona()));
        ps.setObject(i + 1, periodo.fimExclusivoEm(parametros.zona()));
    }

    /** Para {@code (CAST(? AS BIGINT) IS NULL OR coluna = ?)}: o mesmo valor nos parâmetros {@code i} e {@code i+1}. */
    static void opcional(PreparedStatement ps, int i, Long valor) throws SQLException {
        for (int j = i; j <= i + 1; j++) {
            if (valor == null) {
                ps.setNull(j, Types.BIGINT);
            } else {
                ps.setLong(j, valor);
            }
        }
    }

    /** Para {@code (CAST(? AS VARCHAR) IS NULL OR coluna = ?)}. */
    static void opcional(PreparedStatement ps, int i, String valor) throws SQLException {
        for (int j = i; j <= i + 1; j++) {
            if (valor == null) {
                ps.setNull(j, Types.VARCHAR);
            } else {
                ps.setString(j, valor);
            }
        }
    }
}
