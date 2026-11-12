package br.com.varesync.ui;

import javafx.scene.control.Label;

/**
 * Mensagem abaixo dos campos de um formulário (8.1): erro em vermelho, junto do campo. O sucesso vai para a
 * {@link Notificacao} discreta da janela (F21.5), que some sozinha; o rótulo fica vazio. Se a tela ainda não está numa
 * janela à vista, o sucesso fica no rótulo, em verde, como antes.
 */
public final class Mensagem {

    private static final String ERRO = "mensagem-erro";
    private static final String SUCESSO = "mensagem-sucesso";

    private Mensagem() {
    }

    public static void erro(Label rotulo, String texto) {
        mostrar(rotulo, texto, ERRO);
    }

    public static void sucesso(Label rotulo, String texto) {
        if (Notificacao.mostrar(rotulo, texto)) {
            limpar(rotulo);
        } else {
            mostrar(rotulo, texto, SUCESSO);
        }
    }

    /**
     * Sucesso que fica no rótulo, em verde (o PDV: o troco e o número da venda ficam à vista no painel até o próximo
     * item, que é a área de avisos da tela).
     */
    public static void sucessoNoRotulo(Label rotulo, String texto) {
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
