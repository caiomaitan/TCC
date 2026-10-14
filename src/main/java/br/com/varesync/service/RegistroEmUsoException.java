package br.com.varesync.service;

/**
 * O registro tem vínculos e não pode ser excluído; a tela oferece inativar em vez disso (6.3).
 */
public class RegistroEmUsoException extends NegocioException {

    public RegistroEmUsoException(String mensagem) {
        super(mensagem);
    }
}
