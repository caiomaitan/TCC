package br.com.varesync.service.nfce;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Validação pelo XSD oficial (F23.3). Os XSD do pacote PL 010g (com o grupo IBSCBS) vêm em
 * {@code src/main/resources/nfe/schemas} e são copiados para uma pasta do usuário ({@code ~/.varesync/schemas/<pacote>}),
 * porque o validador e a biblioteca leem de uma pasta.
 *
 * <p>O validador é o do JDK, e não o {@code Validar} da biblioteca: aquele ignora os erros de padrão, de enumeração,
 * de tamanho máximo e de tipo (justamente os de um CNPJ ou de um valor mal formatado). Decisão no PROGRESSO.
 */
public final class ValidacaoXsd {

    public static final String VERSAO_PACOTE = "PL_010g_v1.03";
    public static final String XSD_LOTE = "enviNFe_v4.00.xsd";
    private static final String RECURSOS = "/nfe/";
    private static final Map<Path, Schema> COMPILADOS = new ConcurrentHashMap<>();

    private final Path pasta;

    /** @param pastaBase pasta do usuário onde os XSD são copiados (a subpasta é a do pacote) */
    public ValidacaoXsd(Path pastaBase) {
        this.pasta = pastaBase.resolve(VERSAO_PACOTE);
    }

    /** A pasta com os XSD (copiados na primeira vez). */
    public Path pasta() {
        copiarSeFaltar();
        return pasta;
    }

    /** A lista de arquivos do pacote ({@code schemas.lst}). */
    public static List<String> arquivos() {
        try (InputStream in = ValidacaoXsd.class.getResourceAsStream(RECURSOS + "schemas.lst")) {
            if (in == null) {
                throw new IllegalStateException("schemas.lst não está no classpath");
            }
            List<String> nomes = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String linha; (linha = r.readLine()) != null; ) {
                    if (!linha.isBlank()) {
                        nomes.add(linha.strip());
                    }
                }
            }
            return nomes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void copiarSeFaltar() {
        try {
            Files.createDirectories(pasta);
            for (String nome : arquivos()) {
                Path destino = pasta.resolve(nome);
                if (Files.exists(destino)) {
                    continue;
                }
                try (InputStream in = ValidacaoXsd.class.getResourceAsStream(RECURSOS + "schemas/" + nome)) {
                    if (in == null) {
                        throw new IllegalStateException("XSD ausente no classpath: " + nome);
                    }
                    Path temporario = Files.createTempFile(pasta, nome, ".tmp");
                    Files.copy(in, temporario, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível copiar os XSD da NFC-e para " + pasta, e);
        }
    }

    private Schema schema(String xsd) {
        Path arquivo = pasta().resolve(xsd);
        return COMPILADOS.computeIfAbsent(arquivo, a -> {
            try {
                SchemaFactory fabrica = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
                fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                return fabrica.newSchema(a.toFile());
            } catch (SAXException e) {
                throw new IllegalStateException("XSD da NFC-e inválido: " + a.getFileName(), e);
            }
        });
    }

    /** Os erros de validação do XML pelo XSD (vazio = válido). */
    public List<String> erros(String xml, String xsd) {
        List<String> erros = new ArrayList<>();
        Validator validador = schema(xsd).newValidator();
        try {
            validador.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validador.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException e) {
            throw new IllegalStateException(e);
        }
        validador.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                // aviso não reprova
            }

            @Override
            public void error(SAXParseException e) {
                erros.add(traduzir(e.getMessage()));
            }

            @Override
            public void fatalError(SAXParseException e) {
                erros.add(traduzir(e.getMessage()));
            }
        });
        try {
            validador.validate(new StreamSource(new StringReader(xml)));
        } catch (SAXException e) {
            if (erros.isEmpty()) {
                erros.add(traduzir(e.getMessage()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return erros;
    }

    /** Tira os códigos do Xerces e o namespace, para a mensagem caber na tela. */
    static String traduzir(String mensagem) {
        if (mensagem == null) {
            return "erro de validação";
        }
        return mensagem.replaceAll("cvc-[a-zA-Z0-9.\\-]+: ", "")
                .replace("http://www.portalfiscal.inf.br/nfe", "")
                .replace("\"\":", "")
                .replace("{", "").replace("}", "")
                .strip();
    }
}
