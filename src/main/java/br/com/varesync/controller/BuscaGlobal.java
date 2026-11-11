package br.com.varesync.controller;

import br.com.varesync.model.GrupoBusca;
import br.com.varesync.model.ItemBusca;
import br.com.varesync.service.BuscaGlobalService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Atalhos;
import br.com.varesync.ui.Atalhos.Tecla;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Janela da busca global (Ctrl+K, F21.3): procura enquanto se digita (a partir de 2 caracteres, fora da FX
 * Application Thread), com os resultados agrupados (telas, produtos, clientes, vendas, fornecedores; até 8 por
 * grupo). Enter abre o resultado selecionado (ou o primeiro), Esc fecha; ↑ e ↓ andam pela lista sem sair do campo.
 * Quem abre o resultado é a janela principal.
 */
final class BuscaGlobal {

    /** O resultado escolhido: um registro ({@code item}) ou uma tela do menu ({@code tela}). */
    record Escolha(ItemBusca item, ItemMenu tela) {
    }

    /** Linha da lista: o título de um grupo ({@code grupo} preenchido) ou um resultado. */
    record Linha(String grupo, ItemBusca item, ItemMenu tela) {

        boolean ehGrupo() {
            return grupo != null;
        }
    }

    private static final Duration ESPERA = Duration.millis(200);

    private final BuscaGlobalService service;
    private final Sessao sessao;
    private final Stage janela = new Stage();
    private final TextField campo = new TextField();
    private final Label situacao = new Label();
    private final ListView<Linha> lista = new ListView<>();
    private final PauseTransition espera = new PauseTransition(ESPERA);
    private int consulta;
    private Escolha escolhida;

