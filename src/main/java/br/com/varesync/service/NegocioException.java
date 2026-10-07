package br.com.varesync.service;

/** Violação de regra de negócio. A mensagem já está pronta para ser exibida ao usuário. */
public class NegocioException extends RuntimeException {

    public NegocioException(String mensagem) {
        super(mensagem);
    }

    public NegocioException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
