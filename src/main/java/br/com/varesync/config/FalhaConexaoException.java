package br.com.varesync.config;

/** Não foi possível conectar ao banco. A mensagem é para o usuário; o detalhe técnico fica na causa. */
public class FalhaConexaoException extends RuntimeException {

    public FalhaConexaoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
