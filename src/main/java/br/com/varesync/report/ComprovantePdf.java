package br.com.varesync.report;

import br.com.varesync.model.ComprovanteImpresso;
import br.com.varesync.model.LinhaComprovante;
import br.com.varesync.service.ErroInesperadoException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * PDF do comprovante não fiscal (6.6), com o layout de {@code reports/comprovante.jrxml} (bobina de 80 mm) e as
 * mesmas linhas da tela ({@link ComprovanteImpresso#linhas()}), para o comprovante da venda e o da devolução. O
 * JRXML é compilado uma vez, no primeiro uso; a primeira geração demora mais, por isso a tela chama em {@code Task}.
 */
public class ComprovantePdf {

    static final String MODELO = "/reports/comprovante.jrxml";

    private JasperReport compilado;

    /** @return o PDF em bytes */
    public byte[] gerar(ComprovanteImpresso comprovante) {
        try {
            return JasperExportManager.exportReportToPdf(preencher(comprovante));
        } catch (JRException e) {
            throw new ErroInesperadoException(e);
        }
    }

    /** O comprovante preenchido no layout de 80 mm (o mesmo para o PDF e para a impressora). */
    public JasperPrint preencher(ComprovanteImpresso comprovante) {
        List<Map<String, ?>> linhas = comprovante.linhas().stream().map(ComprovantePdf::campos).toList();
        try {
            return JasperFillManager.fillReport(modelo(), new HashMap<>(), new JRMapCollectionDataSource(linhas));
        } catch (JRException e) {
            throw new ErroInesperadoException(e);
        }
    }

    public void salvar(ComprovanteImpresso comprovante, Path destino) {
        try {
            Files.write(destino, gerar(comprovante));
        } catch (IOException e) {
            throw new ErroInesperadoException(e);
        }
    }

    private synchronized JasperReport modelo() throws JRException {
        if (compilado == null) {
            compilado = compilar(MODELO);
        }
        return compilado;
    }

    /** Compila um JRXML do classpath (também usado pelo teste que compila todos os modelos). */
    static JasperReport compilar(String recurso) throws JRException {
        try (InputStream jrxml = ComprovantePdf.class.getResourceAsStream(recurso)) {
            if (jrxml == null) {
                throw new JRException("Modelo não encontrado: " + recurso);
            }
            return JasperCompileManager.compileReport(jrxml);
        } catch (IOException e) {
            throw new JRException("Falha ao ler " + recurso, e);
        }
    }

    private static Map<String, ?> campos(LinhaComprovante linha) {
        return Map.of("estilo", linha.estilo().name(), "esquerda", linha.esquerda(), "direita", linha.direita());
    }
}
