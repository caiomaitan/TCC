package br.com.varesync.ui;

import java.net.URL;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

/** Diálogos padronizados do sistema (título, sem cabeçalho técnico). */
public final class Dialogos {

    public static final String TITULO = "VareSync";
    public static final String CSS = "/css/app.css";

    private Dialogos() {
    }

    public static void alerta(Alert.AlertType tipo, String mensagem) {
        Alert alerta = new Alert(tipo, mensagem);
        alerta.setTitle(TITULO);
        alerta.setHeaderText(null);
        estilizar(alerta.getDialogPane());
        alerta.showAndWait();
    }

    /** Pergunta com "Sim" e "Não"; Esc ou fechar a janela vale "Não". */
    public static boolean confirmar(String mensagem) {
        return confirmar(null, mensagem);
    }

    /** Pergunta presa à janela {@code dono} (fica por cima dela, inclusive da tela "Sessão bloqueada"). */
    public static boolean confirmar(Window dono, String mensagem) {
        ButtonType sim = new ButtonType("Sim", ButtonBar.ButtonData.YES);
        ButtonType nao = new ButtonType("Não", ButtonBar.ButtonData.NO);
        Alert alerta = new Alert(Alert.AlertType.CONFIRMATION, mensagem, sim, nao);
        if (dono != null) {
            alerta.initOwner(dono);
        }
        alerta.setTitle(TITULO);
        alerta.setHeaderText(null);
        estilizar(alerta.getDialogPane());
        return alerta.showAndWait().filter(sim::equals).isPresent();
    }

    /** Aplica a folha de estilo do sistema a um diálogo. */
    public static void estilizar(DialogPane painel) {
        URL css = Dialogos.class.getResource(CSS);
        if (css != null) {
            painel.getStylesheets().add(css.toExternalForm());
        }
    }
}
