package br.com.varesync.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.function.Consumer;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Callback;

/** Janelas modais carregadas de FXML (cadastro de produto, categorias). Esc fecha a janela. */
public final class Janelas {

    private Janelas() {
    }

    /**
     * Carrega o FXML, prepara o controller e mostra a janela até ela fechar.
     *
     * @param preparar chamado com o controller antes de a janela aparecer (ex.: carregar o registro)
     * @return o controller, depois que a janela fechou
     */
    public static <C> C abrirModal(Window dona, String fxml, String titulo, Callback<Class<?>, Object> fabrica,
                                   Consumer<C> preparar) {
        FXMLLoader loader = new FXMLLoader(Janelas.class.getResource(fxml));
        loader.setControllerFactory(fabrica);
        Parent raiz;
        try {
            raiz = loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao carregar " + fxml, e);
        }
        C controller = loader.getController();
        preparar.accept(controller);

        Stage janela = new Stage();
        janela.initOwner(dona);
        janela.initModality(Modality.WINDOW_MODAL);
        janela.setTitle(Dialogos.TITULO + " — " + titulo);
        Scene cena = new Scene(raiz);
        URL css = Janelas.class.getResource(Dialogos.CSS);
        if (css != null) {
            cena.getStylesheets().add(css.toExternalForm());
        }
        cena.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                janela.close();
            }
        });
        janela.setScene(cena);
        janela.showAndWait();
        return controller;
    }
}
