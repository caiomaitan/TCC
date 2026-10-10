package br.com.varesync.ui;

import br.com.varesync.service.NegocioException;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Diálogo modal com campos rotulados e validação ao confirmar: se a ação lançar {@link NegocioException},
 * a mensagem aparece abaixo dos campos e o diálogo continua aberto. Enter confirma, Esc cancela.
 */
final class DialogoFormulario {

    private final Dialog<Object> dialogo = new Dialog<>();
    private final GridPane campos = new GridPane();
    private final Label mensagem = new Label();
    private final ButtonType confirmar;
    private Control primeiroCampo;
    private int linhas;

    DialogoFormulario(String titulo, String instrucao, String textoConfirmar) {
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText(titulo);
        confirmar = new ButtonType(textoConfirmar, ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(confirmar,
                new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));

        campos.setHgap(12);
        campos.setVgap(10);
        ColumnConstraints rotulos = new ColumnConstraints();
        rotulos.setMinWidth(130);
        ColumnConstraints entradas = new ColumnConstraints();
        entradas.setHgrow(Priority.ALWAYS);
        entradas.setMinWidth(220);
        campos.getColumnConstraints().addAll(rotulos, entradas);

        mensagem.setWrapText(true);
        mensagem.setMaxWidth(380);
        mensagem.getStyleClass().add("mensagem-erro");

        VBox conteudo = new VBox(12);
        if (instrucao != null) {
            Label texto = new Label(instrucao);
            texto.setWrapText(true);
            texto.setMaxWidth(380);
            conteudo.getChildren().add(texto);
        }
        conteudo.getChildren().addAll(campos, mensagem);
        dialogo.getDialogPane().setContent(conteudo);
        Dialogos.estilizar(dialogo.getDialogPane());
    }

    void adicionarCampo(String rotulo, Control campo) {
        campos.add(new Label(rotulo), 0, linhas);
        campos.add(campo, 1, linhas);
        linhas++;
        if (primeiroCampo == null) {
            primeiroCampo = campo;
        }
    }

    /**
     * Mostra o diálogo e espera.
     *
     * @param acao executada ao confirmar; {@link NegocioException} mantém o diálogo aberto com a mensagem
     * @param aoRecusar chamado depois de cada recusa (ex.: limpar a senha); pode ser nulo
     * @return o resultado da ação, ou vazio se cancelado
     */
    <T> Optional<T> mostrar(Supplier<T> acao, Runnable aoRecusar) {
        Object[] resultado = new Object[1];
        Node botao = dialogo.getDialogPane().lookupButton(confirmar);
        ((Button) botao).addEventFilter(ActionEvent.ACTION, evento -> {
            try {
                resultado[0] = acao.get();
            } catch (NegocioException e) {
                mensagem.setText(e.getMessage());
                if (aoRecusar != null) {
                    aoRecusar.run();
                }
                evento.consume();
            }
        });
        dialogo.setResultConverter(tipo -> tipo == confirmar ? Boolean.TRUE : null);
        if (primeiroCampo != null) {
            Platform.runLater(primeiroCampo::requestFocus);
        }
        boolean confirmado = dialogo.showAndWait().isPresent();
        @SuppressWarnings("unchecked")
        T valor = (T) resultado[0];
        return confirmado ? Optional.ofNullable(valor) : Optional.empty();
    }
}
