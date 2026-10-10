package br.com.varesync.ui;

import javafx.scene.control.Label;

/** Mensagem abaixo dos campos de um formulário (8.1): erro em vermelho, sucesso em verde. */
public final class Mensagem {

    private static final String ERRO = "mensagem-erro";
    private static final String SUCESSO = "mensagem-sucesso";

    private Mensagem() {
    }

    public static void erro(Label rotulo, String texto) {
        mostrar(rotulo, texto, ERRO);
    }

    public static void sucesso(Label rotulo, String texto) {
        mostrar(rotulo, texto, SUCESSO);
    }

    public static void informar(Label rotulo, String texto) {
        mostrar(rotulo, texto, null);
    }

    public static void limpar(Label rotulo) {
        mostrar(rotulo, "", null);
    }

    private static void mostrar(Label rotulo, String texto, String estilo) {
        rotulo.setText(texto);
        rotulo.getStyleClass().removeAll(ERRO, SUCESSO);
        if (estilo != null) {
            rotulo.getStyleClass().add(estilo);
        }
    }
}
