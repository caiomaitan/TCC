package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.controller.ItemMenu;
import br.com.varesync.controller.RelatoriosController;
import br.com.varesync.report.Relatorio;
import br.com.varesync.service.DadosTela;
import br.com.varesync.util.Formatos;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;

/**
 * Fluxo 4 (F11.3): geração e exportação de um relatório pela tela — R01 Vendas por período dos últimos 30 dias, com os
 * dados de demonstração, exportado em CSV e PDF. A janela "Salvar como" é nativa e ficaria visível: o teste chama o
 * método que o botão chama depois da escolha do arquivo, com o destino em {@code target/telas}.
 */
class RelatorioTelaIT extends TelaBase {

    @Test
    void geracaoEExportacaoDoR01() throws Exception {
        dados.criarSupervisor();
        dados.gerarDemonstracao();
        LocalDate fim = LocalDate.now();
        LocalDate inicio = fim.minusDays(29);

        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        abrirTela(principal, ItemMenu.RELATORIOS);
        Parent r = principal.getRoot();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Relatorio<?>> escolha = (ComboBox<Relatorio<?>>) r.lookup("#escolhaRelatorio");
            escolha.getItems().stream().filter(x -> x.codigo().equals("R01")).findFirst()
                    .ifPresent(x -> escolha.getSelectionModel().select(x));
        });
        Thread.sleep(300);
        noFx(() -> {
            ((DatePicker) r.lookup("#dataInicio")).setValue(inicio);
            ((DatePicker) r.lookup("#dataFim")).setValue(fim);
            ((Button) r.lookup("#botaoGerar")).fire();
        });
        esperar(() -> fxr(() -> !r.lookup("#botaoGerar").isDisabled() && linhas(r) > 0), "R01 gerado");
        Thread.sleep(500);
        int linhas = fx(() -> linhas(r));
        assertThat(linhas).as("um dia por linha nos últimos 30 dias").isBetween(20, 30);
        capturar(principal, "r01_na_tela");

        // A receita da linha de totais é a da view de rentabilidade no mesmo período (7.2).
        BigDecimal receita = new BigDecimal(valor("SELECT coalesce(sum(receita), 0) FROM vw_venda_item_rentabilidade "
                + "WHERE data_hora >= '" + inicio + "'::date AND data_hora < '" + fim.plusDays(1) + "'::date"));
        List<String> totais = fx(() -> {
            List<String> textos = new ArrayList<>();
            for (Node n : ((Parent) r.lookup("#linhaTotais")).getChildrenUnmodifiable()) {
                if (n instanceof Label l) {
                    textos.add(l.getText());
                }
            }
            return textos;
        });
        assertThat(totais).as("linha de totais").contains(Formatos.moeda(receita));

        // ----- Exportar CSV e PDF -----
        Path pasta = Path.of(System.getProperty("telas.saida", "target/telas"), getClass().getSimpleName());
        Files.createDirectories(pasta);
        Path csv = pasta.resolve("R01.csv");
        Path pdf = pasta.resolve("R01.pdf");
        Files.deleteIfExists(csv);
        Files.deleteIfExists(pdf);
        RelatoriosController controller = controllerDoBotao(fx(() -> (Button) r.lookup("#botaoGerar")));
        exportar(controller, "CSV", csv);
        exportar(controller, "PDF", pdf);
        capturar(principal, "exportado");

        byte[] bytesCsv = Files.readAllBytes(csv);
        assertThat(bytesCsv).as("CSV com BOM para o Excel").startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        List<String> linhasCsv = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertThat(linhasCsv.get(0)).contains(";").contains("Receita");
        assertThat(linhasCsv).as("cabeçalho, um dia por linha e o total").hasSizeGreaterThanOrEqualTo(linhas + 2);
        assertThat(linhasCsv.get(linhasCsv.size() - 1)).as("total do CSV, sem separador de milhar")
                .startsWith("Total;").contains(";" + receita.toPlainString().replace('.', ',') + ";");
        byte[] bytesPdf = Files.readAllBytes(pdf);
        assertThat(new String(bytesPdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(bytesPdf.length).isGreaterThan(2_000);
    }

    private static int linhas(Parent r) {
        return r.lookupAll(".table-view").stream().mapToInt(n -> ((TableView<?>) n).getItems().size()).max()
                .orElse(0);
    }

    /** O mesmo que o botão faz depois do "Salvar como": {@code exportar(formato, destino)}, em Task. */
    private static void exportar(RelatoriosController controller, String formato, Path destino) throws Exception {
        Method exportar = RelatoriosController.class.getDeclaredMethod("exportar", String.class, Path.class);
        exportar.setAccessible(true);
        Task<?> tarefa = fx(() -> (Task<?>) exportar.invoke(controller, formato, destino));
        esperar(() -> fxr(tarefa::isDone), "exportar " + formato);
        assertThat(fx(tarefa::getException)).as("exportar %s", formato).isNull();
        assertThat(destino).exists();
    }

    /** O controller da tela, achado no tratador do botão (o FXML liga o onAction a um método dele). */
    private static RelatoriosController controllerDoBotao(Button botao) throws Exception {
        Object achado = procurar(fx(botao::getOnAction), 0);
        if (achado == null) {
            throw new IllegalStateException("controller da tela de relatórios não encontrado");
        }
        return (RelatoriosController) achado;
    }

    private static Object procurar(Object objeto, int nivel) throws IllegalAccessException {
        if (objeto == null || nivel > 3) {
            return null;
        }
        if (objeto instanceof RelatoriosController) {
            return objeto;
        }
        for (Class<?> c = objeto.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (RuntimeException inacessivel) {
                    continue;
                }
                Object achado = procurar(f.get(objeto), nivel + 1);
                if (achado != null) {
                    return achado;
                }
            }
        }
        return null;
    }
}
