package br.com.varesync.service;

/**
 * Desconto acima do limite do operador (6.6) sem a permissão {@code DESCONTO_ACIMA_LIMITE}: a tela pede a liberação
 * de supervisor e tenta de novo com ela.
 */
public class LimiteDescontoException extends NegocioException {

    public LimiteDescontoException(String mensagem) {
        super(mensagem);
    }
}
