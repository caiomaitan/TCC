package br.com.varesync.service.nfce;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * A causa da falta de comunicação com a SEFAZ em linguagem simples (F23.6, "Contingência à vista"): tempo esgotado,
 * endereço não encontrado, conexão recusada, certificado recusado pela SEFAZ ou erro inesperado, com o tipo do erro.
 *
 * <p>Só olha os tipos das exceções da cadeia (e o nome da classe, para as da biblioteca e do Axis2): as mensagens
 * podem trazer o XML, o endereço com parâmetros ou o caminho do certificado, e nunca entram no texto.
 */
public final class CausaSemComunicacao {

    private CausaSemComunicacao() {
    }

    /** @param timeoutSegundos o {@code nfce.timeout_segundos} da chamada, para o texto do tempo esgotado */
    public static String descrever(Throwable erro, int timeoutSegundos) {
        Throwable raiz = erro;
        for (Throwable c = erro; c != null; c = c.getCause() == c ? null : c.getCause()) {
            raiz = c;
            String nome = c.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException || nome.contains("timeout")) {
                return "tempo esgotado: a SEFAZ não respondeu em " + timeoutSegundos + " segundos";
            }
            if (c instanceof UnknownHostException) {
                return "endereço da SEFAZ não encontrado: confira a internet deste computador e os endereços em "
                        + "Configurações › NFC-e";
            }
            if (c instanceof ConnectException || c instanceof NoRouteToHostException) {
                return "conexão recusada: o endereço da SEFAZ não aceitou a conexão (confira a internet e o firewall)";
            }
            if (c instanceof SSLException || nome.contains("ssl") || nome.contains("certpath")
                    || nome.contains("certificate")) {
                return "certificado recusado pela SEFAZ ou falha na conexão segura: confira o certificado deste "
                        + "terminal (validade e CNPJ)";
            }
        }
        return "erro inesperado na comunicação (" + raiz.getClass().getSimpleName() + ")";
    }

    /** Serviço paralisado (cStat 108 e 109) ou resposta sem cStat. */
    public static String paralisado(String cstat) {
        return cstat == null ? "a SEFAZ respondeu sem o código da situação"
                : "serviço da SEFAZ paralisado (cStat " + cstat + ")";
    }
}
