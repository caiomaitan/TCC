package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.VendaResumo;
import br.com.varesync.service.DadosTela;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

/**
 * Fluxo 2 (F11.3): venda completa no PDV pela tela — três itens, cancelamento de item (F6), desconto de 5% na venda
 * (F5), pagamento misto no PIX e em dinheiro com troco (F10) e, depois, o cancelamento da venda (F9).
 */
class VendaPdvTelaIT extends TelaBase {

    private static final String REFRIGERANTE = "7891000315507";
    private static final String BISCOITO = "7891234567895";

    @Test
    void vendaComDescontoPagamentoMistoECancelamento() throws Exception {
        dados.criarSupervisor();
        long refrigerante = dados.produto("Refrigerante cola 2 L", "UN", "5.49", "3.20", "10", REFRIGERANTE);
        long biscoito = dados.produto("Biscoito recheado 140 g", "UN", "3.50", "2.00", "10", BISCOITO);
        dados.abrirCaixa("100.00");

        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        Scene pdv = abrirPdv(principal);
        Parent p = pdv.getRoot();

        // ----- Itens: 3 refrigerantes, 2 biscoitos lidos um a um; o último biscoito é cancelado (F6) -----
        digitar(p, "#entrada", "3*" + REFRIGERANTE);
        digitar(p, "#entrada", BISCOITO);
        digitar(p, "#entrada", BISCOITO);
        assertThat(fx(() -> ((TableView<?>) p.lookup("#tabelaItens")).getItems().size())).isEqualTo(3);
        noFx(() -> ((TableView<?>) p.lookup("#tabelaItens")).getSelectionModel().select(2));
        tecla(p, KeyCode.F6);
        responder("Cancelar o item 3", "Sim");
        assertThat(texto(p, "#total")).isEqualTo("R$ 19,97"); // 3 × 5,49 + 3,50
        capturar(pdv, "itens_com_cancelado");

        // ----- Desconto de 5% na venda (F5), dentro do limite do operador -----
        tecla(p, KeyCode.F5);
        DialogPane desconto = esperarDialogo("Desconto na venda");
        noFx(() -> ((TextField) desconto.lookup(".campo-numero")).setText("5"));
        capturar(desconto.getScene(), "desconto_na_venda");
        confirmarDialogo(desconto);
        assertThat(texto(p, "#total")).isEqualTo("R$ 18,97"); // 5% de 19,97 = 1,00
        capturar(pdv, "com_desconto");

        // ----- Pagamento misto (F10): R$ 10,00 no PIX e uma nota de R$ 20,00 -----
        tecla(p, KeyCode.F10);
        Scene pagamento = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null, "pagamento");
        Parent g = pagamento.getRoot();
        pagar(g, TipoFormaPagamento.PIX, "10,00");
        pagar(g, TipoFormaPagamento.DINHEIRO, "20,00");
        assertThat(texto(g, "#troco")).isEqualTo("R$ 11,03");
        capturar(pagamento, "pagamento_misto");
        Platform.runLater(() -> ((Button) g.lookup("#botaoFinalizar")).fire());
        DialogPane finalizada = esperarDialogo("finalizada");
        long venda = Long.parseLong(finalizada.getHeaderText().replaceAll("\\D", ""));
        capturar(finalizada.getScene(), "venda_finalizada");
        responderPorCabecalho(finalizada, "Próxima venda");

        assertThat(valor("SELECT status || ';' || valor_total || ';' || troco || ';' || desconto_venda FROM venda WHERE id = "
                + venda)).isEqualTo("FINALIZADA;18.97;11.03;1.00");
        assertThat(valor("SELECT string_agg(f.tipo || '=' || vp.valor, ',' ORDER BY f.tipo) FROM venda_pagamento vp "
                + "JOIN forma_pagamento f ON f.id = vp.forma_pagamento_id WHERE vp.venda_id = " + venda))
                .isEqualTo("DINHEIRO=8.97,PIX=10.00");
        assertThat(contar("SELECT count(*) FROM venda_item WHERE venda_id = " + venda + " AND cancelado")).isEqualTo(1);
        assertThat(saldo(refrigerante)).isEqualTo("7.000");
        assertThat(saldo(biscoito)).isEqualTo("9.000");

        // ----- Cancelamento da venda (F9) -----
        tecla(p, KeyCode.F9);
        Scene vendas = esperarCena(s -> s.getRoot().lookup("#botaoCancelar") != null
                && s.getRoot().lookup("#tabelaVendas") != null, "vendas da sessão");
        Parent v = vendas.getRoot();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            TableView<VendaResumo> tabela = (TableView<VendaResumo>) v.lookup("#tabelaVendas");
            tabela.getItems().stream().filter(x -> x.id() == venda).findFirst()
                    .ifPresent(x -> tabela.getSelectionModel().select(x));
        });
        Platform.runLater(() -> ((Button) v.lookup("#botaoCancelar")).fire());
        DialogPane cancelar = esperarDialogo("Cancelar a venda nº " + venda);
        noFx(() -> ((ComboBox<?>) cancelar.lookup(".combo-box")).getEditor().setText("Cliente desistiu"));
        capturar(cancelar.getScene(), "cancelar_venda");
        confirmarDialogo(cancelar);
        capturar(vendas, "venda_cancelada");

        assertThat(valor("SELECT status || ';' || motivo_cancelamento FROM venda WHERE id = " + venda))
                .isEqualTo("CANCELADA;Cliente desistiu");
        assertThat(contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'CANCELAMENTO_VENDA'")).isEqualTo(2);
        assertThat(saldo(refrigerante)).isEqualTo("10.000");
        assertThat(saldo(biscoito)).isEqualTo("10.000");
        assertThat(valor("SELECT dinheiro_esperado FROM vw_caixa_resumo WHERE status = 'ABERTO'"))
                .as("a venda cancelada sai do dinheiro esperado").isEqualTo("100.00");

        noFx(() -> ((Stage) vendas.getWindow()).close());
        Window janelaPdv = pdv.getWindow();
        tecla(p, KeyCode.ESCAPE); // carrinho vazio: Esc sai do PDV
        esperar(() -> !janelaPdv.isShowing(), "sair do PDV");
    }

    private void pagar(Parent g, TipoFormaPagamento tipo, String quantia) throws Exception {
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<FormaPagamento> forma = (ComboBox<FormaPagamento>) g.lookup("#forma");
            forma.setValue(forma.getItems().stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow());
            TextField valor = (TextField) g.lookup("#valor");
            valor.setText(quantia);
            valor.fireEvent(new ActionEvent());
        });
        Thread.sleep(300);
    }

    private static void responderPorCabecalho(DialogPane d, String resposta) throws Exception {
        Window w = d.getScene().getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : d.getButtonTypes()) {
                if (b.getText().equals(resposta)) {
                    ((Button) d.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !w.isShowing(), resposta);
        Thread.sleep(300);
    }

    private static String texto(Parent raiz, String id) {
        return fxr(() -> ((Label) raiz.lookup(id)).getText());
    }

    private static String saldo(long produto) throws Exception {
        return valor("SELECT quantidade FROM produto_estoque WHERE produto_id = " + produto);
    }
}
