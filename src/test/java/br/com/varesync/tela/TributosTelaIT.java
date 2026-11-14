package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.controller.ItemMenu;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.MeioPagamentoNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.service.DadosTela;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

/**
 * Tributos da venda, parte A (F22.1 a F22.3), pela tela: Configurações › Loja (Tributos da venda), o tPag da forma de pagamento, o
 * perfil tributário com FCP e redução do IBS e da CBS, a importação da tabela IBPT (com a confirmação da UF diferente
 * da loja) e a venda no PDV que grava os tributos do item.
 */
class TributosTelaIT extends TelaBase {

    private static final String AGUA = "7891000315507";
    private static final String NCM = "22021000";

    @Test
    void configuracoesPerfilTabelaIbptEVendaComTributos() throws Exception {
        dados.criarSupervisor();
        dados.configurar("empresa.uf", "SP");
        long agua = dados.produto("Água com gás 500 ml", "UN", "10.00", "4.00", "10", AGUA);
        dados.abrirCaixa("100.00");

        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        Parent r = principal.getRoot();

        // ----- Configurações › Loja, bloco "Tributos da venda" -----
        abrirTela(principal, ItemMenu.CONFIGURACOES);
        assertThat(selecionarAba(r, "Loja")).isTrue();
        assertThat(fx(() -> ((TextField) r.lookup("#campoAliquotaCbs")).getText())).isEqualTo("0,90");
        assertThat(fx(() -> ((TextField) r.lookup("#campoAliquotaIbsUf")).getText())).isEqualTo("0,10");
        noFx(() -> {
            for (javafx.scene.Node n = r.lookup("#campoAliquotaCbs"); n != null; n = n.getParent()) {
                if (n instanceof javafx.scene.control.ScrollPane s) {
                    s.setVvalue(1);
                    break;
                }
            }
        });
        Thread.sleep(300);
        capturar(principal, "configuracoes_tributos");
        noFx(() -> {
            ((TextField) r.lookup("#campoAliquotaIbsMun")).setText("0,05");
            ((Button) r.lookup("#botaoSalvar")).fire();
        });
        esperar(() -> "0.05".equals(valorOuNulo("SELECT valor FROM configuracao WHERE chave = 'fiscal.aliquota_ibs_mun'")),
                "alíquota do IBS do município gravada");
        assertThat(valor("SELECT descricao FROM auditoria WHERE acao = 'CONFIGURACAO_ALTERADA' ORDER BY id DESC "
                + "LIMIT 1")).contains("alíquota do IBS do município de 0,00% para 0,05%");

        // ----- Formas de pagamento: o PIX da maquininha passa a 17 -----
        assertThat(selecionarAba(r, "Formas de pagamento")).isTrue();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            TableView<FormaPagamentoCadastro> formas = (TableView<FormaPagamentoCadastro>) r.lookup("#tabelaFormas");
            formas.getItems().stream().filter(f -> f.tipo() == TipoFormaPagamento.PIX).findFirst()
                    .ifPresent(f -> formas.getSelectionModel().select(f));
        });
        Thread.sleep(300);
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<MeioPagamentoNfce> tpag = (ComboBox<MeioPagamentoNfce>) r.lookup("#campoTpagForma");
            assertThat(tpag.getValue().codigo()).isEqualTo("20");
            tpag.setValue(MeioPagamentoNfce.doCodigo("17").orElseThrow());
            botao(r, "Salvar tPag").fire();
        });
        esperar(() -> "17".equals(valorOuNulo("SELECT tpag FROM forma_pagamento WHERE tipo = 'PIX'")), "tPag do PIX");
        Thread.sleep(300);
        capturar(principal, "formas_tpag");

        // ----- Cadastro fiscal › Perfis: perfil do Simples -----
        abrirTela(principal, ItemMenu.FISCAL);
        assertThat(selecionarAba(r, "Perfis tributários")).isTrue();
        noFx(() -> {
            ((TextField) r.lookup("#campoNomePerfil")).setText("Simples tributado");
            ((TextField) r.lookup("#campoCfop")).setText("5102");
            ((TextField) r.lookup("#campoSituacaoIcms")).setText("102");
            ((TextField) r.lookup("#campoCstPis")).setText("49");
            ((TextField) r.lookup("#campoCstCofins")).setText("49");
        });
        assertThat(fx(() -> r.lookup("#campoFcp") != null && r.lookup("#campoReducaoIbsCbs") != null)).isTrue();
        capturar(principal, "perfil_com_fcp_e_reducao");
        noFx(() -> botao(r, "Salvar perfil").fire());
        esperar(() -> fxr(() -> contar("SELECT count(*) FROM perfil_tributario") == 1), "perfil gravado");
        dados.dadosFiscais(agua, NCM, 0, "Simples tributado");

        // ----- Tabela IBPT: o arquivo é de MG e a loja é de SP -> confirmação -----
        assertThat(selecionarAba(r, "Tabelas NCM, CEST e IBPT")).isTrue();
        assertThat(fx(() -> ((Label) r.lookup("#situacaoIbpt")).getText())).contains("não importada");
        Path arquivo = arquivoIbpt();
        Object controller = controllerDoBotao(fx(() -> (Button) r.lookup("#botaoImportarIbpt")));
        Method importar = controller.getClass().getDeclaredMethod("importarIbpt", Path.class);
        importar.setAccessible(true);
        Platform.runLater(() -> {
            try {
                importar.invoke(controller, arquivo);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Scene confirmacao = esperarCena(s -> s.getRoot() instanceof DialogPane d && d.getContentText() != null
                && d.getContentText().contains("é da tabela do IBPT de MG"), "confirmação da UF");
        capturar(confirmacao, "ibpt_uf_diferente");
        responder("é da tabela do IBPT de MG", "Sim");
        esperar(() -> fxr(() -> ((Label) r.lookup("#situacaoIbpt")).getText().contains("versão 26.2.A")),
                "tabela IBPT importada");
        assertThat(contar("SELECT count(*) FROM ibpt")).isEqualTo(2);
        assertThat(fx(() -> ((Label) r.lookup("#mensagemTabelas")).getText() + " " + ((Label) r.lookup(
                "#situacaoIbpt")).getText())).contains("linhas de NCM");
        capturar(principal, "ibpt_importada");

        // ----- PDV: a venda grava os tributos do item -----
        Scene pdv = abrirPdv(principal);
        Parent p = pdv.getRoot();
        digitar(p, "#entrada", AGUA);
        tecla(p, KeyCode.F10);
        Scene pagamento = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null, "pagamento");
        Parent g = pagamento.getRoot();
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<FormaPagamento> forma = (ComboBox<FormaPagamento>) g.lookup("#forma");
            forma.setValue(forma.getItems().stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                    .findFirst().orElseThrow());
            TextField valor = (TextField) g.lookup("#valor");
            valor.setText("10,00");
            valor.fireEvent(new ActionEvent());
        });
        Thread.sleep(300);
        Platform.runLater(() -> ((Button) g.lookup("#botaoFinalizar")).fire());
        DialogPane finalizada = esperarDialogo("finalizada");
        long venda = Long.parseLong(finalizada.getHeaderText().replaceAll("\\D", ""));
        capturar(finalizada.getScene(), "venda_finalizada");
        Window w = finalizada.getScene().getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : finalizada.getButtonTypes()) {
                if (b.getText().equals("Próxima venda")) {
                    ((Button) finalizada.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !w.isShowing(), "próxima venda");

        assertThat(valor("SELECT vit.csosn || ';' || vit.valor_icms || ';' || vit.trib_federal || ';' "
                + "|| vit.trib_estadual || ';' || vit.trib_municipal || ';' || vit.ibpt_versao "
                + "FROM venda_item_tributo vit JOIN venda_item vi ON vi.id = vit.venda_item_id WHERE vi.venda_id = "
                + venda)).isEqualTo("102;0.00;1.35;1.80;0.00;26.2.A");
        assertThat(valor("SELECT valor_total FROM venda WHERE id = " + venda)).as("a venda não muda")
                .isEqualTo("10.00");

        Window janelaPdv = pdv.getWindow();
        tecla(p, KeyCode.ESCAPE);
        esperar(() -> !janelaPdv.isShowing(), "sair do PDV");
    }

    /** Arquivo no leiaute do IBPT (ISO-8859-1, {@code ;}), de MG, com vigência em volta de hoje e uma linha de NBS. */
    private Path arquivoIbpt() throws Exception {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String inicio = LocalDate.now().minusDays(30).format(f);
        String fim = LocalDate.now().plusDays(60).format(f);
        String conteudo = "codigo;ex;tipo;descricao;nacionalfederal;importadosfederal;estadual;municipal;"
                + "vigenciainicio;vigenciafim;chave;versao;fonte\n"
                + NCM + ";;0;Água mineral com adição de açúcar;13,45;15,45;18,00;0,00;" + inicio + ";" + fim
                + ";A1B2C3;26.2.A;IBPT/empresometro.com.br\n"
                + "09012100;;0;Café torrado;8,03;10,03;7,00;0,00;" + inicio + ";" + fim
                + ";A1B2C3;26.2.A;IBPT/empresometro.com.br\n"
                + "1.0101;;1;Serviço (NBS);13,45;0;0;2,00;" + inicio + ";" + fim + ";A1B2C3;26.2.A;IBPT\n";
        Path pasta = Path.of(System.getProperty("telas.saida", "target/telas"), getClass().getSimpleName());
        Files.createDirectories(pasta);
        Path arquivo = pasta.resolve("TabelaIBPTaxMG26.2.A.csv");
        Files.write(arquivo, conteudo.getBytes(StandardCharsets.ISO_8859_1));
        return arquivo;
    }

    private static String valorOuNulo(String sql) {
        try {
            return valor(sql);
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /** O controller da tela, achado no tratador do botão (o FXML liga o onAction a um método dele). */
    private static Object controllerDoBotao(Button botao) throws Exception {
        Object achado = procurar(fx(botao::getOnAction), 0);
        if (achado == null) {
            throw new IllegalStateException("controller do Cadastro fiscal não encontrado");
        }
        return achado;
    }

    private static Object procurar(Object objeto, int nivel) throws IllegalAccessException {
        if (objeto == null || nivel > 3) {
            return null;
        }
        if (objeto.getClass().getName().equals("br.com.varesync.controller.FiscalController")) {
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
