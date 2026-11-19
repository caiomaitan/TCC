package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.controller.ItemMenu;
import br.com.varesync.report.Relatorio;
import br.com.varesync.service.DadosTela;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

/**
 * Varredura de cortes (F11.3): com os dados de demonstração, todas as telas do menu (e cada aba delas), o PDV com
 * itens e o pagamento, na janela de 1280×720, primeiro no claro a 100% e depois no escuro a 130% (F21). Nenhum texto
 * pode sair cortado com "…" fora das células, e nenhum controle pode passar da borda da janela (regras em
 * {@link Cortes}). Cada tela vira uma captura em {@code target/telas/VarreduraCortesTelaIT/}.
 */
class VarreduraCortesTelaIT extends TelaBase {

    private final List<String> cortes = new ArrayList<>();
    private int telas;

    @Test
    void todasAsTelasNoClaroA100ENoEscuroA130() throws Exception {
        System.out.println(fx(VarreduraCortesTelaIT::fonteDoSistema));
        dados.criarSupervisor();
        dados.gerarDemonstracao();
        dados.abrirCaixa("100.00");
        String codigoLongo = valor("SELECT pcb.codigo FROM produto_codigo_barras pcb JOIN produto p ON p.id = "
                + "pcb.produto_id WHERE p.ativo AND p.preco_venda > 0 AND pcb.principal AND p.unidade = 'UN' "
                + "ORDER BY length(p.nome) DESC, p.id LIMIT 1");
        String codigoCurto = valor("SELECT pcb.codigo FROM produto_codigo_barras pcb JOIN produto p ON p.id = "
                + "pcb.produto_id WHERE p.ativo AND p.preco_venda > 0 AND pcb.principal AND p.unidade = 'UN' "
                + "ORDER BY p.nome, p.id LIMIT 1");

        // ----- Claro a 100% (o padrão) -----
        Scene principal = abrirEEntrar(DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        assertThat(fx(() -> principal.getRoot().getStyleClass().contains("tema-escuro"))).isFalse();
        passar(principal, "claro100", codigoLongo, codigoCurto);

        // ----- Escuro a 130%: as preferências do usuário valem no próximo login -----
        dados.preferencias(DadosTela.LOGIN_SUPERVISOR, "ESCURO", 130);
        noFx(() -> botao(principal.getRoot(), "Log off").fire());
        Scene escuro = entrar(esperarLogin(), DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);
        assertThat(fx(() -> escuro.getRoot().getStyleClass().contains("tema-escuro"))).as("tema escuro").isTrue();
        passar(escuro, "escuro130", codigoLongo, codigoCurto);

        System.out.println("Varredura de cortes: " + telas + " telas conferidas, " + cortes.size() + " cortes");
        assertThat(telas).as("telas conferidas nas duas passadas").isGreaterThan(2 * ItemMenu.values().length);
        assertThat(cortes).as("textos cortados ou controles fora da janela de 1280×720").isEmpty();
    }

    /** Todas as telas do menu, com as abas, e o PDV. */
    private void passar(Scene principal, String prefixo, String codigoLongo, String codigoCurto) throws Exception {
        assertThat(fx(() -> principal.getWindow().getWidth() <= 1281 && principal.getWindow().getHeight() <= 721))
                .as("%s: janela principal em 1280×720", prefixo).isTrue();
        Parent r = principal.getRoot();
        for (ItemMenu item : ItemMenu.values()) {
            if (item.janelaPropria()) {
                passarPdv(principal, prefixo, codigoLongo, codigoCurto);
                continue;
            }
            abrirTela(principal, item);
            String nome = prefixo + "_" + arquivo(item.rotulo());
            if (item == ItemMenu.RELATORIOS) {
                gerarR01(r);
            }
            selecionarPrimeiraLinha(r);
            verificar(principal, nome);
            for (Tab aba : fx(() -> abasDaTela(r))) {
                if (fx(() -> aba.getTabPane().getSelectionModel().getSelectedItem() == aba)) {
                    continue; // já conferida com a tela
                }
                noFx(() -> aba.getTabPane().getSelectionModel().select(aba));
                Thread.sleep(900);
                selecionarPrimeiraLinha(r);
                verificar(principal, nome + "_" + arquivo(aba.getText()));
            }
        }
    }

    private void passarPdv(Scene principal, String prefixo, String codigoLongo, String codigoCurto) throws Exception {
        Scene pdv = abrirPdv(principal);
        noFx(() -> {
            Stage janela = (Stage) pdv.getWindow();
            janela.setMaximized(false);
            janela.setWidth(1280);
            janela.setHeight(720);
        });
        Thread.sleep(500);
        Parent p = pdv.getRoot();
        digitar(p, "#entrada", codigoLongo);
        digitar(p, "#entrada", "3*" + codigoCurto);
        verificar(pdv, prefixo + "_pdv");

        tecla(p, KeyCode.F10);
        Scene pagamento = esperarCena(s -> s.getRoot().lookup("#botaoFinalizar") != null, "pagamento");
        Thread.sleep(400);
        verificar(pagamento, prefixo + "_pdv_pagamento");
        Window janelaPagamento = pagamento.getWindow();
        noFx(() -> botao(pagamento.getRoot(), "Voltar à venda (Esc)").fire());
        esperar(() -> !janelaPagamento.isShowing(), "voltar à venda");

        tecla(p, KeyCode.ESCAPE);
        responder("bandonar", "Sim");
        Window janelaPdv = pdv.getWindow();
        tecla(p, KeyCode.ESCAPE);
        esperar(() -> !janelaPdv.isShowing(), "sair do PDV");
        Thread.sleep(400);
    }

    private void verificar(Scene cena, String nome) throws Exception {
        Thread.sleep(300);
        List<String> achados = fx(() -> Cortes.procurar(cena));
        capturar(cena, nome);
        telas++;
        achados.forEach(c -> cortes.add(nome + ": " + c));
    }

    /** Abas das telas (inclusive as das seções de Configurações), na ordem da tela. */
    private static List<Tab> abasDaTela(Parent raiz) {
        StackPane conteudo = (StackPane) raiz.lookup(".conteudo");
        List<Tab> abas = new ArrayList<>();
        for (Node n : conteudo.lookupAll(".tab-pane")) {
            TabPane tp = (TabPane) n;
            if (Cortes.visivel(tp)) {
                abas.addAll(tp.getTabs().stream().filter(t -> !t.isDisabled()).toList());
            }
        }
        return abas;
    }

    /** Linha selecionada: as telas divididas mostram o detalhe embaixo. */
    private static void selecionarPrimeiraLinha(Parent raiz) throws Exception {
        noFx(() -> {
            StackPane conteudo = (StackPane) raiz.lookup(".conteudo");
            for (Node n : conteudo.lookupAll(".table-view")) {
                TableView<?> tabela = (TableView<?>) n;
                if (Cortes.visivel(tabela) && !tabela.getItems().isEmpty()
                        && tabela.getSelectionModel().getSelectedIndex() < 0) {
                    tabela.getSelectionModel().select(0);
                    return;
                }
            }
        });
        Thread.sleep(600);
    }

    private static void gerarR01(Parent r) throws Exception {
        noFx(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<Relatorio<?>> escolha = (ComboBox<Relatorio<?>>) r.lookup("#escolhaRelatorio");
            escolha.getItems().stream().filter(x -> x.codigo().equals("R01")).findFirst()
                    .ifPresent(x -> escolha.getSelectionModel().select(x));
            ((Button) r.lookup("#botaoGerar")).fire();
        });
        esperar(() -> fxr(() -> !r.lookup("#botaoGerar").isDisabled() && r.lookupAll(".table-view").stream()
                .mapToInt(n -> ((TableView<?>) n).getItems().size()).max().orElse(0) > 0), "R01 gerado");
        Thread.sleep(500);
    }

