package br.com.varesync.service;

/**
 * Falha técnica sem tradução para o usuário. A mensagem é genérica;
 * o detalhe fica na causa, que vai para o log.
 */
public class ErroInesperadoException extends RuntimeException {

    public static final String MENSAGEM = "Erro inesperado. Os detalhes foram registrados no log.";

    public ErroInesperadoException(Throwable causa) {
        super(MENSAGEM, causa);
    }
}
