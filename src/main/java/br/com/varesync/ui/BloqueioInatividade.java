package br.com.varesync.ui;

import br.com.varesync.service.NegocioException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bloqueio por inatividade (F10.2). Depois de N minutos sem teclado nem mouse em qualquer janela do sistema, abre por
 * cima delas a tela "Sessão bloqueada": uma janela modal do tamanho das janelas abertas (principal, PDV e diálogos),
 * que esconde o conteúdo sem mexer nele. O estado das telas fica como estava, inclusive o carrinho do PDV.
 *
 * <p>Desbloquear pede a senha de quem está logado ({@link Desbloqueio}); "Trocar usuário" chama quem criou o bloqueio,
 * que faz o log off (e pergunta antes se há venda em andamento).
 */
public final class BloqueioInatividade {

    private static final Logger log = LoggerFactory.getLogger(BloqueioInatividade.class);

    /** Confere a senha do usuário logado; recusa com {@link NegocioException} (senha errada, bloqueado). */
    @FunctionalInterface
    public interface Desbloqueio {
        void desbloquear(String senha);
    }

    private final Duration limite;
    private final String usuario;
    private final Desbloqueio desbloqueio;
    private final Consumer<Window> trocarUsuario;
    private final LongSupplier relogioNanos;
    private final EventHandler<InputEvent> atividade = e -> registrarAtividade();
    private final ListChangeListener<Window> janelas = this::janelasMudaram;
    private final List<Window> vigiadas = new ArrayList<>();
    private final Timeline verificacao;
    private long ultimaAtividade;
    private Stage tela;
    private PasswordField campoSenha;
    private Label mensagem;

    BloqueioInatividade(Duration limite, String usuario, Desbloqueio desbloqueio, Consumer<Window> trocarUsuario,
                        LongSupplier relogioNanos) {
        this.limite = limite;
        this.usuario = usuario;
        this.desbloqueio = desbloqueio;
        this.trocarUsuario = trocarUsuario;
        this.relogioNanos = relogioNanos;
        this.verificacao = new Timeline(
                new KeyFrame(javafx.util.Duration.seconds(1), e -> verificar()));
        this.verificacao.setCycleCount(Animation.INDEFINITE);
    }

    /**
     * Começa a vigiar todas as janelas do sistema (as abertas e as que abrirem depois).
     *
     * @param minutos       0 = não bloqueia (devolve um bloqueio que não faz nada)
     * @param usuario       "Nome (login)", mostrado na tela bloqueada
     * @param trocarUsuario recebe a janela da tela bloqueada, para as perguntas ficarem por cima dela
     */
    public static BloqueioInatividade iniciar(int minutos, String usuario, Desbloqueio desbloqueio,
                                              Consumer<Window> trocarUsuario) {
        BloqueioInatividade bloqueio = new BloqueioInatividade(Duration.ofMinutes(minutos), usuario, desbloqueio,
                trocarUsuario, System::nanoTime);
        if (minutos > 0) {
            bloqueio.ligar();
        }
        return bloqueio;
    }

    void ligar() {
        ultimaAtividade = relogioNanos.getAsLong();
        Window.getWindows().forEach(this::vigiar);
        Window.getWindows().addListener(janelas);
        verificacao.play();
        log.info("Bloqueio por inatividade ligado: {} min", limite.toMinutes());
    }

    /** Para de vigiar e fecha a tela bloqueada, se aberta (log off, saída). */
    public void parar() {
        verificacao.stop();
        Window.getWindows().removeListener(janelas);
        vigiadas.forEach(w -> w.removeEventFilter(InputEvent.ANY, atividade));
        vigiadas.clear();
        if (tela != null) {
            tela.close();
            tela = null;
        }
    }

    public boolean bloqueada() {
        return tela != null;
    }

    /** Janela da tela bloqueada (nula se não está bloqueada). */
    public Stage janela() {
        return tela;
    }

    private void janelasMudaram(ListChangeListener.Change<? extends Window> mudanca) {
        while (mudanca.next()) {
            mudanca.getAddedSubList().forEach(this::vigiar);
            mudanca.getRemoved().forEach(w -> {
                w.removeEventFilter(InputEvent.ANY, atividade);
                vigiadas.remove(w);
            });
        }
    }

    private void vigiar(Window janela) {
        if (janela == tela || vigiadas.contains(janela)) {
            return;
        }
        janela.addEventFilter(InputEvent.ANY, atividade);
        vigiadas.add(janela);
    }

    private void registrarAtividade() {
        if (tela == null) {
            ultimaAtividade = relogioNanos.getAsLong();
        }
    }

