package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.App;
import br.com.varesync.BancoTeste;
import br.com.varesync.config.AppConfig;
import br.com.varesync.controller.ItemMenu;
import br.com.varesync.service.DadosTela;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base dos testes de interface (F11.3, perfil {@code -Pui}): o padrão dos roteiros de tela das fases 10 a 21.
 *
 * <ul>
 *   <li><b>Sem o robô do sistema operacional:</b> nenhuma tecla nem clique de verdade. Os componentes do JavaFX são
 *       acionados direto ({@code Button.fire()}, {@code TextField.setText} + {@code ActionEvent}, {@code KeyEvent}
 *       disparado no nó). Teclas do sistema atingiriam o programa que estivesse na frente.</li>
 *   <li><b>Janelas fora da tela:</b> toda janela nova (inclusive diálogos e popups) vai para x/y = -4000, com
 *       opacidade 0, e volta para lá se o sistema tentar centralizá-la.</li>
 *   <li><b>Conferência por captura</b> ({@code Scene.snapshot}), gravada em {@code target/telas/<teste>/}, fora do
 *       repositório.</li>
 *   <li><b>Só o banco {@code varesync_test}:</b> a pasta de usuário é a temporária do perfil
 *       ({@code target/ui-home}), a configuração é gravada sem BOM e a URL é conferida antes de abrir o sistema; fora
 *       disso o teste para sem abrir nada.</li>
 * </ul>
 */
abstract class TelaBase {

    static final long ESPERA_MS = 30_000;
    private static boolean javafxIniciado;

    static DataSource dataSource;
    DadosTela dados;
    private App app;
    private Stage palco;
    private final List<Throwable> errosNaTela = new CopyOnWriteArrayList<>();
    private int capturas;

    @BeforeAll
    static void prepararAmbiente() throws Exception {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (!home.getFileName().toString().equals("ui-home") || !home.getParent().getFileName().toString()
                .equals("target")) {
            throw new IllegalStateException("Os testes de interface só rodam com a pasta de usuário temporária "
                    + "(mvn -Pui verify, que usa target/ui-home); user.home = " + home);
        }
        dataSource = BancoTeste.dataSource(); // limpa e migra o varesync_test na primeira chamada
        iniciarJavafx();
    }

