package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.controller.ItemMenu;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Relatorio;
import br.com.varesync.service.DadosTela;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

/**
 * Tributos da venda, parte B (F22.4 e F22.5), pela tela: a NF-e com ST importada em Compras preenche o ST retido pelo
 * diálogo dos dados fiscais; a venda no PDV grava o ST e mostra os tributos aproximados no comprovante; o R31 e o R30
 * (regras novas) em Relatórios.
 */
class TributosParteBTelaIT extends TelaBase {

    private static final String CERVEJA = "7891000315507";
    private static final String REFRIGERANTE = "7896004000015";
    private static final String ARROZ = "7891234567895";
    private static final String ARROZ_CAIXA = "17891234567892";
    private static final String CAFE = "7896006716112";

    @Test
    void notaComStComprovanteR31ER30() throws Exception {
        dados.criarSupervisor();
        dados.configurar("empresa.uf", "SP");
        dados.perfilSimples("Simples ST", "5405", "500");
        long cerveja = dados.produto("Cerveja lata 350 ml", "UN", "6.00", "4.00", "24", CERVEJA);
        dados.produto("Refrigerante lata 350 ml", "UN", "3.50", "2.50", "24", REFRIGERANTE);
        long arroz = dados.produto("Arroz tipo 1 1 kg", "UN", "7.00", "5.00", "24", ARROZ);
        dados.embalagem(arroz, ARROZ_CAIXA, 12);
        dados.produto("Café 500 g", "UN", "15.00", "12.50", "10", CAFE);
        dados.dadosFiscais(cerveja, null, 0, "Simples ST");
        dados.importarIbpt(tabelaIbpt(), "TabelaIBPTaxSP26.2.A.csv");
        dados.abrirCaixa("100.00");

        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        Parent r = principal.getRoot();

        // ----- Compras: o XML da NF-e com ST (o método que o botão chama depois do "Abrir") -----
        abrirTela(principal, ItemMenu.COMPRAS);
        Path xml = Path.of("src/test/resources/nfe/nfe-st.xml").toAbsolutePath();
        Object compras = controller(fx(() -> botao(r, "Importar XML da NF-e…")), "ComprasController");
        Method importar = compras.getClass().getDeclaredMethod("importarNfe", Path.class);
        importar.setAccessible(true);
        Platform.runLater(() -> {
            try {
                importar.invoke(compras, xml);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        DialogPane conferencia = esperarDialogo("Importar NF-e nº 7777");
        capturar(conferencia.getScene(), "nfe_conferencia");
        confirmarDialogo(conferencia);
        DialogPane fiscal = esperarDialogo("Dados fiscais da NF-e");
        String textoFiscal = fx(() -> String.join(" | ", fiscal.lookupAll(".check-box").stream()
                .map(n -> ((javafx.scene.control.CheckBox) n).getText()).toList()));
        assertThat(textoFiscal).contains("ST retido por unidade: base R$ 5,0000, alíquota 18,00%, ICMS próprio "
                + "R$ 0,2000, retido R$ 0,3000");
        capturar(fiscal.getScene(), "nfe_dados_fiscais_com_st");
        confirmarDialogo(fiscal);
        esperar(() -> "5.0000;0.3000".equals(valorOuNulo("SELECT st_base_unitaria || ';' || st_icms_retido_unitario "
                + "FROM produto WHERE id = " + cerveja)), "ST retido gravado");
        assertThat(valor("SELECT st_icms_retido_unitario FROM produto WHERE id = " + arroz))
                .as("2 caixas de 12 = 24 un").isEqualTo("0.9667");

        // ----- PDV: 2 cervejas; o comprovante com os tributos aproximados -----
        Scene pdv = abrirPdv(principal);
        Parent p = pdv.getRoot();
        digitar(p, "#entrada", "2*" + CERVEJA);
        tecla(p, KeyCode.F10);
        Scene pagamento = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null, "pagamento");
        Parent g = pagamento.getRoot();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<FormaPagamento> forma = (ComboBox<FormaPagamento>) g.lookup("#forma");
            forma.setValue(forma.getItems().stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                    .findFirst().orElseThrow());
            TextField valor = (TextField) g.lookup("#valor");
            valor.setText("12,00");
            valor.fireEvent(new ActionEvent());
        });
        Thread.sleep(300);
        Platform.runLater(() -> ((Button) g.lookup("#botaoFinalizar")).fire());
        DialogPane finalizada = esperarDialogo("finalizada");
        long venda = Long.parseLong(finalizada.getHeaderText().replaceAll("\\D", ""));
        Platform.runLater(() -> {
            for (ButtonType b : finalizada.getButtonTypes()) {
                if (b.getText().equals("Ver comprovante")) {
                    ((Button) finalizada.lookupButton(b)).fire();
                }
            }
        });
        Scene comprovante = esperarCena(s -> s.getRoot().lookup("#texto") instanceof TextArea, "comprovante");
        String texto = fx(() -> ((TextArea) comprovante.getRoot().lookup("#texto")).getText());
        // 12,00 × 17,30% = 2,08; × 25% = 3,00.
        assertThat(texto.replace("\n", " ")).contains("Tributos aproximados: Federal R$ 2,08 · Estadual R$ 3,00 ·",
                "Municipal R$ 0,00 (Fonte: IBPT)");
        capturar(comprovante, "comprovante_com_tributos");
        noFx(() -> ((Stage) comprovante.getWindow()).close());
        assertThat(valor("SELECT t.base_st_retido || ';' || t.icms_st_retido FROM venda_item_tributo t "
                + "JOIN venda_item vi ON vi.id = t.venda_item_id WHERE vi.venda_id = " + venda))
                .isEqualTo("10.00;0.60");
        Window janelaPdv = pdv.getWindow();
        tecla(p, KeyCode.ESCAPE);
        esperar(() -> !janelaPdv.isShowing(), "sair do PDV");

        // ----- Relatórios: R31 e R30 -----
        abrirTela(principal, ItemMenu.RELATORIOS);
        gerar(r, "R31");
        assertThat(fx(() -> linhas(r))).isEqualTo(1);
        capturar(principal, "r31");
        gerar(r, "R30");
        assertThat(fx(() -> linhas(r))).isPositive();
        capturar(principal, "r30_regras_novas");
    }

