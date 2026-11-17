package br.com.varesync.report;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * QR Code do DANFE NFC-e (F23.7), pelo ZXing: correção de erro M e UTF-8, como pede o manual. A imagem sai com
 * {@link #PIXELS_POR_MODULO} pixels por módulo (nítida na impressão) e a margem de 4 módulos; o tamanho no papel é o do
 * JRXML (100 pt, cerca de 35 mm, acima dos 25 mm do manual).
 */
public final class QrCodeImagem {

    static final int PIXELS_POR_MODULO = 6;
    private static final int PRETO = 0xFF000000;
    private static final int BRANCO = 0xFFFFFFFF;

    private QrCodeImagem() {
    }

    public static BufferedImage gerar(String conteudo) {
        BitMatrix matriz;
        try {
            matriz = new QRCodeWriter().encode(conteudo, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                    EncodeHintType.MARGIN, 4));
        } catch (WriterException e) {
            throw new IllegalArgumentException("QR Code não pôde ser gerado", e);
        }
        int lado = matriz.getWidth() * PIXELS_POR_MODULO;
        BufferedImage imagem = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < lado; y++) {
            for (int x = 0; x < lado; x++) {
                imagem.setRGB(x, y, matriz.get(x / PIXELS_POR_MODULO, y / PIXELS_POR_MODULO) ? PRETO : BRANCO);
            }
        }
        return imagem;
    }
}
