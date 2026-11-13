package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.controller.ItemMenu;
import br.com.varesync.service.DadosTela;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

/**
 * Fluxo 3 (F11.3): inventário pela tela até a aplicação — novo inventário, inclusão pelo código de barras, contagem
 * cega pelo leitor, divergências (R13) e aplicar (com a confirmação do resumo). Um item fica sem contar e não muda.
 */
class InventarioTelaIT extends TelaBase {

    private static final String ARROZ = "7891000315507";
    private static final String FEIJAO = "7891234567895";
    private static final String CAFE = "12345670";

    @Test
    void inventarioAteAAplicacao() throws Exception {
        dados.criarSupervisor();
        long arroz = dados.produto("Arroz tipo 1 5 kg", "UN", "24.90", "18.00", "10", ARROZ);
        long feijao = dados.produto("Feijão carioca 1 kg", "UN", "8.49", "6.00", "5", FEIJAO);
        long cafe = dados.produto("Café torrado 500 g", "UN", "17.90", "12.00", "4", CAFE);

        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        abrirTela(principal, ItemMenu.INVENTARIO);
        Parent r = principal.getRoot();

        // ----- Novo inventário -----
        Platform.runLater(() -> botao(r, "Novo inventário… (Ctrl+N)").fire());
        var novo = esperarDialogo("Novo inventário");
        noFx(() -> ((TextField) novo.lookup(".text-field")).setText("Mercearia — novembro"));
        confirmarDialogo(novo);
        long inventario = Long.parseLong(valor("SELECT max(id) FROM inventario"));
        assertThat(valor("SELECT descricao || ';' || status FROM inventario WHERE id = " + inventario))
                .isEqualTo("Mercearia — novembro;ABERTO");

        // ----- Inclusão pelo código de barras -----
        for (String codigo : new String[] {ARROZ, FEIJAO, CAFE}) {
            digitar(r, "#campoInclusao", codigo);
        }
        esperar(() -> fxr(() -> ((TableView<?>) r.lookup("#tabelaContagem")).getItems().size() == 3), "3 itens");
        capturar(principal, "itens_incluidos");

        // ----- Contagem cega: arroz 8 (falta 2), feijão 6 (sobra 1); o café fica sem contar -----
        contar(r, ARROZ, "8");
        contar(r, FEIJAO, "6");
        assertThat(fx(() -> br.com.varesync.ui.Notificacao.atual(principal.getWindow())))
                .as("notificação discreta").startsWith("Contagem gravada: Feijão");
        capturar(principal, "contagem");

        // ----- Divergências -----
        assertThat(selecionarAba(r, "Divergências")).isTrue();
        esperar(() -> !texto(r, "#totalFalta").equals("—"), "totais das divergências");
        assertThat(texto(r, "#totalFalta")).contains("36,00"); // 2 × 18,00
        assertThat(texto(r, "#totalSobra")).contains("6,00"); // 1 × 6,00
        capturar(principal, "divergencias");

        // ----- Aplicar -----
        Platform.runLater(() -> ((javafx.scene.control.Button) r.lookup("#botaoAplicar")).fire());
        responder("Aplicar o inventário nº " + inventario, "Sim");
        esperar(() -> {
            try {
                return "APLICADO".equals(valor("SELECT status FROM inventario WHERE id = " + inventario));
            } catch (Exception e) {
                return false;
            }
        }, "inventário aplicado");
        Thread.sleep(500);
        capturar(principal, "aplicado");

        assertThat(saldo(arroz)).isEqualTo("8.000");
        assertThat(saldo(feijao)).isEqualTo("6.000");
        assertThat(saldo(cafe)).as("o item não contado não muda").isEqualTo("4.000");
        assertThat(contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'INVENTARIO'")).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM auditoria WHERE acao = 'INVENTARIO_APLICADO'")).isEqualTo(1);
        assertThat(fx(() -> r.lookup("#botaoAplicar").isDisabled())).as("aplicado fica só para consulta").isTrue();
    }

    private static void contar(Parent r, String codigo, String quantidade) throws Exception {
        digitar(r, "#campoLeitura", codigo);
        digitar(r, "#campoQuantidade", quantidade);
    }

    private static String texto(Parent raiz, String id) {
        return fxr(() -> ((Label) raiz.lookup(id)).getText());
    }

    private static String saldo(long produto) throws Exception {
        return valor("SELECT quantidade FROM produto_estoque WHERE produto_id = " + produto);
    }
}
