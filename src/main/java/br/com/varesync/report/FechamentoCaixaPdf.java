package br.com.varesync.report;

import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.service.ErroInesperadoException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * PDF do fechamento de caixa (R11), com o layout próprio de {@code reports/fechamento-caixa.jrxml} (7.4) e as
 * linhas de {@link FechamentoCaixaExportacao}. O JRXML é compilado uma vez, no primeiro uso.
 */
public class FechamentoCaixaPdf {

    static final String MODELO = "/reports/fechamento-caixa.jrxml";

    private JasperReport compilado;

    /** @return o PDF em bytes */
    public byte[] gerar(RelatorioFechamento relatorio, Emissao emissao) {
        try {
            return JasperExportManager.exportReportToPdf(preencher(relatorio, emissao));
        } catch (JRException e) {
            throw new ErroInesperadoException(e);
        }
    }

    JasperPrint preencher(RelatorioFechamento relatorio, Emissao emissao) throws JRException {
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("LOJA", emissao.loja());
        parametros.put("TITULO", FechamentoCaixaExportacao.titulo(relatorio));
        parametros.put("EMISSAO", emissao.texto());
        List<Map<String, ?>> linhas = FechamentoCaixaExportacao.linhas(relatorio, false).stream()
                .map(FechamentoCaixaPdf::campos).toList();
        return JasperFillManager.fillReport(modelo(), parametros, new JRMapCollectionDataSource(linhas));
    }

    public void salvar(RelatorioFechamento relatorio, Emissao emissao, Path destino) {
        try {
            Files.write(destino, gerar(relatorio, emissao));
        } catch (IOException e) {
            throw new ErroInesperadoException(e);
        }
    }

    private synchronized JasperReport modelo() throws JRException {
        if (compilado == null) {
            compilado = ComprovantePdf.compilar(MODELO);
        }
        return compilado;
    }

    private static Map<String, ?> campos(FechamentoCaixaExportacao.Linha linha) {
        Map<String, String> campos = new HashMap<>();
        campos.put("estilo", linha.estilo().name());
        for (int i = 0; i < 5; i++) {
            campos.put("c" + i, linha.coluna(i));
        }
        return campos;
    }
}
