package br.com.varesync.service;

import br.com.varesync.util.Gtin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Leitura do XML da NF-e (6.11, segunda parte) para gerar a entrada de mercadoria: emitente, número, emissão, valor
 * total e itens ({@code cEAN}/{@code cEANTrib}, {@code xProd}, {@code qCom}/{@code vUnCom}). Quando há EAN tributável
 * e a unidade tributável é diferente da comercial, usa a quantidade e o código tributáveis ({@code qTrib}), que
 * costumam estar na unidade de venda (ex.: 1 CX comercial = 12 UN tributáveis). Aceita o XML da nota ({@code NFe}) ou o
 * de distribuição ({@code nfeProc}); o XML é lido sem DTD nem entidades externas.
 */
public final class LeitorNfe {

    private LeitorNfe() {
    }

    /** Emitente da nota; {@code documento} só com dígitos (CNPJ ou CPF). */
    public record Emitente(String documento, String razaoSocial, String nomeFantasia, String cidade, String uf,
                           String telefone) {
    }

    /**
     * Item da nota, já na unidade escolhida.
     *
     * @param numero            {@code nItem}
     * @param codigoBarras      EAN válido, ou nulo ("SEM GTIN")
     * @param valorTotal        {@code vProd} (valor da linha)
     * @param unidadeTributavel a quantidade e o código são os tributáveis
     * @param comercial         o item como o fornecedor vende, para o vínculo pelo código do fornecedor (F16.1)
     */
    public record Item(int numero, String codigoBarras, String descricao, String unidade, BigDecimal quantidade,
                       BigDecimal valorTotal, boolean unidadeTributavel, Comercial comercial, List<Rastro> rastros) {

        public Item {
            rastros = rastros == null ? List.of() : List.copyOf(rastros);
        }

        /** Item sem o grupo {@code rastro}. */
        public Item(int numero, String codigoBarras, String descricao, String unidade, BigDecimal quantidade,
                    BigDecimal valorTotal, boolean unidadeTributavel, Comercial comercial) {
            this(numero, codigoBarras, descricao, unidade, quantidade, valorTotal, unidadeTributavel, comercial,
                    List.of());
        }
    }

    /**
     * Grupo {@code rastro} do item (F17.2): {@code nLote}, {@code qLote} (na unidade comercial do item) e {@code dVal}.
     */
    public record Rastro(String lote, BigDecimal quantidade, LocalDate validade) {
    }

    /**
     * Dados comerciais do item: {@code cProd} (vazio se faltar), {@code uCom}, {@code qCom} e {@code vUnCom} (nulo se
     * faltar).
     */
    public record Comercial(String codigoProduto, String unidade, BigDecimal quantidade, BigDecimal valorUnitario) {
    }

    /** Duplicata da cobrança ({@code cobr/dup}). */
    public record Duplicata(String numero, LocalDate vencimento, BigDecimal valor) {
    }

    /**
     * @param chave      chave de acesso (44 dígitos), se houver
     * @param duplicatas as de {@code cobr/dup} que têm vencimento; vazio sem cobrança
     */
    public record Nota(String chave, Emitente emitente, String numero, LocalDate emissao, BigDecimal valorTotal,
                       List<Item> itens, List<Duplicata> duplicatas) {
    }