    private void gerar(Parent r, String codigo) throws Exception {
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Relatorio<?>> escolha = (ComboBox<Relatorio<?>>) r.lookup("#escolhaRelatorio");
            escolha.getItems().stream().filter(x -> x.codigo().equals(codigo)).findFirst()
                    .ifPresent(x -> escolha.getSelectionModel().select(x));
        });
        Thread.sleep(300);
        noFx(() -> {
            DatePicker inicio = (DatePicker) r.lookup("#dataInicio");
            if (inicio != null && inicio.isVisible()) {
                inicio.setValue(LocalDate.now());
                ((DatePicker) r.lookup("#dataFim")).setValue(LocalDate.now());
            }
            ((Button) r.lookup("#botaoGerar")).fire();
        });
        esperar(() -> fxr(() -> !r.lookup("#botaoGerar").isDisabled() && linhas(r) > 0), codigo + " gerado");
        Thread.sleep(400);
    }

    private static int linhas(Parent r) {
        return r.lookupAll(".table-view").stream().mapToInt(n -> ((TableView<?>) n).getItems().size()).max()
                .orElse(0);
    }

    /** Tabela IBPT no leiaute do IBPT, vigente em volta de hoje, com a cerveja (22030000). */
    private static byte[] tabelaIbpt() {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String inicio = LocalDate.now().minusDays(30).format(f);
        String fim = LocalDate.now().plusDays(60).format(f);
        return ("codigo;ex;tipo;descricao;nacionalfederal;importadosfederal;estadual;municipal;vigenciainicio;"
                + "vigenciafim;chave;versao;fonte\n"
                + "22030000;;0;Cervejas de malte;17,30;19,30;25,00;0,00;" + inicio + ";" + fim + ";A;26.2.A;IBPT\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String valorOuNulo(String sql) {
        try {
            return valor(sql);
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /** O controller da tela, achado no tratador do botão (o FXML liga o onAction a um método dele). */
    private static Object controller(ButtonBase botao, String classe) throws Exception {
        Object achado = procurar(fx(botao::getOnAction), classe, 0);
        if (achado == null) {
            throw new IllegalStateException(classe + " não encontrado");
        }
        return achado;
    }

    private static Object procurar(Object objeto, String classe, int nivel) throws IllegalAccessException {
        if (objeto == null || nivel > 3) {
            return null;
        }
        if (objeto.getClass().getSimpleName().equals(classe)) {
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
                Object achado = procurar(f.get(objeto), classe, nivel + 1);
                if (achado != null) {
                    return achado;
                }
            }
        }
        return null;
    }
}
