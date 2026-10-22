package br.com.varesync.report;

import br.com.varesync.model.Comprovante;
import br.com.varesync.service.ErroInesperadoException;
import br.com.varesync.service.NegocioException;
import java.util.function.Supplier;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.PrintRequestAttributeSet;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.JRPrintServiceExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimplePrintServiceExporterConfiguration;

/**
 * Impressão do comprovante não fiscal (6.6) na impressora padrão do Windows, sem a janela de impressão: o mesmo
 * layout de 80 mm do PDF ({@link ComprovantePdf#preencher}). Roda em {@code Task} (a impressora pode demorar).
 */
public class ImpressaoComprovante {

    /** Manda o comprovante preenchido para a impressora (separado para os testes não imprimirem). */
    @FunctionalInterface
    public interface Destino {
        void imprimir(JasperPrint impressao, PrintService impressora) throws JRException;
    }

    private final ComprovantePdf modelo;
    private final Supplier<PrintService> impressoraPadrao;
    private final Destino destino;

    public ImpressaoComprovante(ComprovantePdf modelo) {
        this(modelo, PrintServiceLookup::lookupDefaultPrintService, (p, i) -> exportar(p, i, null));
    }

    public ImpressaoComprovante(ComprovantePdf modelo, Supplier<PrintService> impressoraPadrao, Destino destino) {
        this.modelo = modelo;
        this.impressoraPadrao = impressoraPadrao;
        this.destino = destino;
    }

    /**
     * @return o nome da impressora usada
     * @throws NegocioException sem impressora padrão no sistema
     */
    public String imprimir(Comprovante comprovante) {
        PrintService impressora = impressoraPadrao.get();
        if (impressora == null) {
            throw new NegocioException("Nenhuma impressora padrão está configurada no Windows. Defina uma em "
                    + "Configurações do Windows › Impressoras ou salve o comprovante em PDF.");
        }
        try {
            destino.imprimir(modelo.preencher(comprovante), impressora);
        } catch (JRException e) {
            throw new ErroInesperadoException(e);
        }
        return impressora.getName();
    }

    /**
     * Envia à impressora sem mostrar diálogos.
     *
     * @param atributos atributos do trabalho de impressão (ex.: destino em arquivo); nulo = padrão
     */
    public static void exportar(JasperPrint impressao, PrintService impressora, PrintRequestAttributeSet atributos)
            throws JRException {
        JRPrintServiceExporter exportador = new JRPrintServiceExporter();
        exportador.setExporterInput(new SimpleExporterInput(impressao));
        SimplePrintServiceExporterConfiguration configuracao = new SimplePrintServiceExporterConfiguration();
        configuracao.setPrintService(impressora);
        configuracao.setDisplayPageDialog(false);
        configuracao.setDisplayPrintDialog(false);
        if (atributos != null) {
            configuracao.setPrintRequestAttributeSet(atributos);
        }
        exportador.setConfiguration(configuracao);
        exportador.exportReport();
    }
}
