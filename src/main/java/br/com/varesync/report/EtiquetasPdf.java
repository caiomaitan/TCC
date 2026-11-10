package br.com.varesync.report;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.model.TipoCodigoBarras;
import br.com.varesync.service.ErroInesperadoException;
import br.com.varesync.service.EtiquetaService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Formatos;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.EAN13Writer;
import com.google.zxing.oned.EAN8Writer;
import com.google.zxing.oned.UPCAWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * Etiquetas de gôndola em PDF A4 (F18.1): {@code etiqueta.colunas} × {@code etiqueta.linhas} etiquetas por folha,
 * começando na posição escolhida da primeira folha (para aproveitar uma folha já usada). Cada etiqueta tem o nome, o
 * preço em destaque, a unidade, a data da impressão, o "DE R$ X POR" e o "Oferta válida até" na promoção, o preço por
 * kg ou litro do produto com conteúdo líquido e o código de barras desenhado pelo ZXing (EAN-13, EAN-8 ou UPC-A);
 * sem um desses, o código interno; no pesável, o PLU. Com a F20.3, a linha "A partir de N un: R$ Z" da faixa de
 * atacado.
 *
 * <p>O layout é um JRXML gerado aqui (formato do JasperReports 7, como o {@link RelatorioPdf}), desenhado para uma
 * etiqueta de 185 × 100 pontos (3 × 8 na folha) e escalado para o tamanho de cada célula.
 */
public class EtiquetasPdf {

    static final int PAGINA_LARGURA = 595;
    static final int PAGINA_ALTURA = 842;
    static final int MARGEM = 20;
    private static final double BASE_LARGURA = 185;
    private static final double BASE_ALTURA = 100;
    /** Pixels por módulo (barra mais fina) na imagem do código de barras. */
    private static final int PIXELS_MODULO = 4;
    private static final int PIXELS_ALTURA = 80;

    private final Map<String, JasperReport> compilados = new ConcurrentHashMap<>();

    public byte[] gerar(List<Etiqueta> etiquetas, Configuracoes.Etiquetas layout, int posicaoInicial) {
        try {
            return JasperExportManager.exportReportToPdf(preencher(etiquetas, layout, posicaoInicial));
        } catch (JRException e) {
            throw new ErroInesperadoException(e);
        }
    }

    public void salvar(List<Etiqueta> etiquetas, Configuracoes.Etiquetas layout, int posicaoInicial, Path destino) {
        byte[] pdf = gerar(etiquetas, layout, posicaoInicial);
        try {
            Files.write(destino, pdf);
        } catch (IOException e) {
            throw new ErroInesperadoException(e);
        }
    }

    /** Folhas usadas: as posições puladas da primeira folha também contam. */
    public static int folhas(int etiquetas, Configuracoes.Etiquetas layout, int posicaoInicial) {
        int ocupadas = posicaoInicial - 1 + etiquetas;
        return Math.max(1, (ocupadas + layout.porFolha() - 1) / layout.porFolha());
    }

    /** As etiquetas preenchidas, antes de virar PDF (os testes as desenham para ler o código de barras). */
    JasperPrint preencher(List<Etiqueta> etiquetas, Configuracoes.Etiquetas layout, int posicaoInicial)
            throws JRException {
        EtiquetaService.conferir(etiquetas);
        if (posicaoInicial < 1 || posicaoInicial > layout.porFolha()) {
            throw new NegocioException("A posição inicial deve estar entre 1 e " + layout.porFolha() + " (a folha tem "
                    + layout.colunas() + " × " + layout.linhas() + " etiquetas).");
        }
        List<Map<String, ?>> linhas = new ArrayList<>();
        for (int i = 1; i < posicaoInicial; i++) {
            Map<String, Object> vazia = new HashMap<>();
            vazia.put("vazia", Boolean.TRUE);
            linhas.add(vazia);
        }
        Map<String, byte[]> imagens = new HashMap<>();
        for (Etiqueta e : etiquetas) {
            Map<String, Object> campos = campos(e, imagens);
            for (int i = 0; i < e.quantidade(); i++) {
                linhas.add(campos);
            }
        }
        return JasperFillManager.fillReport(compilar(jrxml(layout.colunas(), layout.linhas())), new HashMap<>(),
                new JRMapCollectionDataSource(linhas));
    }

