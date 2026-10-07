package br.com.varesync;

import br.com.varesync.config.AppConfig;
import br.com.varesync.config.AppContext;
import br.com.varesync.config.Banco;
import br.com.varesync.config.FalhaConexaoException;
import br.com.varesync.controller.ConexaoController;
import br.com.varesync.controller.PrincipalController;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inicialização e troca de telas.
 * Sequência: lê/cria a config → testa a conexão (senão, tela de conexão) → Flyway migrate → janela principal.
 */
public class App extends Application {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    static final double LARGURA_MINIMA = 1280;
    static final double ALTURA_MINIMA = 720;
    private static final String CSS = "/css/app.css";

    private Stage palco;
    private AppContext contexto;
    private PrincipalController principal;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        TratadorExcecoes.instalar();
        log.info("Iniciando o VareSync (Java {})", System.getProperty("java.version"));
        palco = stage;
        palco.setTitle(Dialogos.TITULO);
        iniciar();
        palco.show();
    }

    @Override
    public void stop() {
        if (principal != null) {
            principal.encerrar();
        }
        if (contexto != null) {
            contexto.close();
        }
        log.info("VareSync encerrado");
    }

    /** Conecta e migra em segundo plano; a tela mostra o progresso enquanto isso. */
    private void iniciar() {
        Label situacao = mostrarCarregando("Conectando ao banco de dados…");
        AppConfig config;
        try {
            config = AppConfig.carregar(AppConfig.arquivoPadrao());
        } catch (UncheckedIOException e) {
            log.error("Falha ao ler a configuração local", e);
            Dialogos.alerta(Alert.AlertType.ERROR, "Não foi possível ler a configuração em "
                    + AppConfig.arquivoPadrao() + ". Os detalhes foram registrados no log.");
            Platform.exit();
            return;
        }
        log.info("Configuração: {}", config);

        Task<AppContext> tarefa = new Task<>() {
            @Override
            protected AppContext call() {
                Banco.testarConexao(config);
                updateMessage("Atualizando o banco de dados…");
                return AppContext.iniciar(config);
            }
        };
        tarefa.messageProperty().addListener((obs, anterior, atual) -> situacao.setText(atual));
        tarefa.setOnSucceeded(e -> {
            contexto = tarefa.getValue();
            mostrarPrincipal();
        });
        tarefa.setOnFailed(e -> {
            Throwable erro = tarefa.getException();
            if (erro instanceof FalhaConexaoException) {
                mostrarConexao(config, erro.getMessage());
            } else {
                log.error("Falha ao preparar o banco de dados", erro);
                mostrarConexao(config, "Não foi possível preparar o banco de dados. "
                        + "Os detalhes foram registrados no log.");
            }
        });
        Tarefas.executar("inicializacao", tarefa);
    }

    private Label mostrarCarregando(String mensagem) {
        ProgressIndicator progresso = new ProgressIndicator();
        Label texto = new Label(mensagem);
        VBox caixa = new VBox(16, progresso, texto);
        caixa.setAlignment(Pos.CENTER);
        caixa.getStyleClass().add("tela-carregando");
        trocarCena(caixa, 480, 320, false);
        return texto;
    }

    private void mostrarConexao(AppConfig config, String erro) {
        ConexaoController controller = new ConexaoController(config, salvo -> iniciar());
        Parent raiz = carregar("/fxml/conexao.fxml", tipo -> controller);
        if (erro != null) {
            controller.exibirErro(erro);
        }
        trocarCena(raiz, 560, 440, false);
    }

    private void mostrarPrincipal() {
        Parent raiz = carregar("/fxml/principal.fxml", tipo -> {
            Object controller = contexto.criarController(tipo);
            if (controller instanceof PrincipalController p) {
                principal = p;
            }
            return controller;
        });
        trocarCena(raiz, LARGURA_MINIMA, ALTURA_MINIMA, true);
    }

    private Parent carregar(String fxml, Callback<Class<?>, Object> fabrica) {
        URL recurso = App.class.getResource(fxml);
        FXMLLoader loader = new FXMLLoader(recurso);
        loader.setControllerFactory(fabrica);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao carregar " + fxml, e);
        }
    }

    private void trocarCena(Parent raiz, double largura, double altura, boolean janelaPrincipal) {
        Scene cena = new Scene(raiz, largura, altura);
        cena.getStylesheets().add(App.class.getResource(CSS).toExternalForm());
        palco.setMinWidth(janelaPrincipal ? LARGURA_MINIMA : 0);
        palco.setMinHeight(janelaPrincipal ? ALTURA_MINIMA : 0);
        palco.setResizable(janelaPrincipal);
        palco.setScene(cena);
        palco.setWidth(largura);
        palco.setHeight(altura);
        palco.centerOnScreen();
    }
}
