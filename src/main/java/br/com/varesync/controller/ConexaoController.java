package br.com.varesync.controller;

import br.com.varesync.config.AppConfig;
import br.com.varesync.config.Banco;
import br.com.varesync.config.ConexaoTestada;
import br.com.varesync.config.FalhaConexaoException;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.util.Textos;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tela "Conexão com o banco": URL, usuário, senha, Testar conexão (com a versão do servidor e o tempo de resposta,
 * F11.5) e Salvar.
 */
public class ConexaoController {

    private static final Logger log = LoggerFactory.getLogger(ConexaoController.class);
    private static final String ESTILO_ERRO = "mensagem-erro";
    private static final String ESTILO_SUCESSO = "mensagem-sucesso";

    private final AppConfig config;
    private final Consumer<AppConfig> aoSalvar;

    @FXML private VBox formulario;
    @FXML private TextField campoUrl;
    @FXML private TextField campoUsuario;
    @FXML private PasswordField campoSenha;
    @FXML private Button botaoTestar;
    @FXML private Button botaoSalvar;
    @FXML private Label mensagem;

    public ConexaoController(AppConfig config, Consumer<AppConfig> aoSalvar) {
        this.config = config;
        this.aoSalvar = aoSalvar;
    }

    @FXML
    private void initialize() {
        campoUrl.setText(config.url());
        campoUsuario.setText(config.usuario());
        campoSenha.setText(config.senha());
        Platform.runLater(() -> (config.senha().isEmpty() ? campoSenha : campoUrl).requestFocus());
    }

    /** Mensagem exibida ao abrir a tela (motivo da falha de conexão). */
    public void exibirErro(String texto) {
        mostrar(texto, ESTILO_ERRO);
    }

    @FXML
    private void testar() {
        AppConfig informado = lerCampos();
        if (informado == null) {
            return;
        }
        executar(informado, false);
    }

    @FXML
    private void salvar() {
        AppConfig informado = lerCampos();
        if (informado == null) {
            return;
        }
        executar(informado, true);
    }

    @FXML
    private void sair() {
        Platform.exit();
    }

    private void executar(AppConfig informado, boolean salvarDepois) {
        Task<ConexaoTestada> tarefa = new Task<>() {
            @Override
            protected ConexaoTestada call() {
                ConexaoTestada testada = Banco.testarConexao(informado);
                if (salvarDepois) {
                    informado.salvar();
                }
                return testada;
            }
        };
        formulario.setDisable(true);
        mostrar("Conectando…", null);
        tarefa.setOnSucceeded(e -> {
            formulario.setDisable(false);
            if (salvarDepois) {
                mostrar("Configuração salva.", ESTILO_SUCESSO);
                aoSalvar.accept(informado);
            } else {
                mostrar(tarefa.getValue().descricao(), ESTILO_SUCESSO); // versão do servidor e tempo (F11.5)
            }
        });
        tarefa.setOnFailed(e -> {
            formulario.setDisable(false);
            Throwable erro = tarefa.getException();
            if (erro instanceof FalhaConexaoException) {
                mostrar(erro.getMessage(), ESTILO_ERRO);
            } else {
                log.error("Falha ao testar ou salvar a conexão", erro);
                mostrar("Não foi possível salvar a configuração. Os detalhes foram registrados no log.", ESTILO_ERRO);
            }
        });
        Tarefas.executar("conexao", tarefa);
    }

    private AppConfig lerCampos() {
        if (Textos.vazio(campoUrl.getText())) {
            mostrar("Informe o endereço do banco.", ESTILO_ERRO);
            campoUrl.requestFocus();
            return null;
        }
        if (Textos.vazio(campoUsuario.getText())) {
            mostrar("Informe o usuário do banco.", ESTILO_ERRO);
            campoUsuario.requestFocus();
            return null;
        }
        return config.comConexao(campoUrl.getText().strip(), campoUsuario.getText().strip(), campoSenha.getText());
    }

    private void mostrar(String texto, String estilo) {
        mensagem.setText(texto);
        mensagem.getStyleClass().removeAll(ESTILO_ERRO, ESTILO_SUCESSO);
        if (estilo != null) {
            mensagem.getStyleClass().add(estilo);
        }
    }
}