    static Map<String, Object> campos(Etiqueta e, Map<String, byte[]> imagens) {
        Map<String, Object> c = new HashMap<>();
        c.put("vazia", Boolean.FALSE);
        c.put("nome", e.nome());
        c.put("preco", Formatos.moeda(e.preco()));
        c.put("unidade", unidade(e));
        c.put("promo", e.promocional() ? "DE " + Formatos.moeda(e.precoDe()) + " POR" : null);
        String oferta = e.promocional() ? "Oferta válida até " + Formatos.data(e.fimPromocao()) : null;
        String atacado = e.temAtacado() ? e.textoAtacado() : null;
        BigDecimal porMedida = e.precoPorMedida();
        String medida = porMedida == null ? null
                : e.conteudo().descricao() + " · " + Formatos.moeda(porMedida) + " o " + e.conteudo().unidade().medida();
        // Duas linhas abaixo do preço (F20.3): a de cima (normal) e a de baixo (negrito). A linha de atacado vai para
        // a de baixo, junto com a oferta; sem o preço por medida, e com as duas, a de atacado sobe.
        if (medida == null && atacado != null && oferta != null) {
            medida = atacado;
        } else if (atacado != null) {
            oferta = oferta == null ? atacado : atacado + " · " + oferta;
        }
        c.put("oferta", oferta);
        c.put("medida", medida);
        if (e.codigoBarras() != null) {
            c.put("barras", imagens.computeIfAbsent(e.codigoBarras(), EtiquetasPdf::png));
            c.put("legenda", e.codigoBarras());
        } else {
            c.put("legenda", e.plu() != null ? "PLU " + e.plu() : "Código " + e.produtoId());
        }
        c.put("data", Formatos.data(e.data()));
        return c;
    }

    private static String unidade(Etiqueta e) {
        if ("KG".equalsIgnoreCase(e.unidade())) {
            return "o kg";
        }
        if ("L".equalsIgnoreCase(e.unidade())) {
            return "o litro";
        }
        return e.unidade();
    }


