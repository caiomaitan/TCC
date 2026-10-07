package br.com.varesync.ui;

import javafx.scene.control.Alert;

/** Diálogos padronizados do sistema (título, sem cabeçalho técnico). */
public final class Dialogos {

    public static final String TITULO = "VareSync";

    private Dialogos() {
    }

    public static void alerta(Alert.AlertType tipo, String mensagem) {
        Alert alerta = new Alert(tipo, mensagem);
        alerta.setTitle(TITULO);
        alerta.setHeaderText(null);
        alerta.showAndWait();
    }
}
