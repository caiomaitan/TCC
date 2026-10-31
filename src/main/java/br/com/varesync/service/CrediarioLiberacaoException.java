package br.com.varesync.service;

/**
 * Venda no crediário acima do limite, ou a cliente com título vencido há mais de {@code crediario.bloqueio_atraso_dias}
 * (F14.2), sem a permissão {@code LIMITE_CREDITO}: a tela pede a liberação e tenta de novo com ela.
 */
public class CrediarioLiberacaoException extends NegocioException {

    public CrediarioLiberacaoException(String mensagem) {
        super(mensagem);
    }
}
