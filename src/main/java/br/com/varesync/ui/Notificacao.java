package br.com.varesync.ui;

import java.util.Map;
import java.util.WeakHashMap;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Notificação discreta de sucesso (F21.5, 8.1): um aviso no canto de baixo da janela, que some sozinho e não tira o
 * foco (é um {@link Popup}, que não recebe o teclado), no lugar das janelas de "OK". Um aviso novo na mesma janela
 * substitui o anterior.
 */
public final class Notificacao {

    public static final String CLASSE = "notificacao";
    /** Tempo mínimo à vista; textos longos ficam mais (até {@link #MAXIMO}). */
    static final Duration MINIMO = Duration.seconds(4);
    static final Duration MAXIMO = Duration.seconds(9);
    private static final double MARGEM = 24;
    private static final double LARGURA_MAXIMA = 520;
    // Estado das notificações abertas, que muda durante o uso; não é uma constante.
    @SuppressWarnings("checkstyle:ConstantName")
    private static final Map<Window, Popup> abertas = new WeakHashMap<>();

    private Notificacao() {
    }

    /** Tempo à vista: 4 s mais 50 ms por caractere acima de 60, até 9 s. */
    static Duration tempo(String texto) {
        double extra = Math.max(0, texto.length() - 60) * 50;
        return Duration.millis(Math.min(MINIMO.toMillis() + extra, MAXIMO.toMillis()));
    }

    /**
     * Mostra o aviso na janela do nó.
     *
     * @return false se o nó ainda não está numa janela à vista (quem chamou mostra o texto de outro jeito)
     */
    public static boolean mostrar(Node referencia, String texto) {
        Scene cena = referencia == null ? null : referencia.getScene();
        return cena != null && mostrar(cena.getWindow(), texto);
    }

    /** @return false se a janela não está à vista */
    public static boolean mostrar(Window janela, String texto) {
        if (janela == null || !janela.isShowing() || texto == null || texto.isBlank()) {
            return false;
        }
        Popup anterior = abertas.remove(janela);
        if (anterior != null) {
            anterior.hide();
        }
        Label rotulo = new Label(texto);
        rotulo.setWrapText(true);
        rotulo.setMaxWidth(LARGURA_MAXIMA * Aparencia.escala());
        rotulo.setMinHeight(Region.USE_PREF_SIZE);
        rotulo.getStyleClass().add("notificacao-texto");
        StackPane caixa = new StackPane(rotulo);
        caixa.setPadding(new Insets(10, 14, 10, 14));
        caixa.setMouseTransparent(true);
        Aparencia.estilizarAvulso(caixa, CLASSE);

        Popup popup = new Popup();
        popup.setAutoFix(true);
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);
        popup.getContent().add(caixa);
        popup.getProperties().put(Notificacao.class, texto);
        abertas.put(janela, popup);
        popup.setOnShown(e -> {
            // No canto de baixo, à direita, acima da barra de status.
            popup.setX(janela.getX() + janela.getWidth() - popup.getWidth() - MARGEM);
            popup.setY(janela.getY() + janela.getHeight() - popup.getHeight() - MARGEM * 2.5);
        });
        popup.show(janela);

        PauseTransition espera = new PauseTransition(tempo(texto));
        FadeTransition some = new FadeTransition(Duration.millis(400), caixa);
        some.setFromValue(1);
        some.setToValue(0);
        SequentialTransition animacao = new SequentialTransition(espera, some);
        animacao.setOnFinished(e -> {
            popup.hide();
            abertas.remove(janela, popup);
        });
        animacao.play();
        return true;
    }

    /** Texto da notificação à vista na janela (para os roteiros de tela), ou nulo. */
    public static String atual(Window janela) {
        Popup popup = abertas.get(janela);
        return popup == null || !popup.isShowing() ? null : (String) popup.getProperties().get(Notificacao.class);
    }
}
