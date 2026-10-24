package br.com.varesync.service;

import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Converte exceções técnicas em mensagens para o usuário, pelo SQLState e pelo nome da constraint.
 * {@link NegocioException} passa sem alteração, também quando vem embrulhada (handler de FXML); nas demais a exceção
 * original fica como causa.
 */
public final class TradutorErros {

    static final String UNICO = "23505";
    static final String CHAVE_ESTRANGEIRA = "23503";
    static final String CHECK = "23514";
    static final String TRIGGER = "P0001";

    private TradutorErros() {
    }

    public static RuntimeException traduzir(Throwable erro) {
        if (erro instanceof NegocioException negocio) {
            return negocio;
        }
        if (erro instanceof ErroInesperadoException inesperado) {
            return inesperado;
        }
        // Regra de negócio embrulhada (o FXMLLoader embrulha a exceção do handler em InvocationTargetException).
        for (Throwable t = erro.getCause(); t != null; t = t.getCause()) {
            if (t instanceof NegocioException negocio) {
                return negocio;
            }
        }
        SQLException sql = encontrarSql(erro);
        if (sql == null) {
            return new ErroInesperadoException(erro);
        }
        String estado = sql.getSQLState();
        ServerErrorMessage servidor = sql instanceof PSQLException psql ? psql.getServerErrorMessage() : null;
        String constraint = servidor == null ? null : servidor.getConstraint();

        if (UNICO.equals(estado)) {
            return new NegocioException(MensagensBanco.mensagem(constraint).orElse(MensagensBanco.UNICO_PADRAO), erro);
        }
        if (CHAVE_ESTRANGEIRA.equals(estado)) {
            return new NegocioException(MensagensBanco.EM_USO, erro);
        }
        if (CHECK.equals(estado)) {
            return new NegocioException(MensagensBanco.mensagem(constraint).orElse(MensagensBanco.CHECK_PADRAO), erro);
        }
        if (TRIGGER.equals(estado) && servidor != null && servidor.getMessage() != null) {
            // Texto do RAISE EXCEPTION, já em português e sem o prefixo "ERROR:" do driver.
            return new NegocioException(servidor.getMessage(), erro);
        }
        return new ErroInesperadoException(erro);
    }

    private static SQLException encontrarSql(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