    /**
     * Para o log: a fonte padrão do JavaFX e a largura de um texto de referência na "System" (a do app.css) e nas
     * famílias que interessam. O nome da "System" não diz qual fonte física o JavaFX usou; a largura igual à de uma
     * família instalada diz. No Linux do CI, a "System" deve medir como a Segoe UI (a Selawik, instalada pelo ci.yml).
     */
    private static String fonteDoSistema() {
        Font padrao = Font.getDefault();
        String referencia = "Salvar alterações · Configurações · v2.0.1";
        double px = 13.5 * 1.3; // tamanho da raiz no app.css, com a fonte em 130%
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "Fonte padrão do JavaFX: família \"%s\", nome \"%s\", tamanho %.1f; largura de \"%s\" a %.2f px:",
                padrao.getFamily(), padrao.getName(), padrao.getSize(), referencia, px));
        List<String> instaladas = Font.getFamilies();
        for (String familia : List.of("System", "Segoe UI", "Selawik", "DejaVu Sans", "Liberation Sans")) {
            boolean existe = familia.equals("System") || instaladas.contains(familia);
            Text texto = new Text(referencia);
            texto.setFont(Font.font(familia, px));
            sb.append(String.format(Locale.ROOT, " %s=%s;", familia,
                    existe ? String.format(Locale.ROOT, "%.1f", texto.getLayoutBounds().getWidth()) : "ausente"));
        }
        return sb.toString();
    }

    /** "Contas a pagar" → "contas_a_pagar". */
    private static String arquivo(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