    /** @throws NegocioException o arquivo não é o XML de uma NF-e, ou falta um dado obrigatório */
    public static Nota ler(byte[] xml) {
        Document documento = documento(xml);
        Element inf = primeiro(documento.getDocumentElement(), "infNFe");
        if (inf == null) {
            throw new NegocioException("O arquivo não é o XML de uma NF-e (não tem o grupo infNFe).");
        }
        Element ide = obrigatorio(inf, "ide");
        Element emit = obrigatorio(inf, "emit");
        String documentoEmitente = texto(emit, "CNPJ");
        if (documentoEmitente.isEmpty()) {
            documentoEmitente = texto(emit, "CPF");
        }
        if (documentoEmitente.isEmpty()) {
            throw new NegocioException("O XML da NF-e não traz o CNPJ nem o CPF do emitente.");
        }
        Element endereco = primeiro(emit, "enderEmit");
        Emitente emitente = new Emitente(documentoEmitente.toUpperCase(Locale.ROOT), texto(emit, "xNome"),
                texto(emit, "xFant"), endereco == null ? "" : texto(endereco, "xMun"),
                endereco == null ? "" : texto(endereco, "UF"), endereco == null ? "" : texto(endereco, "fone"));
        Element total = primeiro(inf, "ICMSTot");

        List<Item> itens = new ArrayList<>();
        NodeList dets = inf.getElementsByTagNameNS("*", "det");
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = obrigatorio(det, "prod");
            itens.add(item(det.getAttribute("nItem").isBlank() ? i + 1 : inteiro(det.getAttribute("nItem")), prod));
        }
        if (itens.isEmpty()) {
            throw new NegocioException("O XML da NF-e não tem itens.");
        }
        String chave = inf.getAttribute("Id").replaceFirst("^NFe", "");
        return new Nota(chave.isBlank() ? null : chave, emitente, texto(ide, "nNF"), emissao(ide),
                total == null ? null : numero(total, "vNF"), List.copyOf(itens), duplicatas(inf));
    }

    /** {@code dVenc} é opcional no XML: duplicata sem vencimento não dá parcela (o aviso da soma aparece na entrada). */
    private static List<Duplicata> duplicatas(Element inf) {
        Element cobranca = primeiro(inf, "cobr");
        if (cobranca == null) {
            return List.of();
        }
        List<Duplicata> duplicatas = new ArrayList<>();
        NodeList dups = cobranca.getElementsByTagNameNS("*", "dup");
        for (int i = 0; i < dups.getLength(); i++) {
            Element dup = (Element) dups.item(i);
            String vencimento = texto(dup, "dVenc");
            if (vencimento.isEmpty()) {
                continue;
            }
            try {
                duplicatas.add(new Duplicata(texto(dup, "nDup"), LocalDate.parse(vencimento), numero(dup, "vDup")));
            } catch (DateTimeParseException e) {
                throw new NegocioException("Vencimento inválido na duplicata do XML da NF-e: \"" + vencimento + "\".");
            }
        }
        return List.copyOf(duplicatas);
    }

    private static Item item(int numero, Element prod) {
        String eanComercial = ean(texto(prod, "cEAN"));
        String eanTributavel = ean(texto(prod, "cEANTrib"));
        String unidadeComercial = texto(prod, "uCom");
        String unidadeTributavel = texto(prod, "uTrib");
        BigDecimal valor = numero(prod, "vProd");
        Comercial comercial = new Comercial(texto(prod, "cProd"), unidadeComercial, numero(prod, "qCom"),
                texto(prod, "vUnCom").isEmpty() ? null : numero(prod, "vUnCom"));
        boolean tributavel = eanTributavel != null && !unidadeTributavel.isEmpty()
                && !unidadeTributavel.equalsIgnoreCase(unidadeComercial);
        List<Rastro> rastros = rastros(prod);
        if (tributavel) {
            return new Item(numero, eanTributavel, texto(prod, "xProd"), unidadeTributavel, numero(prod, "qTrib"),
                    valor, true, comercial, rastros);
        }
        return new Item(numero, eanComercial != null ? eanComercial : eanTributavel, texto(prod, "xProd"),
                unidadeComercial, comercial.quantidade(), valor, false, comercial, rastros);
    }

    /** Grupos {@code rastro} filhos diretos de {@code prod} (NF-e 4.00), na ordem da nota. */
    private static List<Rastro> rastros(Element prod) {
        List<Rastro> rastros = new ArrayList<>();
        for (Node n = prod.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && "rastro".equals(e.getLocalName())) {
                String validade = texto(e, "dVal");
                try {
                    rastros.add(new Rastro(texto(e, "nLote"), numero(e, "qLote"),
                            validade.isEmpty() ? null : LocalDate.parse(validade)));
                } catch (DateTimeParseException ex) {
                    throw new NegocioException("Validade de lote inválida no XML da NF-e: \"" + validade + "\".");
                }
            }
        }
        return rastros;
    }

    /** "SEM GTIN", vazio ou código com dígito verificador errado → nulo. */
    static String ean(String texto) {
        String codigo = texto == null ? "" : texto.strip();
        return !codigo.isEmpty() && Gtin.valido(codigo) ? codigo : null;
    }

    /** {@code dhEmi} (NF-e 3.10 e 4.00: data e hora com fuso) ou {@code dEmi} (versões antigas). */
    private static LocalDate emissao(Element ide) {
        String dataHora = texto(ide, "dhEmi");
        try {
            if (!dataHora.isEmpty()) {
                return OffsetDateTime.parse(dataHora).toLocalDate();
            }
            String data = texto(ide, "dEmi");
            return data.isEmpty() ? null : LocalDate.parse(data);
        } catch (DateTimeParseException e) {
            throw new NegocioException("Data de emissão inválida no XML da NF-e: \"" + dataHora + "\".");
        }
    }

    private static Document documento(byte[] xml) {
        try {
            DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
            fabrica.setNamespaceAware(true);
            fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            fabrica.setXIncludeAware(false);
            fabrica.setExpandEntityReferences(false);
            DocumentBuilder construtor = fabrica.newDocumentBuilder();
            construtor.setErrorHandler(null);
            return construtor.parse(new ByteArrayInputStream(xml));
        } catch (SAXException | IOException e) {
            throw new NegocioException("O arquivo não é um XML válido de NF-e.");
        } catch (ParserConfigurationException e) {
            throw new ErroInesperadoException(e);
        }
    }

    /** Primeiro descendente com o nome local (qualquer namespace), ou nulo. */
    private static Element primeiro(Element pai, String nome) {
        if (nome.equals(pai.getLocalName())) {
            return pai;
        }
        NodeList nos = pai.getElementsByTagNameNS("*", nome);
        return nos.getLength() == 0 ? null : (Element) nos.item(0);
    }

    private static Element obrigatorio(Element pai, String nome) {
        Element e = primeiro(pai, nome);
        if (e == null) {
            throw new NegocioException("O XML da NF-e está incompleto: falta o grupo " + nome + ".");
        }
        return e;
    }

    /** Texto do filho direto (sem espaços nas pontas), ou vazio. */
    private static String texto(Element pai, String nome) {
        for (Node n = pai.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && nome.equals(e.getLocalName())) {
                return e.getTextContent().strip();
            }
        }
        return "";
    }

    private static BigDecimal numero(Element pai, String nome) {
        String valor = texto(pai, nome);
        try {
            return new BigDecimal(valor);
        } catch (NumberFormatException e) {
            throw new NegocioException("Valor inválido no XML da NF-e (" + nome + " = \"" + valor + "\").");
        }
    }

    private static int inteiro(String texto) {
        try {
            return Integer.parseInt(texto.strip());
        } catch (NumberFormatException e) {
            throw new NegocioException("Número de item inválido no XML da NF-e: \"" + texto + "\".");
        }
    }
}
