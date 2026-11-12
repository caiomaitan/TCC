package br.com.varesync.ui;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.ui.Atalhos.Atalho;
import br.com.varesync.ui.Atalhos.Tecla;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

/**
 * Registro de atalhos (F21.3): a tecla dispara o que está registrado e a lista da ajuda sai do mesmo registro. Sem
 * subir o JavaFX: os nós de layout e os eventos de teclado não precisam da tela.
 */
class AtalhosTest {

    /** Sobe o JavaFX uma vez (sem janela); sem tela disponível, o teste é pulado. */
    static void iniciarJavaFx() {
        try {
            javafx.application.Platform.startup(() -> { });
        } catch (IllegalStateException jaIniciado) {
            // já estava no ar
        } catch (UnsupportedOperationException semTela) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "JavaFX sem tela");
        }
    }

    private static KeyEvent tecla(KeyCode codigo, boolean ctrl) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", codigo, false, ctrl, false, false);
    }

    /** Dispara a tecla no nó, como o JavaFX faz: os filtros dos nós de cima para baixo até o alvo. */
    private static KeyEvent disparar(Node alvo, KeyCode codigo, boolean ctrl) {
        KeyEvent evento = tecla(codigo, ctrl);
        Event.fireEvent(alvo, evento);
        return evento;
    }

    /** F21.5: a barra e o rodapé saem do registro, com o texto curto, por grupo e na ordem do registro. */
    @Test
    void dicaDoRodapeSaiDoRegistro() {
        iniciarJavaFx(); // os rótulos (controles) pedem o JavaFX iniciado; nenhuma janela é aberta
        VBox raiz = new VBox();
        javafx.scene.control.Label rodape = new javafx.scene.control.Label("Enter no campo de busca pesquisa");
        rodape.getStyleClass().add(Atalhos.CLASSE_DICA);
        javafx.scene.control.Label sugestao = new javafx.scene.control.Label("Pedir: marque as linhas");
        sugestao.getStyleClass().add(Atalhos.CLASSE_DICA);
        sugestao.setUserData("sugestao");
        Pane semDica = new Pane();
        raiz.getChildren().addAll(semDica, rodape, sugestao);
        Atalhos.de(raiz)
                .em(Tecla.de(KeyCode.F4), "Ajustar o estoque do produto selecionado", () -> { }).curto("ajustar")
                .em(Tecla.de(KeyCode.F6), "Só na ajuda", () -> { })
                .em(Tecla.de(KeyCode.F5), "Calcular a sugestão", () -> { }).curto("calcula", "sugestao");
        Atalhos.de(semDica).em(Tecla.ctrl(KeyCode.N), "Novo", () -> { }).curto("novo");

        Atalhos.preencherDicas(raiz);
        assertThat(rodape.getText()).isEqualTo("F4 ajustar · Ctrl+N novo · Enter no campo de busca pesquisa");
        assertThat(sugestao.getText()).isEqualTo("F5 calcula · Pedir: marque as linhas");
        Atalhos.preencherDicas(raiz); // de novo: não repete o texto fixo
        assertThat(rodape.getText()).isEqualTo("F4 ajustar · Ctrl+N novo · Enter no campo de busca pesquisa");
        assertThat(Atalhos.dica(Atalhos.daTela(raiz), null, null)).isEqualTo("F4 ajustar · Ctrl+N novo");
        // A lista da ajuda continua com todos, inclusive os sem texto curto.
        assertThat(Atalhos.daTela(raiz)).extracting(Atalho::descricao).contains("Só na ajuda");
    }

    @Test
    void textoDaTeclaNaAjuda() {
        assertThat(Tecla.de(KeyCode.F4).texto()).isEqualTo("F4");
        assertThat(Tecla.ctrl(KeyCode.N).texto()).isEqualTo("Ctrl+N");
        assertThat(Tecla.ctrl(KeyCode.K).texto()).isEqualTo("Ctrl+K");
        assertThat(Tecla.de(KeyCode.ENTER).texto()).isEqualTo("Enter");
        assertThat(Tecla.de(KeyCode.ESCAPE).texto()).isEqualTo("Esc");
        assertThat(Tecla.de(KeyCode.UP).texto()).isEqualTo("↑");
        assertThat(Tecla.de(KeyCode.DOWN).texto()).isEqualTo("↓");
    }

    @Test
    void ctrlFazParteDaTecla() {
        assertThat(Tecla.ctrl(KeyCode.N).combina(tecla(KeyCode.N, true))).isTrue();
        assertThat(Tecla.ctrl(KeyCode.N).combina(tecla(KeyCode.N, false))).isFalse();
        assertThat(Tecla.de(KeyCode.F5).combina(tecla(KeyCode.F5, true))).isFalse();
        assertThat(Tecla.de(KeyCode.F5).combina(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, true, false,
                false, false))).as("Shift+F5").isFalse();
    }

    @Test
    void teclaRegistradaExecutaEConsome() {
        Pane raiz = new Pane();
        AtomicInteger novo = new AtomicInteger();
        Atalhos.de(raiz).em(Tecla.ctrl(KeyCode.N), "Novo", novo::incrementAndGet);

        KeyEvent ctrlN = tecla(KeyCode.N, true);
        assertThat(Atalhos.de(raiz).tratar(ctrlN)).isTrue();
        assertThat(ctrlN.isConsumed()).isTrue();
        assertThat(Atalhos.de(raiz).tratar(tecla(KeyCode.N, false))).isFalse();
        assertThat(Atalhos.de(raiz).tratar(tecla(KeyCode.F2, false))).isFalse();
        disparar(raiz, KeyCode.N, true); // pelo filtro instalado no nó
        assertThat(novo).hasValue(2);
    }

    @Test
    void condicaoFalsaDeixaATeclaPassar() {
        Pane raiz = new Pane();
        AtomicBoolean caixaAberto = new AtomicBoolean(false);
        AtomicInteger fechar = new AtomicInteger();
        Atalhos.de(raiz).em(Tecla.de(KeyCode.F12), "Fechar o caixa", caixaAberto::get, fechar::incrementAndGet);

        KeyEvent fechado = tecla(KeyCode.F12, false);
        assertThat(Atalhos.de(raiz).tratar(fechado)).isFalse();
        assertThat(fechado.isConsumed()).isFalse();
        disparar(raiz, KeyCode.F12, false);
        assertThat(fechar).hasValue(0);
        caixaAberto.set(true);
        disparar(raiz, KeyCode.F12, false);
        assertThat(fechar).hasValue(1);
    }

    @Test
    void mesmaTeclaComCondicoesDiferentes() {
        Pane raiz = new Pane();
        AtomicBoolean naSugestao = new AtomicBoolean(false);
        List<String> chamados = new ArrayList<>();
        Atalhos.de(raiz)
                .em(Tecla.ctrl(KeyCode.N), "Nova entrada", () -> !naSugestao.get(), () -> chamados.add("entrada"))
                .em(Tecla.de(KeyCode.F5), "Calcular", naSugestao::get, () -> chamados.add("sugestao"));

        disparar(raiz, KeyCode.F5, false);
        disparar(raiz, KeyCode.N, true);
        naSugestao.set(true);
        disparar(raiz, KeyCode.F5, false);
        disparar(raiz, KeyCode.N, true);
        assertThat(chamados).containsExactly("entrada", "sugestao");
    }

    @Test
    void registroDeUmNoFilhoSoValeComOFocoNele() {
        VBox raiz = new VBox();
        Pane tabela = new Pane();
        Pane campo = new Pane();
        raiz.getChildren().addAll(tabela, campo);
        AtomicInteger abrir = new AtomicInteger();
        Atalhos.de(tabela).em(Tecla.de(KeyCode.ENTER), "Abrir o selecionado", abrir::incrementAndGet);

        disparar(campo, KeyCode.ENTER, false);
        assertThat(abrir).hasValue(0);
        disparar(tabela, KeyCode.ENTER, false);
        assertThat(abrir).hasValue(1);
    }

    @Test
    void oMesmoNoTemUmRegistroSo() {
        Pane raiz = new Pane();
        assertThat(Atalhos.de(raiz)).isSameAs(Atalhos.de(raiz));
        AtomicInteger contador = new AtomicInteger();
        Atalhos.de(raiz).em(Tecla.de(KeyCode.F4), "Ajustar", contador::incrementAndGet);
        Atalhos.de(raiz).em(Tecla.de(KeyCode.F5), "Ajustar saldo", contador::incrementAndGet);
        disparar(raiz, KeyCode.F4, false);
        assertThat(contador).as("um filtro só: F4 conta uma vez").hasValue(1);
    }

    /**
     * Aceite da parte B: a lista da ajuda bate com os atalhos da tela. Cada atalho da lista, disparado no nó em que
     * foi registrado, executa exatamente a ação dele; e nenhuma tecla registrada fica fora da lista.
     */
    @Test
    void listaDaTelaBateComOQueAsTeclasFazem() {
        VBox raiz = new VBox();
        StackPane tabela = new StackPane();
        Pane campo = new Pane();
        VBox dentro = new VBox(campo);
        raiz.getChildren().addAll(tabela, dentro);
        Map<String, AtomicInteger> chamadas = new HashMap<>();
        Map<String, Node> onde = new HashMap<>();
        registrar(raiz, Tecla.de(KeyCode.F4), "Ajustar estoque", chamadas, onde);
        registrar(raiz, Tecla.ctrl(KeyCode.N), "Novo", chamadas, onde);
        registrar(tabela, Tecla.de(KeyCode.ENTER), "Abrir o selecionado", chamadas, onde);
        registrar(campo, Tecla.de(KeyCode.UP), "Item de cima", chamadas, onde);

        List<Atalho> lista = Atalhos.daTela(raiz);
        assertThat(lista).extracting(a -> a.tecla().texto() + " " + a.descricao())
                .containsExactly("F4 Ajustar estoque", "Ctrl+N Novo", "Enter Abrir o selecionado", "↑ Item de cima");
        for (Atalho atalho : lista) {
            disparar(onde.get(atalho.descricao()), atalho.tecla().codigo(), atalho.tecla().ctrl());
        }
        assertThat(chamadas).allSatisfy((descricao, vezes) -> assertThat(vezes).as(descricao).hasValue(1));
    }

    @Test
    void telaSemAtalhosDaListaVazia() {
        assertThat(Atalhos.daTela(new VBox(new Pane()))).isEmpty();
    }

    private static void registrar(Node no, Tecla tecla, String descricao, Map<String, AtomicInteger> chamadas,
                                  Map<String, Node> onde) {
        AtomicInteger vezes = new AtomicInteger();
        chamadas.put(descricao, vezes);
        onde.put(descricao, no);
        Atalhos.de(no).em(tecla, descricao, vezes::incrementAndGet);
    }
}
