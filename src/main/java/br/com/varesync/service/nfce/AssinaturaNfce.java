package br.com.varesync.service.nfce;

import br.com.swconsultoria.certificado.exception.CertificadoException;
import br.com.swconsultoria.nfe.Assinar;
import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.enuns.AmbienteEnum;
import br.com.swconsultoria.nfe.dom.enuns.AssinaturaEnum;
import br.com.swconsultoria.nfe.dom.enuns.DocumentoEnum;
import br.com.swconsultoria.nfe.dom.enuns.EstadosEnum;
import br.com.swconsultoria.nfe.dom.enuns.ServicosEnum;
import br.com.swconsultoria.nfe.exception.NfeException;
import br.com.swconsultoria.nfe.schemas.TEnviNFe;
import br.com.swconsultoria.nfe.schemas.TNFe;
import br.com.swconsultoria.nfe.util.WebServiceUtil;
import br.com.swconsultoria.nfe.util.XmlNfeUtil;
import br.com.varesync.service.NegocioException;
import java.nio.file.Path;
import java.time.ZoneId;
import javax.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assinatura da NFC-e pela biblioteca Java_NFe (F23.3): a nota vai num lote de uma ({@code enviNFe}, indSinc 1, como
 * a transmissão da parte B), o {@code infNFe} é assinado com o certificado do terminal (RSA-SHA1, C14N, como manda o
 * leiaute) e volta só o elemento {@code NFe} assinado.
 *
 * <p>Só homologação: a configuração da biblioteca é sempre criada com {@link AmbienteEnum#HOMOLOGACAO}.
 *
 * <p>O {@link Assinar} da biblioteca guarda a chave privada em campos estáticos; as assinaturas passam por uma trava,
 * para duas emissões ao mesmo tempo (a venda e o transmissor) não trocarem de chave no meio.
 */
public final class AssinaturaNfce {

    private static final Logger LOG = LoggerFactory.getLogger(AssinaturaNfce.class);
    /** A biblioteca guarda a chave e o certificado em campos estáticos: assinatura e chamadas à SEFAZ, uma por vez. */
    static final Object TRAVA = new Object();
    /**
     * O log da biblioteca (java.util.logging) grava o XML inteiro em INFO: fica só a partir de WARNING (F23.4). A
     * referência forte impede que o nível se perca com o coletor de lixo.
     */
    private static final java.util.logging.Logger LOG_BIBLIOTECA = java.util.logging.Logger.getLogger(
            "br.com.swconsultoria");

    static {
        LOG_BIBLIOTECA.setLevel(java.util.logging.Level.WARNING);
    }

    private final Path pastaSchemas;
    private final ZoneId fuso;

    public AssinaturaNfce(Path pastaSchemas, ZoneId fuso) {
        this.pastaSchemas = pastaSchemas;
        this.fuso = fuso;
    }

    /** A configuração da biblioteca para a UF, sempre em homologação. */
    public ConfiguracoesNfe configuracao(String uf, CertificadoA1 certificado) {
        try {
            return ConfiguracoesNfe.criarConfiguracoes(EstadosEnum.valueOf(uf), AmbienteEnum.HOMOLOGACAO,
                    certificado.paraBiblioteca(), pastaSchemas.toString(), fuso);
        } catch (CertificadoException e) {
            throw new NegocioException("O certificado deste terminal está vencido ou não é válido.");
        } catch (IllegalArgumentException e) {
            throw new NegocioException("A UF da loja não é válida para a NFC-e.");
        }
    }

    /** Endereço do QR Code da UF em homologação, pela tabela da biblioteca. */
    public String urlQrCode(ConfiguracoesNfe config) {
        return url(config, ServicosEnum.URL_QRCODE);
    }

    /** Endereço da consulta pela chave (urlChave) da UF em homologação, pela tabela da biblioteca. */
    public String urlConsulta(ConfiguracoesNfe config) {
        return url(config, ServicosEnum.URL_CONSULTANFCE);
    }

    private static String url(ConfiguracoesNfe config, ServicosEnum servico) {
        try {
            return WebServiceUtil.getUrl(config, DocumentoEnum.NFCE, servico);
        } catch (NfeException e) {
            throw new NegocioException("A biblioteca não tem o endereço da NFC-e para a UF " + config.getEstado()
                    + ". Informe-o em Configurações › NFC-e.");
        }
    }

    /** O lote de uma nota (enviNFe 4.00, indSinc 1) sem assinatura. */
    public static String lote(TNFe nfe, String idLote) {
        TEnviNFe envi = new TEnviNFe();
        envi.setVersao(MontagemNfce.VERSAO_LEIAUTE);
        envi.setIdLote(idLote);
        envi.setIndSinc("1");
        envi.getNFe().add(nfe);
        try {
            return XmlNfeUtil.objectToXml(envi);
        } catch (JAXBException | NfeException e) {
            LOG.error("Falha ao gerar o XML da NFC-e", e);
            throw new IllegalStateException("Falha ao gerar o XML da NFC-e", e);
        }
    }

    /** O lote com a nota assinada (para a validação pelo {@code enviNFe_v4.00.xsd} e, na parte B, o envio). */
    public String assinarLote(String loteSemAssinatura, ConfiguracoesNfe config) {
        synchronized (TRAVA) {
            try {
                return Assinar.assinaNfe(config, loteSemAssinatura, AssinaturaEnum.NFE);
            } catch (NfeException e) {
                LOG.error("Falha ao assinar a NFC-e: {}", e.getClass().getSimpleName());
                throw new NegocioException("Não foi possível assinar a NFC-e com o certificado deste terminal.");
            }
        }
    }

    /** Só o elemento {@code <NFe ...>...</NFe>} de dentro do lote. */
    public static String nfeDoLote(String lote) {
        int inicio = lote.indexOf("<NFe");
        int fim = lote.lastIndexOf("</NFe>");
        if (inicio < 0 || fim < inicio) {
            throw new IllegalStateException("Lote sem a NFe");
        }
        String nfe = lote.substring(inicio, fim + "</NFe>".length());
        return nfe.startsWith("<NFe xmlns") ? nfe
                : nfe.replaceFirst("^<NFe", "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"");
    }

    /** O lote de volta em torno da NFe assinada (gravada), para validar ou, na parte B, enviar. */
    public static String loteCom(String nfeAssinada, String idLote) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><enviNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\" "
                + "versao=\"" + MontagemNfce.VERSAO_LEIAUTE + "\"><idLote>" + idLote + "</idLote><indSinc>1</indSinc>"
                + nfeAssinada + "</enviNFe>";
    }
}
