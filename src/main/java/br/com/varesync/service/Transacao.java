package br.com.varesync.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Abre uma transação, entrega a mesma {@link Connection} ao trabalho e faz commit ou rollback.
 * Todo método de service que grava usa {@code executar}; os DAOs só recebem a conexão.
 */
public final class Transacao {

    private final DataSource dataSource;

    public Transacao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T executar(Trabalho<T> trabalho) {
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try {
                T resultado = trabalho.executar(con);
                con.commit();
                return resultado;
            } catch (Exception e) {
                desfazer(con, e);
                throw TradutorErros.traduzir(e);
            }
        } catch (SQLException e) {
            throw TradutorErros.traduzir(e);
        }
    }

    /** Rollback que não esconde o erro original: a falha do rollback entra como suprimida. */
    private static void desfazer(Connection con, Exception original) {
        try {
            con.rollback();
        } catch (SQLException falhaRollback) {
            original.addSuppressed(falhaRollback);
        }
    }

    @FunctionalInterface
    public interface Trabalho<T> {
        T executar(Connection con) throws Exception;
    }
}
