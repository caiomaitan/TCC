package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.ConteudoLiquido;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.model.UnidadeConteudo;
import br.com.varesync.service.NegocioException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.lowagie.text.pdf.PRStream;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfNumber;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import org.junit.jupiter.api.Test;

/**
 * PDF das etiquetas de gôndola (F18.1): o código de barras desenhado no PDF é lido de volta pelo ZXing e confere com o
 * EAN do produto (aceite "Código de barras"); DE/POR, preço por kg, posição inicial e folhas. Sem banco.
 */
class EtiquetasPdfTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 6);
    private static final Configuracoes.Etiquetas FOLHA = Configuracoes.Etiquetas.PADRAO;

    static Etiqueta etiqueta(long id, String nome, String preco, String barras) {
        return new Etiqueta(id, nome, "UN", new BigDecimal(preco), null, null, null, barras, null,
                ConteudoLiquido.SEM, HOJE, null, true, 1);
    }

    @Test
    void codigoDeBarrasDoPdfELidoDeVoltaEConfereComOEan() throws Exception {
        List<Etiqueta> etiquetas = List.of(
                etiqueta(1, "Café Torrado 500 g", "18.90", "7891000315507"),
                etiqueta(2, "Bala de Goma", "1.50", "12345670"),
                etiqueta(3, "Molho Importado", "22.00", "036000291452"));
        byte[] pdf = new EtiquetasPdf().gerar(etiquetas, FOLHA, 1);

        List<String> lidos = new ArrayList<>();
        PdfReader leitor = new PdfReader(pdf);
        for (BufferedImage imagem : imagensDaPagina(leitor, 1)) {
            lidos.add(ler(imagem).getText());
        }
        assertThat(lidos).as("códigos lidos das imagens embutidas no PDF")
                .containsExactlyInAnyOrder("7891000315507", "12345670", "036000291452");
        String texto = new PdfTextExtractor(leitor).getTextFromPage(1);
        assertThat(texto).contains("Café Torrado 500 g", "R$ 18,90", "7891000315507", "06/11/2026");
        leitor.close();
    }

    @Test
    void codigoDesenhadoNaFolhaTambemELido() throws Exception {
        Etiqueta cafe = etiqueta(1, "Café Torrado 500 g", "18.90", "7891000315507");
        JasperPrint folha = new EtiquetasPdf().preencher(List.of(cafe), FOLHA, 1);
        BufferedImage pagina = (BufferedImage) JasperPrintManager.printPageToImage(folha, 0, 3f);
        BufferedImage etiqueta = pagina.getSubimage(0, 0, pagina.getWidth() / 3, pagina.getHeight() / 8);
        Result lido = ler(etiqueta);
        assertThat(lido.getText()).isEqualTo("7891000315507");
        assertThat(lido.getBarcodeFormat()).isEqualTo(BarcodeFormat.EAN_13);
    }

    @Test
    void promocaoSaiComoDePorEOfertaValidaAte() throws Exception {
        Etiqueta promo = new Etiqueta(5, "Refrigerante Cola 2 L", "UN", new BigDecimal("7.99"),
                new BigDecimal("9.99"), 10L, LocalDate.of(2026, 11, 13), null, null, ConteudoLiquido.SEM, HOJE, null,
                true, 1);
        String texto = textoDaPagina(new EtiquetasPdf().gerar(List.of(promo), FOLHA, 1));
        assertThat(texto).contains("DE R$ 9,99 POR", "R$ 7,99", "Oferta válida até 13/11/2026", "Código 5");
    }

    /** Aceite "Etiqueta" da F20: a faixa de R$ 4,99 a partir de 6 un. */
    @Test
    void faixaDeAtacadoSaiComoAPartirDe6Un() throws Exception {
        Etiqueta refri = new Etiqueta(9, "Refrigerante Cola 2 L", "UN", new BigDecimal("5.49"), null, null, null,
                null, null, ConteudoLiquido.SEM, HOJE, null, true, 1, new BigDecimal("6.000"), new BigDecimal("4.99"));
        assertThat(refri.textoAtacado()).isEqualTo("A partir de 6 un: R$ 4,99");
        assertThat(textoDaPagina(new EtiquetasPdf().gerar(List.of(refri), FOLHA, 1)))
                .contains("R$ 5,49", "A partir de 6 un: R$ 4,99");
    }

    @Test
    void faixaComPromocaoEConteudoCabeNasDuasLinhas() {
        Etiqueta tudo = new Etiqueta(10, "Biscoito Recheado", "UN", new BigDecimal("7.99"), new BigDecimal("8.99"),
                3L, LocalDate.of(2026, 11, 13), null, null, new ConteudoLiquido(new BigDecimal("500"),
                UnidadeConteudo.G), HOJE, null, true, 1, new BigDecimal("6"), new BigDecimal("6.99"));
        Map<String, Object> c = EtiquetasPdf.campos(tudo, new java.util.HashMap<>());
        assertThat(c.get("medida")).isEqualTo("500 g · R$ 15,98 o kg");
        assertThat(c.get("oferta")).isEqualTo("A partir de 6 un: R$ 6,99 · Oferta válida até 13/11/2026");

        Etiqueta semConteudo = new Etiqueta(10, "Biscoito Recheado", "UN", new BigDecimal("7.99"),
                new BigDecimal("8.99"), 3L, LocalDate.of(2026, 11, 13), null, null, ConteudoLiquido.SEM, HOJE, null,
                true, 1, new BigDecimal("6"), new BigDecimal("6.99"));
        c = EtiquetasPdf.campos(semConteudo, new java.util.HashMap<>());
        assertThat(c.get("medida")).isEqualTo("A partir de 6 un: R$ 6,99");
        assertThat(c.get("oferta")).isEqualTo("Oferta válida até 13/11/2026");
    }

    @Test
    void precoPorKgDoAceite() throws Exception {
        Etiqueta arroz = new Etiqueta(7, "Biscoito Recheado", "UN", new BigDecimal("8.99"), null, null, null,
                null, null, new ConteudoLiquido(new BigDecimal("500"), UnidadeConteudo.G), HOJE, null, true, 1);
        String texto = textoDaPagina(new EtiquetasPdf().gerar(List.of(arroz), FOLHA, 1));
        assertThat(texto).contains("500 g · R$ 17,98 o kg");
    }

    @Test
    void pesavelMostraOPluSemCodigoDeBarras() throws Exception {
        Etiqueta queijo = new Etiqueta(8, "Queijo Mussarela", "KG", new BigDecimal("39.90"), null, null, null, null,
                123, ConteudoLiquido.SEM, HOJE, null, true, 1);
        byte[] pdf = new EtiquetasPdf().gerar(List.of(queijo), FOLHA, 1);
        assertThat(textoDaPagina(pdf)).contains("PLU 123", "R$ 39,90", "o kg");
        PdfReader leitor = new PdfReader(pdf);
        assertThat(imagensDaPagina(leitor, 1)).isEmpty();
        leitor.close();
    }

    @Test
    void posicaoInicialEQuantidadeDefinemAsFolhas() throws Exception {
        Etiqueta cafe = etiqueta(1, "Café", "18.90", "7891000315507").comQuantidade(5);
        JasperPrint uma = new EtiquetasPdf().preencher(List.of(cafe), FOLHA, 20);
        assertThat(uma.getPages()).as("posições 20 a 24 cabem na 1ª folha").hasSize(1);
        JasperPrint duas = new EtiquetasPdf().preencher(List.of(cafe), FOLHA, 21);
        assertThat(duas.getPages()).as("posições 21 a 25 passam para a 2ª folha").hasSize(2);
        assertThat(EtiquetasPdf.folhas(5, FOLHA, 21)).isEqualTo(2);
        assertThat(EtiquetasPdf.folhas(5, FOLHA, 20)).isEqualTo(1);
        assertThatThrownBy(() -> new EtiquetasPdf().preencher(List.of(cafe), FOLHA, 25))
                .isInstanceOf(NegocioException.class).hasMessageContaining("entre 1 e 24");
    }

    @Test
    void outrasFolhasCompilam() throws Exception {
        Etiqueta cafe = etiqueta(1, "Café Torrado 500 g", "18.90", "7891000315507");
        for (int[] f : new int[][] {{1, 1}, {2, 5}, {4, 10}, {6, 20}}) {
            Configuracoes.Etiquetas layout = new Configuracoes.Etiquetas(f[0], f[1], "", "");
            assertThat(new EtiquetasPdf().gerar(List.of(cafe), layout, 1)).isNotEmpty();
        }
    }

    // ----- apoio: leitura do PDF -----

    private static String textoDaPagina(byte[] pdf) throws Exception {
        PdfReader leitor = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(leitor).getTextFromPage(1);
        } finally {
            leitor.close();
        }
    }

    /** As imagens da página, decodificadas a partir dos bytes do PDF (cinza ou RGB, 1 ou 8 bits). */
    static List<BufferedImage> imagensDaPagina(PdfReader leitor, int pagina) throws Exception {
        List<BufferedImage> imagens = new ArrayList<>();
        PdfDictionary recursos = leitor.getPageN(pagina).getAsDict(PdfName.RESOURCES);
        PdfDictionary objetos = recursos == null ? null : recursos.getAsDict(PdfName.XOBJECT);
        if (objetos == null) {
            return imagens;
        }
        for (PdfName nome : objetos.getKeys()) {
            PdfObject objeto = PdfReader.getPdfObject(objetos.get(nome));
            if (!(objeto instanceof PRStream stream) || !PdfName.IMAGE.equals(stream.getAsName(PdfName.SUBTYPE))) {
                continue;
            }
            imagens.add(decodificar(stream));
        }
        return imagens;
    }

    private static BufferedImage decodificar(PRStream stream) throws Exception {
        int largura = ((PdfNumber) PdfReader.getPdfObject(stream.get(PdfName.WIDTH))).intValue();
        int altura = ((PdfNumber) PdfReader.getPdfObject(stream.get(PdfName.HEIGHT))).intValue();
        int bits = ((PdfNumber) PdfReader.getPdfObject(stream.get(PdfName.BITSPERCOMPONENT))).intValue();
        PdfObject espaco = PdfReader.getPdfObject(stream.get(PdfName.COLORSPACE));
        int componentes = PdfName.DEVICERGB.equals(espaco) ? 3 : 1;
        byte[] dados = PdfReader.getStreamBytes(stream);
        int porLinha = (largura * componentes * bits + 7) / 8;
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < altura; y++) {
            for (int x = 0; x < largura; x++) {
                int cinza;
                if (bits == 1) {
                    int b = dados[y * porLinha + x / 8] & 0xFF;
                    cinza = ((b >> (7 - x % 8)) & 1) == 1 ? 255 : 0;
                } else {
                    int i = y * porLinha + x * componentes;
                    cinza = dados[i] & 0xFF;
                }
                imagem.setRGB(x, y, (cinza << 16) | (cinza << 8) | cinza);
            }
        }
        return imagem;
    }

    private static Result ler(BufferedImage imagem) throws NotFoundException {
        int[] pixels = imagem.getRGB(0, 0, imagem.getWidth(), imagem.getHeight(), null, 0, imagem.getWidth());
        RGBLuminanceSource fonte = new RGBLuminanceSource(imagem.getWidth(), imagem.getHeight(), pixels);
        Map<DecodeHintType, Object> dicas = new EnumMap<>(DecodeHintType.class);
        dicas.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A));
        dicas.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(fonte)), dicas);
    }
}
