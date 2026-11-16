package br.com.varesync.service.nfce;

import br.com.swconsultoria.nfe.exception.NfeException;
import br.com.swconsultoria.nfe.util.NFCeUtil;
import br.com.varesync.service.NegocioException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * QR Code da NFC-e, versão 3 (NT 2025.001; dispensa o CSC), pela biblioteca Java_NFe (F23.3):
 * <ul>
 *   <li>emissão normal: {@code <url>?p=<chave>|3|2} (o 2 é o tpAmb);</li>
 *   <li>contingência: {@code <url>?p=<chave>|3|2|<dia da emissão>|<vNF com ponto>|<tipo do destinatário>|<CPF ou
 *   CNPJ>|<assinatura>}, com a assinatura RSA-SHA1 dos 7 primeiros parâmetros (com os separadores), feita com o
 *   certificado da NFC-e e escrita em Base64.</li>
 * </ul>
 */
public final class QrCodeNfce {

    private QrCodeNfce() {
    }

    public static String normal(String url, String chave) {
        return NFCeUtil.getCodeQRCodeV3(chave, MontagemNfce.AMBIENTE_HOMOLOGACAO, url);
    }

    /**
     * @param dhEmi     o dhEmi do XML (AAAA-MM-DDThh:mm:ss±hh:mm): o dia são os caracteres 9 e 10
     * @param documento CPF ou CNPJ do destinatário, ou nulo
     */
    public static String contingencia(String url, String chave, String dhEmi, BigDecimal valorNota, String documento,
                                      CertificadoA1 certificado) {
        String tipo = documento == null || documento.isBlank() ? "" : documento.length() == 11 ? "2" : "1";
        try {
            return NFCeUtil.getCodeQRCodeContingenciaV3(chave, MontagemNfce.AMBIENTE_HOMOLOGACAO, dhEmi,
                    valorNota.setScale(2, RoundingMode.HALF_UP).toPlainString(), tipo,
                    tipo.isEmpty() ? "" : documento, url, certificado.paraBiblioteca());
        } catch (NfeException e) {
            throw new NegocioException("Não foi possível assinar o QR Code da contingência com o certificado deste "
                    + "terminal.");
        }
    }
}
