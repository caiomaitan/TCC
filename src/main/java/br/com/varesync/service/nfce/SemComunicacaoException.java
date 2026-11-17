package br.com.varesync.service.nfce;

/**
 * Sem comunicação com a SEFAZ (F23.4): sem conexão, tempo esgotado em {@code nfce.timeout_segundos}, erro HTTP ou
 * serviço paralisado (cStat 108 e 109). A nota pode ter chegado ou não: quem chama decide (contingência ou nova
 * tentativa).
 *
 * <p>A mensagem é a causa em linguagem simples (F23.6, "Contingência à vista": tempo esgotado, endereço não encontrado,
 * conexão recusada, certificado recusado pela SEFAZ ou erro inesperado, com o tipo do erro). Ela vai para o motivo da
 * NFC-e SEM_RESPOSTA, para a auditoria da entrada na contingência e para o "Testar comunicação"; nunca traz o XML, o
 * certificado nem a senha (só textos fixos e nomes de classes, {@link CausaSemComunicacao}).
 */
public final class SemComunicacaoException extends RuntimeException {

    public SemComunicacaoException(String causa) {
        super(causa);
    }

    /** A causa em linguagem simples. */
    public String causa() {
        return getMessage();
    }
}
