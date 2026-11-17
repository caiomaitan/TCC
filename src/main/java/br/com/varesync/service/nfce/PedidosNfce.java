package br.com.varesync.service.nfce;

import br.com.swconsultoria.nfe.Assinar;
import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.Evento;
import br.com.swconsultoria.nfe.dom.enuns.AssinaturaEnum;
import br.com.swconsultoria.nfe.dom.enuns.DocumentoEnum;
import br.com.swconsultoria.nfe.exception.NfeException;
import br.com.swconsultoria.nfe.schemas.TInutNFe;
import br.com.swconsultoria.nfe.util.CancelamentoSubstituicaoUtil;
import br.com.swconsultoria.nfe.util.CancelamentoUtil;
import br.com.swconsultoria.nfe.util.InutilizacaoUtil;
import br.com.swconsultoria.nfe.util.XmlNfeUtil;
import br.com.varesync.service.NegocioException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Os pedidos de evento (110111 e 110112) e de inutilização assinados com o certificado do terminal, e o XML completo
 * que a lei manda guardar (F23.9): o {@code procEventoNFe} (o evento assinado com o retorno da SEFAZ) e o
 * {@code procInutNFe} (o pedido assinado com o retorno). Sem rede: a {@link SefazHomologacao} envia o pedido assinado
 * aqui, sem a biblioteca assinar de novo, e a SEFAZ falsa dos testes confere o mesmo pedido.
 *
 * <p>Os pedidos são montados pela própria biblioteca ({@code CancelamentoUtil}, {@code CancelamentoSubstituicaoUtil} e
 * {@code InutilizacaoUtil}), com o mesmo texto que ela enviaria. A assinatura RSA-SHA1 (PKCS#1 v1.5) é determinística:
 * na inutilização, que a biblioteca sempre assina de novo antes de enviar, a assinatura enviada é a mesma desta.
 */
public final class PedidosNfce {

    private static final Logger LOG = LoggerFactory.getLogger(PedidosNfce.class);
    private static final String NS = "http://www.portalfiscal.inf.br/nfe";
    private static final String DECLARACAO = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    /** Versão do leiaute dos eventos de cancelamento (110111 e 110112). */
    public static final String VERSAO_EVENTO = "1.00";
    /** Versão do leiaute da inutilização. */
    public static final String VERSAO_INUTILIZACAO = "4.00";

    private PedidosNfce() {
    }

    /** O lote {@code envEvento} do 110111, assinado. */
    public static String cancelamento(ConfiguracoesNfe config, String cnpj, String chave, String protocolo,
                                      String justificativa, OffsetDateTime quando) {
        try {
            Evento evento = evento(cnpj, chave, protocolo, justificativa, quando, config);
            return assinarEvento(config, XmlNfeUtil.objectToXml(CancelamentoUtil.montaCancelamento(evento, config),
                    config.getEncode()));
        } catch (NfeException | javax.xml.bind.JAXBException e) {
            throw falha("o pedido de cancelamento", e);
        }
    }

    /** O lote {@code envEvento} do 110112 (a de contingência {@code chaveSubstituta} ficou no lugar), assinado. */
    public static String cancelamentoPorSubstituicao(ConfiguracoesNfe config, String cnpj, String chave,
                                                     String protocolo, String chaveSubstituta,
                                                     String justificativa, OffsetDateTime quando) {
        try {
            Evento evento = evento(cnpj, chave, protocolo, justificativa, quando, config);
            evento.setChaveSusbstituta(chaveSubstituta);
            return assinarEvento(config, XmlNfeUtil.objectToXml(
                    CancelamentoSubstituicaoUtil.montaCancelamento(evento, config), config.getEncode()));
        } catch (NfeException | javax.xml.bind.JAXBException e) {
            throw falha("o pedido de cancelamento por substituição", e);
        }
    }

    /** O objeto da inutilização (o que a biblioteca envia) de uma faixa de números da série, no ano de {@code quando}. */
    public static TInutNFe inutilizacao(ConfiguracoesNfe config, String cnpj, int serie, int numeroInicial,
                                        int numeroFinal, String justificativa, OffsetDateTime quando) {
        return InutilizacaoUtil.montaInutilizacao(DocumentoEnum.NFCE, cnpj, serie, numeroInicial, numeroFinal,
                TextoNfe.limpar(justificativa, 255), quando.atZoneSameInstant(zona(config)).toLocalDateTime(),
                config);
    }

    /** O {@code inutNFe} assinado, com o mesmo texto que a biblioteca assina antes de enviar. */
    public static String inutilizacaoAssinada(ConfiguracoesNfe config, TInutNFe pedido) {
        try {
            String xml = XmlNfeUtil.objectToXml(pedido, config.getEncode())
                    .replaceAll(" xmlns:ns2=\"http://www.w3.org/2000/09/xmldsig#\"", "");
            return semDeclaracao(assinar(config, xml, AssinaturaEnum.INUTILIZACAO));
        } catch (NfeException | javax.xml.bind.JAXBException e) {
            throw falha("o pedido de inutilização", e);
        }
    }

    /** O elemento {@code evento} assinado de dentro do lote {@code envEvento}. */
    public static String eventoDoLote(String lote) {
        int inicio = lote.indexOf("<evento");
        int fim = lote.lastIndexOf("</evento>");
        if (inicio < 0 || fim < inicio) {
            throw new IllegalStateException("Lote sem o evento");
        }
        return lote.substring(inicio, fim + "</evento>".length());
    }

    /**
     * O procEventoNFe: o evento assinado e o {@code retEvento} da resposta (o {@code retEnvEvento} inteiro ou só o
     * {@code retEvento}).
     */
    public static String procEvento(String loteOuEventoAssinado, String resposta) {
        String evento = loteOuEventoAssinado.contains("<envEvento") ? eventoDoLote(loteOuEventoAssinado)
                : semDeclaracao(loteOuEventoAssinado);
        return DECLARACAO + "<procEventoNFe xmlns=\"" + NS + "\" versao=\"" + VERSAO_EVENTO + "\">" + evento
                + elemento(resposta, "retEvento") + "</procEventoNFe>";
    }

    /**
     * O procInutNFe: o pedido assinado e o {@code retInutNFe} da resposta. A raiz é {@code ProcInutNFe}, com P
     * maiúsculo, como declara o XSD oficial ({@code procInutNFe_v4.00.xsd}).
     */
    public static String procInutilizacao(String inutAssinado, String resposta) {
        return DECLARACAO + "<ProcInutNFe xmlns=\"" + NS + "\" versao=\"" + VERSAO_INUTILIZACAO + "\">"
                + semDeclaracao(inutAssinado) + elemento(resposta, "retInutNFe") + "</ProcInutNFe>";
    }

    private static String assinarEvento(ConfiguracoesNfe config, String xml) throws NfeException {
        // Como a biblioteca faz antes de enviar (Cancelar): o evento no namespace da NF-e e sem o prefixo do xmldsig.
        String lote = xml.replaceAll(" xmlns:ns2=\"http://www.w3.org/2000/09/xmldsig#\"", "")
                .replaceAll("<evento v", "<evento xmlns=\"" + NS + "\" v");
        return assinar(config, lote, AssinaturaEnum.EVENTO);
    }

    private static String assinar(ConfiguracoesNfe config, String xml, AssinaturaEnum tipo) throws NfeException {
        synchronized (AssinaturaNfce.TRAVA) {
            return Assinar.assinaNfe(config, xml, tipo);
        }
    }

    private static Evento evento(String cnpj, String chave, String protocolo, String justificativa,
                                 OffsetDateTime quando, ConfiguracoesNfe config) {
        Evento evento = new Evento();
        evento.setChave(chave);
        evento.setProtocolo(protocolo);
        evento.setMotivo(TextoNfe.limpar(justificativa, 255));
        evento.setCnpj(cnpj);
        evento.setDataEvento(quando.atZoneSameInstant(zona(config)).toLocalDateTime());
        return evento;
    }

    static ZoneId zona(ConfiguracoesNfe c) {
        return c.getZoneId() == null ? ZoneId.systemDefault() : c.getZoneId();
    }

    /**
     * O primeiro elemento {@code nome} da resposta, com os namespaces (pelo DOM, porque a resposta da SEFAZ pode vir
     * com prefixos); a resposta que não se lê vai como está, sem a declaração.
     */
    static String elemento(String xml, String nome) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("Resposta da SEFAZ vazia");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList nos = doc.getElementsByTagNameNS("*", nome);
            Element e = nos.getLength() > 0 ? (Element) nos.item(0) : doc.getDocumentElement();
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter saida = new StringWriter();
            t.transform(new DOMSource(e), new StreamResult(saida));
            return saida.toString().strip();
        } catch (Exception ex) {
            LOG.warn("Resposta da SEFAZ que não se lê ({}): vai como está", ex.getClass().getSimpleName());
            return semDeclaracao(xml);
        }
    }

    static String semDeclaracao(String xml) {
        return xml == null ? null : xml.replaceFirst("^\\s*<\\?xml[^>]*\\?>\\s*", "").strip();
    }

    private static NegocioException falha(String oQue, Exception e) {
        LOG.error("Falha ao assinar {} da NFC-e: {}", oQue, e.getClass().getSimpleName());
        return new NegocioException("Não foi possível assinar " + oQue + " da NFC-e com o certificado deste terminal.");
    }
}
