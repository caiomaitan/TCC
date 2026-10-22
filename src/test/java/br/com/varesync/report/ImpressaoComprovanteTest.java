package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.ComprovanteTest;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.service.NegocioException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.print.PrintService;
import net.sf.jasperreports.engine.JasperPrint;
import org.junit.jupiter.api.Test;

/** Impressão do comprovante (6.6) sem impressora de verdade: o que vai para a impressora padrão e a falta dela. */
class ImpressaoComprovanteTest {

    /** Impressora de mentira: só responde o nome. */
    private static PrintService impressora(String nome) {
        return (PrintService) Proxy.newProxyInstance(PrintService.class.getClassLoader(),
                new Class<?>[] {PrintService.class}, (proxy, metodo, args) -> switch (metodo.getName()) {
                    case "getName", "toString" -> nome;
                    case "hashCode" -> nome.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @Test
    void mandaOMesmoLayoutDoPdfParaAImpressoraPadrao() {
        List<JasperPrint> enviados = new ArrayList<>();
        List<String> impressoras = new ArrayList<>();
        ImpressaoComprovante impressao = new ImpressaoComprovante(new ComprovantePdf(),
                () -> impressora("Térmica do caixa"), (p, i) -> {
                    enviados.add(p);
                    impressoras.add(i.getName());
                });
        String usada = impressao.imprimir(ComprovanteTest.exemplo(StatusVenda.FINALIZADA));
        assertThat(usada).isEqualTo("Térmica do caixa");
        assertThat(impressoras).containsExactly("Térmica do caixa");
        assertThat(enviados).singleElement().satisfies(p -> {
            assertThat(p.getPages()).isNotEmpty();
            assertThat(p.getPageWidth()).isEqualTo(226); // bobina de 80 mm, a mesma largura do JRXML do PDF
        });
    }

    @Test
    void semImpressoraPadraoAvisaEmVezDeFalhar() {
        ImpressaoComprovante impressao = new ImpressaoComprovante(new ComprovantePdf(), () -> null,
                (p, i) -> { throw new AssertionError("não deveria imprimir"); });
        assertThatThrownBy(() -> impressao.imprimir(ComprovanteTest.exemplo(StatusVenda.FINALIZADA)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Nenhuma impressora padrão");
    }
}
