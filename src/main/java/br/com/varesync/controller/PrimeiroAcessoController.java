package br.com.varesync.controller;

import br.com.varesync.model.PoliticaSenha;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.AcessoService.PrimeiroAcesso;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Textos;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/** Assistente de primeiro acesso (6.1): nome da loja e o supervisor. Não existe senha padrão. */
public class PrimeiroAcessoController {

    private final AcessoService acessoService;
    private final Navegacao navegacao;

    @FXML private VBox formulario;
    @FXML private TextField campoLoja;
    @FXML private TextField campoNome;
    @FXML private TextField campoLogin;
    @FXML private PasswordField campoSenha;
    @FXML private PasswordField campoConfirmacao;
    @FXML private Label mensagem;

    public PrimeiroAcessoController(AcessoService acessoService, Navegacao navegacao) {
        this.acessoService = acessoService;
        this.navegacao = navegacao;
    }

    @FXML
    private void initialize() {
        PoliticaSenha politica = acessoService.politicaSenha();
        campoSenha.setPromptText("ao menos " + politica.tamanhoMinimo() + " caracteres");
        campoSenha.setTooltip(new Tooltip(politica.descricao()));
        Platform.runLater(campoLoja::requestFocus);
    }

    @FXML
    private void criar() {
        PrimeiroAcesso dados = new PrimeiroAcesso(campoLoja.getText(), campoNome.getText(), campoLogin.getText(),
                campoSenha.getText(), campoConfirmacao.getText());
        String login = Textos.normalizarLogin(campoLogin.getText());
        Task<Long> tarefa = new Task<>() {
            @Override
            protected Long call() {
                return acessoService.criarPrimeiroSupervisor(dados);
            }
        };
        formulario.setDisable(true);
        Mensagem.informar(mensagem, "Criando o supervisor…");
        tarefa.setOnSucceeded(e -> {
            Dialogos.alerta(Alert.AlertType.INFORMATION,
                    "Supervisor criado. Entre com o login \"" + login + "\" e a senha que você definiu.");
            navegacao.mostrarLogin(login);
        });
        tarefa.setOnFailed(e -> {
            formulario.setDisable(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("primeiro-acesso", tarefa);
    }

    @FXML
    private void sair() {
        navegacao.sair();
    }
}
