package br.com.varesync.service;

/**
 * Devolução depois de {@code devolucao.prazo_dias} (F13.2) sem a liberação de um supervisor: a tela pede a liberação
 * e tenta de novo com ela.
 */
public class PrazoDevolucaoException extends NegocioException {

    public PrazoDevolucaoException(String mensagem) {
        super(mensagem);
    }
}
