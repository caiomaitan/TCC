package br.com.varesync.service.nfce;

/**
 * Sem comunicação com a SEFAZ (F23.4): sem conexão, tempo esgotado em {@code nfce.timeout_segundos}, erro HTTP ou
 * serviço paralisado (cStat 108 e 109). A nota pode ter chegado ou não: quem chama decide (contingência ou nova
 * tentativa). A mensagem é para o log; nunca traz o certificado, a senha nem o XML.
 */
public final class SemComunicacaoException extends RuntimeException {

    public SemComunicacaoException(String motivo) {
        super(motivo);
    }
}
