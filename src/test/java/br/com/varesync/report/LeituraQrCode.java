package br.com.varesync.report;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;

/**
 * Lê de volta, pelo ZXing, o QR Code da página que o JasperReports desenhou (F23.7): a página inteira é desenhada numa
 * imagem (como na impressão) e o QR Code é procurado nela.
 */
public final class LeituraQrCode {

    private LeituraQrCode() {
    }

    /** O conteúdo do QR Code da 1ª página. */
    public static String lerDaPagina(JasperPrint impressao) throws JRException, NotFoundException {
        BufferedImage pagina = (BufferedImage) JasperPrintManager.printPageToImage(impressao, 0, 4f);
        return ler(pagina);
    }

    public static String ler(BufferedImage imagem) throws NotFoundException {
        int[] pixels = imagem.getRGB(0, 0, imagem.getWidth(), imagem.getHeight(), null, 0, imagem.getWidth());
        RGBLuminanceSource fonte = new RGBLuminanceSource(imagem.getWidth(), imagem.getHeight(), pixels);
        Map<DecodeHintType, Object> dicas = new EnumMap<>(DecodeHintType.class);
        dicas.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        dicas.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        dicas.put(DecodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(fonte)), dicas).getText();
    }
}
