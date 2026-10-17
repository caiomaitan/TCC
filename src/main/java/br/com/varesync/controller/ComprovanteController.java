package br.com.varesync.controller;

import br.com.varesync.model.Comprovante;
import br.com.varesync.report.ComprovantePdf;
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
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Comprovante não fiscal em tela (texto de largura fixa) e "Salvar PDF…" (6.6). O PDF é gerado em {@link Task}:
 * na primeira vez o JasperReports compila o modelo.
 */
public class ComprovanteController {

    private final ComprovantePdf comprovantePdf;
    private Comprovante comprovante;

    @FXML private VBox raiz;
    @FXML private TextArea texto;
    @FXML private Button botaoPdf;
    @FXML private ProgressIndicator progresso;
    @FXML private Button botaoFechar;
    @FXML private Label mensagem;

    public ComprovanteController(ComprovantePdf comprovantePdf) {
        this.comprovantePdf = comprovantePdf;
    }

    public void mostrar(Comprovante comprovanteVenda) {
        this.comprovante = comprovanteVenda;
        texto.setText(comprovanteVenda.texto());
        Platform.runLater(botaoFechar::requestFocus);
    }

    @FXML
    private void salvarPdf() {
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Salvar comprovante em PDF");
        escolha.setInitialFileName("comprovante-venda-" + comprovante.vendaId() + ".pdf");
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