    /** O código de barras em PNG (sem perda). */
    static byte[] png(String codigo) {
        java.io.ByteArrayOutputStream saida = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(desenhar(codigo), "png", saida);
        } catch (IOException e) {
            throw new ErroInesperadoException(e);
        }
        return saida.toByteArray();
    }
    /** O código de barras como imagem preto e branco, com a margem branca padrão de cada formato. */
    static BufferedImage desenhar(String codigo) {
        TipoCodigoBarras tipo = TipoCodigoBarras.de(codigo)
                .orElseThrow(() -> new IllegalArgumentException("Código sem desenho: " + codigo));
        BitMatrix matriz = switch (tipo) {
            case EAN_13 -> new EAN13Writer().encode(codigo, BarcodeFormat.EAN_13, 0, 1);
            case EAN_8 -> new EAN8Writer().encode(codigo, BarcodeFormat.EAN_8, 0, 1);
            case UPC_A -> new UPCAWriter().encode(codigo, BarcodeFormat.UPC_A, 0, 1);
        };
        int modulos = matriz.getWidth();
        BufferedImage imagem = new BufferedImage(modulos * PIXELS_MODULO, PIXELS_ALTURA, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < imagem.getWidth(); x++) {
            int cor = matriz.get(x / PIXELS_MODULO, 0) ? 0x000000 : 0xFFFFFF;
            for (int y = 0; y < PIXELS_ALTURA; y++) {
                imagem.setRGB(x, y, cor);
            }
        }
        return imagem;
    }

    private JasperReport compilar(String jrxml) throws JRException {
        JasperReport pronto = compilados.get(jrxml);
        if (pronto == null) {
            pronto = JasperCompileManager.compileReport(
                    new ByteArrayInputStream(jrxml.getBytes(StandardCharsets.UTF_8)));
            compilados.put(jrxml, pronto);
        }
        return pronto;
    }

    /** JRXML da folha: colunas lado a lado (ordem horizontal), uma etiqueta por registro. */
    static String jrxml(int colunas, int linhas) {
        int util = PAGINA_LARGURA - 2 * MARGEM;
        int largura = util / colunas;
        int altura = (PAGINA_ALTURA - 2 * MARGEM) / linhas;
        double sx = largura / BASE_LARGURA;
        double sy = altura / BASE_ALTURA;
        double sf = Math.min(sx, sy);
        Escala e = new Escala(sx, sy, sf);

        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<jasperReport name=\"etiquetas\" language=\"java\" pageWidth=\"").append(PAGINA_LARGURA)
                .append("\" pageHeight=\"").append(PAGINA_ALTURA).append("\" columnCount=\"").append(colunas)
                .append("\" printOrder=\"Horizontal\" columnWidth=\"").append(largura)
                .append("\" columnSpacing=\"0\" leftMargin=\"").append(MARGEM).append("\" rightMargin=\"")
                .append(MARGEM).append("\" topMargin=\"").append(MARGEM).append("\" bottomMargin=\"").append(MARGEM)
                .append("\" whenNoDataType=\"NoPages\" uuid=\"5d7c1e2a-8b4f-4c3a-9e61-2f0a7b9c4d18\">\n");
        s.append("    <style name=\"Base\" default=\"true\" fontName=\"DejaVu Sans\" fontSize=\"8\"/>\n");
        s.append("    <field name=\"vazia\" class=\"java.lang.Boolean\"/>\n");
        for (String campo : List.of("nome", "preco", "unidade", "promo", "oferta", "medida", "legenda", "data")) {
            s.append("    <field name=\"").append(campo).append("\" class=\"java.lang.String\"/>\n");
        }
        // PNG em bytes: com java.awt.Image o JasperReports grava JPEG no PDF, que borra as barras.
        s.append("    <field name=\"barras\" class=\"java.lang.Object\"/>\n");
        s.append("    <detail>\n        <band height=\"").append(altura).append("\" splitType=\"Prevent\">\n");
        String visivel = "!$F{vazia}";
        s.append("            <element kind=\"rectangle\" x=\"0\" y=\"0\" width=\"").append(largura)
                .append("\" height=\"").append(altura).append("\">\n")
                .append("                <printWhenExpression><![CDATA[").append(visivel)
                .append("]]></printWhenExpression>\n")
                .append("                <pen lineWidth=\"0.25\" lineStyle=\"Dotted\" lineColor=\"#A0A0A0\"/>\n")
                .append("            </element>\n");
        texto(s, e, 5, 3, 175, 23, 9, true, "Left", "nome", true);
        texto(s, e, 5, 26, 175, 10, 7, true, "Left", "promo", false);
        texto(s, e, 5, 35, 132, 26, 20, true, "Left", "preco", true);
        texto(s, e, 137, 46, 43, 12, 8, false, "Right", "unidade", true);
        texto(s, e, 5, 60, 175, 9, 6.5, false, "Left", "medida", true);
        texto(s, e, 5, 68, 175, 9, 6.5, true, "Left", "oferta", true);
        s.append("            <element kind=\"image\" x=\"").append(e.x(5)).append("\" y=\"").append(e.y(77))
                .append("\" width=\"").append(e.x(100)).append("\" height=\"").append(e.y(15))
                .append("\" scaleImage=\"FillFrame\">\n")
                .append("                <printWhenExpression><![CDATA[$F{barras} != null]]></printWhenExpression>\n")
                .append("                <expression><![CDATA[$F{barras}]]></expression>\n")
                .append("            </element>\n");
        texto(s, e, 5, 92, 100, 7, 5.5, false, "Center", "legenda", true);
        texto(s, e, 110, 90, 70, 8, 6, false, "Right", "data", false);
        s.append("        </band>\n    </detail>\n");
        s.append("</jasperReport>\n");
        return s.toString();
    }

    private record Escala(double sx, double sy, double sf) {
        int x(double v) {
            return (int) Math.round(v * sx);
        }

        int y(double v) {
            return (int) Math.round(v * sy);
        }

        String fonte(double tamanho) {
            return String.format(Locale.ROOT, "%.1f", Math.max(4, tamanho * sf));
        }
    }

    private static void texto(StringBuilder s, Escala e, double x, double y, double w, double h, double fonte,
                              boolean negrito, String alinhamento, String campo, boolean encolhe) {
        s.append("            <element kind=\"textField\" x=\"").append(e.x(x)).append("\" y=\"").append(e.y(y))
                .append("\" width=\"").append(e.x(w)).append("\" height=\"").append(e.y(h))
                .append("\" fontSize=\"").append(e.fonte(fonte)).append("\"").append(negrito ? " bold=\"true\"" : "")
                .append(" hTextAlign=\"").append(alinhamento).append("\" vTextAlign=\"Middle\" blankWhenNull=\"true\"")
                .append(encolhe ? " textAdjust=\"ScaleFont\"" : "").append(">\n")
                .append("                <printWhenExpression><![CDATA[!$F{vazia}]]></printWhenExpression>\n")
                .append("                <expression><![CDATA[$F{").append(campo).append("}]]></expression>\n")
                .append("            </element>\n");
    }
}