    private static synchronized void iniciarJavafx() throws Exception {
        if (javafxIniciado) {
            return;
        }
        CompletableFuture<Void> iniciou = new CompletableFuture<>();
        try {
            Platform.startup(() -> iniciou.complete(null));
        } catch (IllegalStateException jaIniciado) {
            iniciou.complete(null);
        }
        iniciou.get(30, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        fx(() -> {
            Window.getWindows().addListener((ListChangeListener<Window>) m -> {
                while (m.next()) {
                    m.getAddedSubList().forEach(TelaBase::esconder);
                }
            });
            return null;
        });
        javafxIniciado = true;
    }

    @BeforeEach
    void prepararDados() throws Exception {
        dados = new DadosTela(dataSource);
        dados.limpar();
    }

    @AfterEach
    void fecharSistema() throws Exception {
        try {
            if (app != null) {
                fx(() -> {
                    List<Window> abertas = new ArrayList<>(Window.getWindows());
                    Collections.reverse(abertas);
                    abertas.forEach(Window::hide);
                    app.stop(); // registra o logout e fecha o pool; não faz o backup automático (só o Sair faz)
                    return null;
                });
            }
        } finally {
            app = null;
            Files.deleteIfExists(AppConfig.arquivoPadrao()); // a configuração temporária tem a senha
        }
        assertThat(errosNaTela).as("exceções na tela").isEmpty();
    }

    // ----- Abrir o sistema -----

    /** Grava a configuração temporária, sem BOM, e confere que ela aponta para o banco de teste. */
    void escreverConfiguracao(String url) throws IOException {
        BancoTeste.Credenciais cr = BancoTeste.credenciais();
        Path config = AppConfig.arquivoPadrao();
        Files.createDirectories(config.getParent());
        Properties p = new Properties();
        p.setProperty("db.url", url);
        p.setProperty("db.usuario", cr.usuario());
        p.setProperty("db.senha", cr.senha());
        p.setProperty("terminal.id", DadosTela.TERMINAL);
        try (Writer w = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) { // sem BOM
            p.store(w, null);
        }
        conferirTravaDaUrl();
    }

    /**
     * Endereço do banco da banca num servidor que não existe (porta 1 do próprio computador, conexão recusada na hora):
     * o único endereço fora do {@code varesync_test} que a trava aceita, para conferir que a 2.0 recusa o
     * {@code varesync_demo}. Se a recusa falhasse, a conexão também falharia, e nenhum banco seria aberto.
     */
    static final String URL_BANCA_SEM_SERVIDOR = "jdbc:postgresql://127.0.0.1:1/varesync_demo";

    /**
     * Trava de URL: só abre o sistema se a configuração lida for a do {@code varesync_test} (ou exatamente a
     * {@link #URL_BANCA_SEM_SERVIDOR}, que não chega a banco nenhum).
     */
    static void conferirTravaDaUrl() {
        String url = AppConfig.carregar(AppConfig.arquivoPadrao()).url();
        if (!url.endsWith("/varesync_test") && !url.equals(URL_BANCA_SEM_SERVIDOR)) {
            throw new IllegalStateException("A configuração não aponta para o varesync_test; nada foi aberto: " + url);
        }
    }

    /** Abre o sistema inteiro ({@code App.start}) numa janela fora da tela. */
    void abrirSistema() throws Exception {
        abrirSistema(br.com.varesync.config.AppContext.Ajustes.NENHUM);
    }

    /** Com as peças do roteiro (F23.6): a SEFAZ falsa e a impressora que não imprime. */
    void abrirSistema(br.com.varesync.config.AppContext.Ajustes ajustes) throws Exception {
        conferirTravaDaUrl();
        fx(() -> {
            palco = new Stage();
            esconder(palco);
            app = new App(ajustes);
            app.start(palco);
            // Depois do start (o App instala o tratador dele): exceção na thread do JavaFX vira falha do teste.
            Thread.currentThread().setUncaughtExceptionHandler((th, e) -> {
                e.printStackTrace();
                errosNaTela.add(e);
            });
            return null;
        });
    }

    /** Configuração do banco de teste + sistema aberto + login; devolve a janela principal. */
    Scene abrirEEntrar(String login, String senha) throws Exception {
        escreverConfiguracao(BancoTeste.credenciais().url());
        abrirSistema();
        return entrar(esperarLogin(), login, senha);
    }

    Scene abrirEEntrar(String login, String senha, br.com.varesync.config.AppContext.Ajustes ajustes)
            throws Exception {
        escreverConfiguracao(BancoTeste.credenciais().url());
        abrirSistema(ajustes);
        return entrar(esperarLogin(), login, senha);
    }

    Scene esperarLogin() throws Exception {
        Scene login = esperarCena(s -> s.getRoot().lookup("#nomeLoja") != null
                && s.getRoot().lookup("#campoLogin") != null, "tela de login");
        Thread.sleep(300);
        return login;
    }

    Scene entrar(Scene login, String usuario, String senha) throws Exception {
        fx(() -> {
            ((TextField) login.getRoot().lookup("#campoLogin")).setText(usuario);
            ((TextField) login.getRoot().lookup("#campoSenha")).setText(senha);
            botao(login.getRoot(), "Entrar").fire();
            return null;
        });
        Scene principal = esperarCena(s -> s.getRoot().lookup("#menu") != null && s.getWidth() > 1000,
                "janela principal");
        Thread.sleep(1200);
        return principal;
    }

    /** Abre uma tela pelo botão do menu e espera o conteúdo trocar. */
    void abrirTela(Scene principal, ItemMenu item) throws Exception {
        Parent r = principal.getRoot();
        Node antes = fx(() -> {
            StackPane c = (StackPane) r.lookup(".conteudo");
            return c.getChildren().isEmpty() ? null : c.getChildren().get(0);
        });
        noFx(() -> botao(r, item.rotulo()).fire());
        esperar(() -> fxr(() -> {
            StackPane c = (StackPane) r.lookup(".conteudo");
            return !c.getChildren().isEmpty() && c.getChildren().get(0) != antes;
        }), "tela " + item.rotulo());
        Thread.sleep(item == ItemMenu.PAINEL ? 1800 : 700);
    }

    /** Abre o PDV pelo menu (janela própria). */
    Scene abrirPdv(Scene principal) throws Exception {
        ButtonBase pdv = fx(() -> botao(principal.getRoot(), ItemMenu.PDV.rotulo()));
        Platform.runLater(pdv::fire);
        Scene cena = esperarCena(s -> s.getRoot().getStyleClass().contains("pdv"), "PDV");
        Thread.sleep(800);
        return cena;
    }

    // ----- Componentes -----

    static ButtonBase botao(Parent raiz, String texto) {
        return raiz.lookupAll(".button").stream().map(n -> (ButtonBase) n).filter(b -> texto.equals(b.getText()))
                .findFirst().orElseThrow(() -> new IllegalStateException("botão não encontrado: " + texto));
    }

    /** Tecla disparada como evento num nó (atalhos das telas ficam no raiz ou num nó de dentro delas). */
    static void tecla(Node no, KeyCode codigo) {
        Platform.runLater(() -> no.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", codigo, false, false, false,
                false)));
    }

    /** Texto num campo e Enter (ActionEvent), como o leitor de código de barras. */
    static void digitar(Parent raiz, String id, String texto) throws Exception {
        fx(() -> {
            TextField campo = (TextField) raiz.lookup(id);
            campo.setText(texto);
            campo.fireEvent(new ActionEvent());
            return null;
        });
        Thread.sleep(300);
    }

    static boolean selecionarAba(Parent raiz, String titulo) throws Exception {
        boolean achou = fx(() -> {
            for (Node n : raiz.lookupAll(".tab-pane")) {
                TabPane tp = (TabPane) n;
                for (Tab tab : tp.getTabs()) {
                    if (titulo.equals(tab.getText())) {
                        tp.getSelectionModel().select(tab);
                        return true;
                    }
                }
            }
            return false;
        });
        Thread.sleep(600);
        return achou;
    }

    // ----- Janelas e diálogos -----

    static Scene esperarCena(Predicate<Scene> condicao, String oQue) throws Exception {
        Scene[] achada = new Scene[1];
        try {
            esperar(() -> fxr(() -> {
                for (Window w : Window.getWindows()) {
                    if (w.isShowing() && w.getScene() != null && condicao.test(w.getScene())) {
                        achada[0] = w.getScene();
                        return true;
                    }
                }
                return false;
            }), oQue);
        } catch (AssertionError tempoEsgotado) {
            throw new AssertionError(tempoEsgotado.getMessage() + "; janelas abertas: " + fx(TelaBase::janelasAbertas),
                    tempoEsgotado);
        }
        return achada[0];
    }

    /** Para a mensagem de falha: cada janela aberta com o tamanho da cena e o que a raiz mostra. */
    static String janelasAbertas() {
        StringBuilder sb = new StringBuilder();
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null) {
                Parent raiz = w.getScene().getRoot();
                sb.append('[').append(w.getClass().getSimpleName()).append(' ')
                        .append(Math.round(w.getScene().getWidth())).append('×')
                        .append(Math.round(w.getScene().getHeight())).append(' ')
                        .append(raiz.getClass().getSimpleName()).append(raiz.getStyleClass());
                if (raiz instanceof DialogPane d) {
                    sb.append(" \"").append(d.getHeaderText()).append(" / ").append(d.getContentText()).append('"');
                }
                sb.append("] ");
            }
        }
        return sb.toString();
    }

    static DialogPane esperarDialogo(String cabecalho) throws Exception {
        Scene cena = esperarCena(s -> s.getRoot() instanceof DialogPane d && d.getHeaderText() != null
                && d.getHeaderText().contains(cabecalho), "diálogo " + cabecalho);
        return (DialogPane) cena.getRoot();
    }

    /** Confirmação ({@code Dialogos.confirmar}, sem cabeçalho): acha pelo texto e responde pelo botão. */
    static void responder(String trecho, String resposta) throws Exception {
        Scene cena = esperarCena(s -> s.getRoot() instanceof DialogPane d && d.getContentText() != null
                && d.getContentText().contains(trecho), "confirmação \"" + trecho + "\"");
        DialogPane d = (DialogPane) cena.getRoot();
        Window w = cena.getWindow();
        Platform.runLater(() -> {
            for (ButtonType b : d.getButtonTypes()) {
                if (b.getText().equals(resposta)) {
                    ((Button) d.lookupButton(b)).fire();
                }
            }
        });
        esperar(() -> !w.isShowing(), "responder \"" + trecho + "\"");
        Thread.sleep(300);
    }

    /** Fecha o diálogo pelo primeiro botão (o de confirmar do {@code DialogoFormulario}) e espera fechar. */
    static void confirmarDialogo(DialogPane d) throws Exception {
        Window w = d.getScene().getWindow();
        Platform.runLater(() -> ((Button) d.lookupButton(d.getButtonTypes().get(0))).fire());
        esperar(() -> !w.isShowing(), "confirmar " + d.getHeaderText());
        Thread.sleep(300);
    }

    static void esconder(Window w) {
        w.setOpacity(0);
        w.setX(-4000);
        w.setY(-4000);
        w.xProperty().addListener((o, a, n) -> {
            if (n.doubleValue() > -3000) {
                w.setX(-4000);
            }
        });
        w.yProperty().addListener((o, a, n) -> {
            if (n.doubleValue() > -3000) {
                w.setY(-4000);
            }
        });
    }

    // ----- Espera e thread do JavaFX -----

    static <T> T fx(Callable<T> acao) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return acao.call();
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(acao.call());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(ESPERA_MS, TimeUnit.MILLISECONDS);
    }

    /** Ação sem resultado na thread do JavaFX, esperando terminar. */
    static void noFx(Runnable acao) throws Exception {
        fx(() -> {
            acao.run();
            return null;
        });
    }

    static <T> T fxr(Callable<T> acao) {
        try {
            return fx(acao);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void esperar(Supplier<Boolean> condicao, String oQue) throws Exception {
        long limite = System.currentTimeMillis() + ESPERA_MS;
        while (System.currentTimeMillis() < limite) {
            try {
                if (Boolean.TRUE.equals(condicao.get())) {
                    return;
                }
            } catch (RuntimeException aindaCarregando) {
                // a tela ainda não está pronta
            }
            Thread.sleep(150);
        }
        throw new AssertionError("tempo esgotado esperando " + oQue);
    }

    // ----- Capturas e banco -----

    /** Captura por snapshot em {@code target/telas/<classe do teste>/<nn>_<nome>.png}. */
    Path capturar(Scene cena, String nome) throws Exception {
        WritableImage imagem = fx(() -> cena.snapshot(null));
        int w = (int) imagem.getWidth();
        int h = (int) imagem.getHeight();
        BufferedImage saida = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader leitor = imagem.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                saida.setRGB(x, y, leitor.getArgb(x, y));
            }
        }
        Path pasta = Path.of(System.getProperty("telas.saida", "target/telas"), getClass().getSimpleName());
        Files.createDirectories(pasta);
        Path arquivo = pasta.resolve(String.format("%02d_%s.png", ++capturas, nome));
        ImageIO.write(saida, "png", arquivo.toFile());
        assertThat(w).as("captura %s com tamanho", nome).isGreaterThan(100);
        return arquivo;
    }

    /** Grava uma imagem pronta (a página do DANFE desenhada pelo Jasper) junto das capturas. */
    Path gravarImagem(BufferedImage imagem, String nome) throws Exception {
        Path pasta = Path.of(System.getProperty("telas.saida", "target/telas"), getClass().getSimpleName());
        Files.createDirectories(pasta);
        Path arquivo = pasta.resolve(String.format("%02d_%s.png", ++capturas, nome));
        ImageIO.write(imagem, "png", arquivo.toFile());
        return arquivo;
    }

    static String valor(String sql) throws SQLException {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static long contar(String sql) throws SQLException {
        try (Connection con = dataSource.getConnection()) {
            return BancoTeste.contar(con, sql);
        }
    }
}
