package br.com.varesync.controller;

import br.com.varesync.model.ComprovanteImpresso;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import java.io.File;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Comprovante não fiscal em tela (texto de largura fixa), Imprimir na impressora padrão (F5) e "Salvar PDF…" (6.6),
 * da venda ou da devolução (F13.2).
 * Impressão e PDF rodam em {@link Task}:
 * na primeira vez o JasperReports compila o modelo.
 */
public class ComprovanteController {

    private final ComprovantePdf comprovantePdf;
    private final ImpressaoComprovante impressao;
    private ComprovanteImpresso comprovante;

    @FXML private VBox raiz;
    @FXML private TextArea texto;
    @FXML private Button botaoImprimir;
    @FXML private Button botaoPdf;
    @FXML private ProgressIndicator progresso;
    @FXML private Button botaoFechar;
    @FXML private Label mensagem;

    public ComprovanteController(ComprovantePdf comprovantePdf, ImpressaoComprovante impressao) {
        this.impressao = impressao;
        this.comprovantePdf = comprovantePdf;
    }

    /** Abre o comprovante (da venda ou da devolução) numa janela modal e espera ela fechar. */
    public static void abrir(Window dona, ComprovanteImpresso comprovante, ComprovantePdf comprovantePdf,
                             ImpressaoComprovante impressao) {
        Janelas.abrirModal(dona, "/fxml/comprovante.fxml", comprovante.titulo(),
                tipo -> new ComprovanteController(comprovantePdf, impressao),
                (ComprovanteController c) -> c.mostrar(comprovante));
    }

    public void mostrar(ComprovanteImpresso exibido) {
        this.comprovante = exibido;
        texto.setText(exibido.texto());
        raiz.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F5) {
                e.consume();
                imprimir();
            }
        });
        Platform.runLater(botaoFechar::requestFocus);
    }

    /** Imprime na impressora padrão do Windows, em {@link Task}. */
    @FXML
    private void imprimir() {
        if (botaoImprimir.isDisabled()) {
            return;
        }
        Task<String> tarefa = new Task<>() {
            @Override
            protected String call() {
                return impressao.imprimir(comprovante);
            }
        };
        botaoImprimir.setDisable(true);
        progresso.setVisible(true);
        Mensagem.informar(mensagem, "Enviando para a impressora…");
        tarefa.setOnSucceeded(e -> {
            botaoImprimir.setDisable(false);
            progresso.setVisible(false);
            Mensagem.sucesso(mensagem, "Comprovante enviado para a impressora " + tarefa.getValue() + ".");
        });
        tarefa.setOnFailed(e -> {
            botaoImprimir.setDisable(false);
            progresso.setVisible(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("comprovante-impressao", tarefa);
    }

    @FXML
    private void salvarPdf() {
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Salvar comprovante em PDF");
        escolha.setInitialFileName(comprovante.nomeArquivo());
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File arquivo = escolha.showSaveDialog(raiz.getScene().getWindow());
        if (arquivo == null) {
            return;
        }
        Path destino = arquivo.toPath();
        Task<Void> tarefa = new Task<>() {
            @Override
            protected Void call() {
                comprovantePdf.salvar(comprovante, destino);
                return null;
            }
        };
        botaoPdf.setDisable(true);
        progresso.setVisible(true);
        tarefa.setOnSucceeded(e -> {
            botaoPdf.setDisable(false);
            progresso.setVisible(false);
            Mensagem.sucesso(mensagem, "PDF salvo em " + destino + ".");
        });
        tarefa.setOnFailed(e -> {
            botaoPdf.setDisable(false);
            progresso.setVisible(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("comprovante-pdf", tarefa);
    }

    @FXML
    private void fechar() {
        ((Stage) raiz.getScene().getWindow()).close();
    }
}
