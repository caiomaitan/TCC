package br.com.varesync;

import br.com.varesync.config.AppConfig;
import br.com.varesync.config.AppContext;
import br.com.varesync.config.Banco;
import br.com.varesync.config.FalhaConexaoException;
import br.com.varesync.controller.ConexaoController;
import br.com.varesync.controller.LoginController;
import br.com.varesync.controller.Navegacao;
import br.com.varesync.controller.PrincipalController;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Icone;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
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
 * Inicialização e troca de telas (3.4): lê/cria a config → testa a conexão (senão, tela de conexão)
 * → Flyway migrate → nenhum usuário? assistente de primeiro acesso → login → troca de senha obrigatória,
 * se marcada → janela principal.
 */
public class App extends Application implements Navegacao {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    static final double LARGURA_MINIMA = 1280;
    static final double ALTURA_MINIMA = 720;

    private Stage palco;
    private AppContext contexto;
    private PrincipalController principal;
    /** Usuário logado; nulo fora da janela principal e da troca de senha. */
    private Sessao sessao;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        TratadorExcecoes.instalar();
        Icone.instalar();
        log.info("Iniciando o VareSync (Java {})", System.getProperty("java.version"));
        palco = stage;
        palco.setTitle(Dialogos.TITULO);
        // Fechar a janela principal vale como Sair: pede confirmação e avisa se há caixa aberto (6.1).
        palco.setOnCloseRequest(e -> {
            if (principal != null && !principal.confirmarSaida()) {
                e.consume();
            }
        });
        iniciar();
        palco.show();
    }

    @Override
    public void stop() {
        encerrarPrincipal();
        if (sessao != null && contexto != null) {
            registrarLogout();
        }
        if (contexto != null) {
            contexto.close();
        }
        log.info("VareSync encerrado");
    }

    // ---- Navegacao ----

    @Override
    public void mostrarLogin(String loginSugerido) {
        encerrarPrincipal();
        sessao = null;
        LoginController controller = (LoginController) mostrar("/fxml/login.fxml", 440, 380, false);
        controller.sugerirLogin(loginSugerido);
    }

    @Override
    public void entrar(Sessao novaSessao) {
        sessao = novaSessao;
        log.info("Login de {} no terminal {}", novaSessao.usuario().login(), novaSessao.terminal());
        if (novaSessao.deveTrocarSenha()) {
            mostrar("/fxml/troca-senha.fxml", 520, 440, false);
        } else {
            principal = (PrincipalController) mostrar("/fxml/principal.fxml", LARGURA_MINIMA, ALTURA_MINIMA, true);
        }
    }

    @Override
    public void logoff() {
        String login = sessao == null ? "" : sessao.usuario().login();
        if (sessao != null) {
            registrarLogout();
        }
        mostrarLogin(login);
    }

    @Override
    public void sair() {
        Platform.exit();
    }

    // ---- Inicialização ----

    /** Conecta, migra e decide entre primeiro acesso e login, em segundo plano. */
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

        Task<Boolean> tarefa = new Task<>() {
            @Override
            protected Boolean call() {
                Banco.testarConexao(config);
                updateMessage("Atualizando o banco de dados…");
                contexto = AppContext.iniciar(config);
                return contexto.acessoService().precisaPrimeiroAcesso();
            }
        };
        tarefa.messageProperty().addListener((obs, anterior, atual) -> situacao.setText(atual));
        tarefa.setOnSucceeded(e -> {
            if (tarefa.getValue()) {
                log.info("Nenhum usuário cadastrado: assistente de primeiro acesso");
                mostrar("/fxml/primeiro-acesso.fxml", 560, 560, false);
            } else {
                mostrarLogin("");
            }
            // Métrica 9.4 (abertura até a tela de login): desde o início da JVM, com conexão e migrations.
            log.info("Primeira tela aberta em {} ms", ManagementFactory.getRuntimeMXBean().getUptime());
        });
        tarefa.setOnFailed(e -> {
            Throwable erro = tarefa.getException();
            fecharContexto();
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

    private void registrarLogout() {
        try {
            contexto.acessoService().sair(sessao);
        } catch (RuntimeException e) {
            // Não impede o log off nem o encerramento; fica no log.
            log.error("Falha ao registrar o logout de {}", sessao.usuario().login(), e);
        }
        sessao = null;
    }

    private void fecharContexto() {
        if (contexto != null) {
            contexto.close();
            contexto = null;
        }
    }

    private void encerrarPrincipal() {
        if (principal != null) {
            principal.encerrar();
            principal = null;
        }
    }

    // ---- Telas ----

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

    /** Carrega a tela com os controllers do {@link AppContext} e devolve o controller da raiz. */
    private Object mostrar(String fxml, double largura, double altura, boolean janelaPrincipal) {
        Object[] raizController = new Object[1];
        Parent raiz = carregar(fxml, tipo -> {
            Object controller = contexto.criarController(tipo, this, sessao);
            if (raizController[0] == null) {
                raizController[0] = controller;
            }
            return controller;
        });
        trocarCena(raiz, largura, altura, janelaPrincipal);
        return raizController[0];
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
        cena.getStylesheets().add(App.class.getResource(Dialogos.CSS).toExternalForm());
        palco.setMinWidth(janelaPrincipal ? LARGURA_MINIMA : 0);
        palco.setMinHeight(janelaPrincipal ? ALTURA_MINIMA : 0);
        palco.setResizable(janelaPrincipal);
        palco.setScene(cena);
        palco.setWidth(largura);
        palco.setHeight(altura);
        palco.centerOnScreen();
    }
}
