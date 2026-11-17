package br.com.varesync.service.nfce;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Leitura do XML da NFC-e e conferência das assinaturas nos testes (F23.3). */
public final class XmlNfceTeste {

    private XmlNfceTeste() {
    }

    public static Document documento(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** O texto de cada elemento com esse nome (sem o namespace), na ordem do documento. */
    public static List<String> todos(String xml, String nome) {
        try {
            NodeList nos = (NodeList) XPathFactory.newInstance().newXPath()
                    .evaluate("//*[local-name()='" + nome + "']", documento(xml), XPathConstants.NODESET);
            List<String> textos = new ArrayList<>();
            for (int i = 0; i < nos.getLength(); i++) {
                textos.add(nos.item(i).getTextContent());
            }
            return textos;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** O texto do primeiro elemento com esse nome, ou nulo. */
    public static String texto(String xml, String nome) {
        List<String> todos = todos(xml, nome);
        return todos.isEmpty() ? null : todos.get(0);
    }

    /** Se a assinatura XML do infNFe confere com a chave pública. */
    public static boolean assinaturaConfere(String xml, PublicKey chave) {
        return assinaturaConfere(xml, chave, "infNFe");
    }

    /** Se a assinatura XML do elemento (infNFe, infEvento ou infInut) confere com a chave pública. */
    public static boolean assinaturaConfere(String xml, PublicKey chave, String elementoAssinado) {
        try {
            Document doc = documento(xml);
            Element inf = (Element) doc.getElementsByTagNameNS("*", elementoAssinado).item(0);
            inf.setIdAttribute("Id", true);
            NodeList assinaturas = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
            if (assinaturas.getLength() != 1) {
                return false;
            }
            DOMValidateContext contexto = new DOMValidateContext(chave, assinaturas.item(0));
            // O leiaute da NF-e exige RSA-SHA1, que a validação segura do JDK 17+ recusa por padrão.
            contexto.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
            XMLSignature assinatura = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(contexto);
            return assinatura.validate(contexto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Se a assinatura RSA-SHA1 em Base64 do QR Code de contingência confere com os 7 primeiros parâmetros. */
    public static boolean assinaturaQrCodeConfere(String qrCode, PublicKey chave) {
        try {
            String parametros = qrCode.substring(qrCode.indexOf("?p=") + 3);
            int ultimo = parametros.lastIndexOf('|');
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initVerify(chave);
            s.update(parametros.substring(0, ultimo).getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(parametros.substring(ultimo + 1)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** O certificado de teste (só a parte pública). */
    public static X509Certificate certificadoDeTeste() {
        try (var in = java.nio.file.Files.newInputStream(CertificadoTeste.arquivo())) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, CertificadoTeste.SENHA.toCharArray());
            return (X509Certificate) ks.getCertificate(Collections.list(ks.aliases()).get(0));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