    private BuscaGlobal(Window dona, BuscaGlobalService service, Sessao sessao) {
        this.service = service;
        this.sessao = sessao;
        janela.initOwner(dona);
        janela.initModality(Modality.WINDOW_MODAL);
        janela.setTitle(Dialogos.TITULO + " — Buscar");

        campo.setPromptText("Produto, código de barras, cliente, CPF, nº da venda, fornecedor ou tela");
        campo.getStyleClass().add("busca-campo");
        situacao.getStyleClass().add("subtitulo");
        situacao.setWrapText(true);
        situacao.setMinHeight(Region.USE_PREF_SIZE);
        lista.getStyleClass().add("busca-lista");
        lista.setPlaceholder(new Label("Os resultados aparecem aqui."));
        lista.setFocusTraversable(false);
        lista.setCellFactory(l -> new Celula());
        lista.getSelectionModel().selectedIndexProperty().addListener((o, antes, depois) -> pularGrupo(
                antes.intValue(), depois.intValue()));
        lista.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                escolher();
            }
        });

        VBox raiz = new VBox(8, campo, situacao, lista);
        raiz.getStyleClass().add("busca-global");
        raiz.setPadding(new Insets(14));
        VBox.setVgrow(lista, javafx.scene.layout.Priority.ALWAYS);
        Atalhos.de(raiz)
                .em(Tecla.de(KeyCode.ENTER), "Abrir o resultado selecionado", this::escolher)
                .em(Tecla.de(KeyCode.ESCAPE), "Fechar a busca", janela::close)
                .em(Tecla.de(KeyCode.DOWN), "Próximo resultado", () -> mover(1))
                .em(Tecla.de(KeyCode.UP), "Resultado anterior", () -> mover(-1));

        Scene cena = new Scene(raiz);
        URL css = BuscaGlobal.class.getResource(Dialogos.CSS);
        if (css != null) {
            cena.getStylesheets().add(css.toExternalForm());
        }
        janela.setScene(cena);
        janela.setOnHidden(e -> espera.stop());

        espera.setOnFinished(e -> procurar());
        campo.textProperty().addListener((o, antes, depois) -> espera.playFromStart());
        mostrarSituacao();
    }

    /** Abre a busca e espera ela fechar; vazio se a pessoa fechou sem escolher. */
    static Optional<Escolha> abrir(Window dona, BuscaGlobalService service, Sessao sessao) {
        BuscaGlobal busca = new BuscaGlobal(dona, service, sessao);
        Platform.runLater(busca.campo::requestFocus);
        busca.janela.showAndWait();
        return Optional.ofNullable(busca.escolhida);
    }

    private void mostrarSituacao() {
        situacao.setText("Digite ao menos " + BuscaGlobalService.MINIMO + " letras ou números. Enter abre, Esc fecha.");
    }

    private void procurar() {
        String texto = campo.getText() == null ? "" : campo.getText();
        int minha = ++consulta;
        if (texto.strip().length() < BuscaGlobalService.MINIMO) {
            lista.getItems().clear();
            mostrarSituacao();
            return;
        }
        situacao.setText("Procurando…");
        Task<List<ItemBusca>> tarefa = new Task<>() {
            @Override
            protected List<ItemBusca> call() {
                return service.buscar(sessao, texto);
            }
        };
        tarefa.setOnSucceeded(e -> {
            if (minha == consulta) {
                montar(texto, BuscaTelas.filtrar(texto, sessao), tarefa.getValue());
            }
        });
        tarefa.setOnFailed(e -> {
            if (minha == consulta) {
                lista.getItems().clear();
                situacao.setText(TratadorExcecoes.mensagem(tarefa.getException()));
            }
        });
        Tarefas.executar("busca-global", tarefa);
    }

    private void montar(String texto, List<ItemMenu> telas, List<ItemBusca> itens) {
        List<Linha> linhas = new ArrayList<>();
        if (!telas.isEmpty()) {
            linhas.add(new Linha(GrupoBusca.TELAS.rotulo(), null, null));
            telas.forEach(t -> linhas.add(new Linha(null, null, t)));
        }
        GrupoBusca grupoAtual = null;
        for (ItemBusca item : itens) {
            if (item.grupo() != grupoAtual) {
                grupoAtual = item.grupo();
                linhas.add(new Linha(grupoAtual.rotulo(), null, null));
            }
            linhas.add(new Linha(null, item, null));
        }
        lista.getItems().setAll(linhas);
        long resultados = linhas.stream().filter(l -> !l.ehGrupo()).count();
        if (resultados == 0) {
            situacao.setText("Nada encontrado para “" + texto.strip() + "”.");
            return;
        }
        situacao.setText(resultados == 1 ? "1 resultado. Enter abre, Esc fecha."
                : resultados + " resultados. ↑ e ↓ escolhem, Enter abre, Esc fecha.");
        lista.getSelectionModel().select(1); // a linha 0 é o título do primeiro grupo
        lista.scrollTo(0);
    }

    /** Ninguém fica no título de um grupo: a seleção passa para o resultado seguinte (ou anterior, subindo). */
    private void pularGrupo(int antes, int depois) {
        if (depois < 0 || depois >= lista.getItems().size() || !lista.getItems().get(depois).ehGrupo()) {
            return;
        }
        int passo = depois < antes ? -1 : 1;
        int alvo = depois + passo;
        if (alvo < 0 || alvo >= lista.getItems().size()) {
            alvo = depois - passo;
        }
        int destino = alvo;
        Platform.runLater(() -> lista.getSelectionModel().select(destino));
    }

    private void mover(int passo) {
        int tamanho = lista.getItems().size();
        if (tamanho == 0) {
            return;
        }
        int atual = lista.getSelectionModel().getSelectedIndex();
        int alvo = atual + passo;
        while (alvo >= 0 && alvo < tamanho && lista.getItems().get(alvo).ehGrupo()) {
            alvo += passo;
        }
        if (alvo >= 0 && alvo < tamanho) {
            lista.getSelectionModel().select(alvo);
            lista.scrollTo(Math.max(0, alvo - 1));
        }
    }

    /** Enter: o resultado selecionado ou, sem seleção, o primeiro. A busca que ainda está rodando é descartada. */
    private void escolher() {
        Linha linha = lista.getSelectionModel().getSelectedItem();
        if (linha == null || linha.ehGrupo()) {
            linha = lista.getItems().stream().filter(l -> !l.ehGrupo()).findFirst().orElse(null);
        }
        if (linha == null) {
            return;
        }
        consulta++;
        escolhida = new Escolha(linha.item(), linha.tela());
        janela.close();
    }

    private static final class Celula extends ListCell<Linha> {

        @Override
        protected void updateItem(Linha linha, boolean vazia) {
            super.updateItem(linha, vazia);
            getStyleClass().remove("busca-grupo");
            setText(null);
            setGraphic(null);
            setMouseTransparent(false);
            if (vazia || linha == null) {
                return;
            }
            if (linha.ehGrupo()) {
                getStyleClass().add("busca-grupo");
                setText(linha.grupo());
                setMouseTransparent(true);
                return;
            }
            String titulo = linha.tela() != null ? linha.tela().rotulo() : linha.item().titulo();
            String detalhe = linha.tela() != null ? "Abrir a tela " + linha.tela().rotulo()
                    : linha.item().detalhe();
            Label rotuloTitulo = new Label(titulo);
            rotuloTitulo.getStyleClass().add("busca-titulo");
            Label rotuloDetalhe = new Label(detalhe);
            rotuloDetalhe.getStyleClass().add("busca-detalhe");
            setGraphic(new VBox(2, rotuloTitulo, rotuloDetalhe));
        }
    }
}
