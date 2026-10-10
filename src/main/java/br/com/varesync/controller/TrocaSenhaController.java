package br.com.varesync.controller;

import br.com.varesync.service.AcessoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/** Troca de senha obrigatória antes de entrar ({@code deve_trocar_senha}). Esc volta ao login. */
public class TrocaSenhaController {

    private final AcessoService acessoService;
    private final Navegacao navegacao;
    private final Sessao sessao;

    @FXML private VBox formulario;
    @FXML private Label saudacao;
    @FXML private PasswordField campoAtual;
    @FXML private PasswordField campoNova;
    @FXML private PasswordField campoConfirmacao;
    @FXML private Label mensagem;

    public TrocaSenhaController(AcessoService acessoService, Navegacao navegacao, Sessao sessao) {
        this.acessoService = acessoService;
        this.navegacao = navegacao;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        saudacao.setText("Olá, " + sessao.usuario().nome() + ". A sua senha é provisória: defina uma senha nova "
                + "para continuar.");
        Platform.runLater(campoAtual::requestFocus);
    }

    @FXML
    private void confirmar() {
        String atual = campoAtual.getText();
        String nova = campoNova.getText();
        String confirmacao = campoConfirmacao.getText();
        Task<Sessao> tarefa = new Task<>() {
            @Override
            protected Sessao call() {
                return acessoService.trocarSenha(sessao, atual, nova, confirmacao);
            }
        };
        formulario.setDisable(true);
        Mensagem.informar(mensagem, "Gravando…");
        tarefa.setOnSucceeded(e -> navegacao.entrar(tarefa.getValue()));
        tarefa.setOnFailed(e -> {
            formulario.setDisable(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("troca-senha", tarefa);
    }

    @FXML
    private void cancelar() {
        navegacao.logoff();
    }
}
