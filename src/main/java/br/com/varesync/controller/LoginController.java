package br.com.varesync.controller;

import br.com.varesync.service.AcessoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Textos;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Tela de login (Fig. 14 revisada): Usuário e Senha; Enter entra, Esc sai; erro abaixo dos campos. */
public class LoginController {

    private final AcessoService acessoService;
    private final Navegacao navegacao;

    @FXML private VBox formulario;
    @FXML private Label nomeLoja;
    @FXML private TextField campoLogin;
    @FXML private PasswordField campoSenha;
    @FXML private Label mensagem;

    public LoginController(AcessoService acessoService, Navegacao navegacao) {
        this.acessoService = acessoService;
        this.navegacao = navegacao;
    }

    @FXML
    private void initialize() {
        nomeLoja.setText(acessoService.nomeEmpresa());
        Platform.runLater(() -> (Textos.vazio(campoLogin.getText()) ? campoLogin : campoSenha).requestFocus());
    }

    public void sugerirLogin(String login) {
        campoLogin.setText(login == null ? "" : login);
    }

    @FXML
    private void entrar() {
        String login = campoLogin.getText();
        String senha = campoSenha.getText();
        Task<Sessao> tarefa = new Task<>() {
            @Override
            protected Sessao call() {
                return acessoService.entrar(login, senha);
            }
        };
        formulario.setDisable(true);
        Mensagem.informar(mensagem, "Entrando…");
        tarefa.setOnSucceeded(e -> navegacao.entrar(tarefa.getValue()));
        tarefa.setOnFailed(e -> {
            formulario.setDisable(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
            campoSenha.clear();
            (Textos.vazio(campoLogin.getText()) ? campoLogin : campoSenha).requestFocus();
        });
        Tarefas.executar("login", tarefa);
    }

    @FXML
    private void sair() {
        navegacao.sair();
    }
}