    void verificar() {
        if (tela == null && relogioNanos.getAsLong() - ultimaAtividade >= limite.toNanos()) {
            bloquear();
        }
    }

    /** Abre a tela "Sessão bloqueada" por cima de todas as janelas do sistema. */
    public void bloquear() {
        if (tela != null) {
            return;
        }
        List<Stage> abertas = Window.getWindows().stream()
                .filter(w -> w instanceof Stage && w.isShowing()).map(w -> (Stage) w).toList();
        if (abertas.isEmpty()) {
            return;
        }
        Window.getWindows().stream().filter(w -> w instanceof PopupWindow).toList().forEach(Window::hide);
        Stage dono = abertas.stream().filter(Window::isFocused).findFirst().orElse(abertas.get(abertas.size() - 1));
        log.info("Tela bloqueada por inatividade ({} min)", limite.toMinutes());

        tela = new Stage(StageStyle.UNDECORATED);
        tela.initOwner(dono);
        tela.initModality(Modality.APPLICATION_MODAL);
        tela.setTitle(Dialogos.TITULO + " — Sessão bloqueada");
        tela.setOnCloseRequest(Event::consume); // só sai pela senha ou por "Trocar usuário"
        Rectangle2D area = area(abertas);
        tela.setX(area.getMinX());
        tela.setY(area.getMinY());
        tela.setWidth(area.getWidth());
        tela.setHeight(area.getHeight());

        Scene cena = new Scene(montar(), area.getWidth(), area.getHeight());
        cena.getStylesheets().add(BloqueioInatividade.class.getResource(Dialogos.CSS).toExternalForm());
        tela.setScene(cena);
        tela.show();
        tela.toFront();
        Platform.runLater(campoSenha::requestFocus);
    }

    private StackPane montar() {
        Label titulo = new Label("Sessão bloqueada");
        titulo.getStyleClass().add("titulo");
        Label quem = new Label("Usuário: " + usuario);
        Label instrucao = new Label("A tela foi bloqueada depois de " + limite.toMinutes()
                + (limite.toMinutes() == 1 ? " minuto" : " minutos") + " sem uso. O que estava aberto continua como "
                + "estava. Para continuar, digite a sua senha.");
        instrucao.setWrapText(true);
        instrucao.setMinHeight(Region.USE_PREF_SIZE);
        instrucao.getStyleClass().add("subtitulo");
        campoSenha = new PasswordField();
        campoSenha.setPromptText("Senha");
        mensagem = new Label();
        mensagem.setWrapText(true);
        mensagem.setMinHeight(40);

        Button trocar = new Button("Trocar usuário");
        trocar.setOnAction(e -> trocarUsuario.accept(tela));
        Button desbloquear = new Button("Desbloquear");
        desbloquear.setDefaultButton(true);
        desbloquear.getStyleClass().add("botao-primario");
        desbloquear.setOnAction(e -> desbloquear());
        Region espaco = new Region();
        HBox.setHgrow(espaco, Priority.ALWAYS);
        HBox botoes = new HBox(10, trocar, espaco, desbloquear);
        botoes.setAlignment(Pos.CENTER_RIGHT);

        VBox cartao = new VBox(12, titulo, quem, instrucao, campoSenha, mensagem, botoes);
        cartao.getStyleClass().add("cartao-bloqueio");
        cartao.setMaxWidth(440);
        cartao.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane fundo = new StackPane(cartao);
        fundo.getStyleClass().add("tela-bloqueada");
        return fundo;
    }

    /** Confere a senha: certa, volta ao ponto exato; errada, mostra o motivo (o 5º erro bloqueia o usuário). */
    void desbloquear() {
        String senha = campoSenha.getText();
        try {
            desbloqueio.desbloquear(senha);
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            campoSenha.clear();
            campoSenha.requestFocus();
            return;
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
            campoSenha.clear();
            return;
        }
        log.info("Tela desbloqueada");
        Stage fechar = tela;
        tela = null;
        ultimaAtividade = relogioNanos.getAsLong();
        if (fechar != null) {
            fechar.close();
        }
    }

    /** Retângulo que cobre todas as janelas abertas do sistema. */
    private static Rectangle2D area(List<Stage> abertas) {
        double x1 = Double.MAX_VALUE;
        double y1 = Double.MAX_VALUE;
        double x2 = -Double.MAX_VALUE;
        double y2 = -Double.MAX_VALUE;
        for (Stage s : abertas) {
            x1 = Math.min(x1, s.getX());
            y1 = Math.min(y1, s.getY());
            x2 = Math.max(x2, s.getX() + s.getWidth());
            y2 = Math.max(y2, s.getY() + s.getHeight());
        }
        return new Rectangle2D(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
    }
}
