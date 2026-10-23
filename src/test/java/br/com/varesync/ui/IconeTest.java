package br.com.varesync.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Confere, sem subir o JavaFX, os arquivos do ícone das janelas e do VareSync.exe. */
class IconeTest {

    @ParameterizedTest
    @ValueSource(ints = {16, 32, 48, 64, 128, 256})
    void pngDeCadaTamanhoExisteComOTamanhoDoNome(int tamanho) throws Exception {
        try (InputStream entrada = IconeTest.class.getResourceAsStream("/img/varesync-" + tamanho + ".png")) {
            assertThat(entrada).isNotNull();
            BufferedImage imagem = ImageIO.read(entrada);
            assertThat(imagem.getWidth()).isEqualTo(tamanho);
            assertThat(imagem.getHeight()).isEqualTo(tamanho);
        }
    }

    @Test
    void icoDoEmpacotamentoTemCabecalhoDeIconeComVariosTamanhos() throws Exception {
        byte[] ico = Files.readAllBytes(Path.of("src/main/resources/img/varesync.ico"));
        ByteBuffer cabecalho = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(cabecalho.getShort()).isZero();
        assertThat(cabecalho.getShort()).isEqualTo((short) 1);
        assertThat(cabecalho.getShort()).isGreaterThanOrEqualTo((short) 6);
    }
}
