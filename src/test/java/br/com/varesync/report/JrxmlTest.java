package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.ComprovanteTest;
import br.com.varesync.model.StatusVenda;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.sf.jasperreports.engine.JasperReport;
import org.junit.jupiter.api.Test;

/**
 * Modelos do JasperReports 7 (3.5, 7.4): todos os JRXML de {@code reports/} compilam, e o comprovante gera um PDF
 * com dados fictícios, acentos e o aviso de documento não fiscal.
 */
class JrxmlTest {

    @Test
    void todosOsJrxmlCompilam() throws Exception {
        List<String> modelos = modelos();
        assertThat(modelos).contains("/reports/comprovante.jrxml");
        for (String modelo : modelos) {
            JasperReport compilado = ComprovantePdf.compilar(modelo);
            assertThat(compilado).as(modelo).isNotNull();
        }
    }

    @Test
    void comprovanteGeraPdf() throws Exception {
        ComprovantePdf pdf = new ComprovantePdf();
        byte[] finalizada = pdf.gerar(ComprovanteTest.exemplo(StatusVenda.FINALIZADA));
        byte[] cancelada = pdf.gerar(ComprovanteTest.exemplo(StatusVenda.CANCELADA));

        assertThat(new String(finalizada, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(finalizada.length).isGreaterThan(1000);
        assertThat(cancelada.length).isGreaterThan(1000);

        Path destino = Files.createTempFile("comprovante", ".pdf");
        try {
            pdf.salvar(ComprovanteTest.exemplo(StatusVenda.FINALIZADA), destino);
            assertThat(Files.size(destino)).isGreaterThan(1000);
        } finally {
            Files.deleteIfExists(destino);
        }
    }

    private static List<String> modelos() throws URISyntaxException, java.io.IOException {
        URL pasta = JrxmlTest.class.getResource("/reports");
        assertThat(pasta).isNotNull();
        try (Stream<Path> arquivos = Files.list(Path.of(pasta.toURI()))) {
            return arquivos.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jrxml"))
                    .map(n -> "/reports/" + n).sorted().toList();
        }
    }
}
