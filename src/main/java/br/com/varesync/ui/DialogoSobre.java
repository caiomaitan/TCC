package br.com.varesync.ui;

import br.com.varesync.config.VersaoSistema;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/** Tela "Sobre" (F11.6): versão, commit e data do build, além das versões do Java e do JavaFX. Enter ou Esc fecham. */
public final class DialogoSobre {

    private DialogoSobre() {
    }

    public static void mostrar(Window dono, VersaoSistema versao) {
        Dialog<Void> dialogo = criar(versao);
        if (dono != null) {
            dialogo.initOwner(dono);
        }
        dialogo.showAndWait();
    }

    /** O diálogo montado, sem abrir (separado para o teste). */
    static Dialog<Void> criar(VersaoSistema versao) {
        Dialog<Void> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO + " — Sobre");
        dialogo.setHeaderText("VareSync — ERP desktop para o pequeno e médio varejo");

        GridPane grade = new GridPane();
        grade.setHgap(12);
        grade.setVgap(6);
        grade.setPadding(new Insets(4, 0, 0, 0));
        linha(grade, 0, "Versão", versao.versao(), "sobre-versao");
        linha(grade, 1, "Commit", versao.commitTexto(), "sobre-commit");
        linha(grade, 2, "Data do build", versao.dataBuildTexto(), "sobre-data");
        linha(grade, 3, "Java", System.getProperty("java.version", VersaoSistema.DESCONHECIDO), "sobre-java");
        linha(grade, 4, "JavaFX", System.getProperty("javafx.runtime.version", VersaoSistema.DESCONHECIDO),
                "sobre-javafx");

        dialogo.getDialogPane().setContent(grade);
        ButtonType fechar = new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogo.getDialogPane().getButtonTypes().setAll(fechar);
        ((Button) dialogo.getDialogPane().lookupButton(fechar)).setDefaultButton(true); // Enter também fecha
        Dialogos.estilizar(dialogo.getDialogPane());
        return dialogo;
    }

    private static void linha(GridPane grade, int linha, String rotulo, String valor, String id) {
        Label nome = new Label(rotulo + ":");
        Label texto = new Label(valor);
        texto.setId(id);
        grade.addRow(linha, nome, texto);
    }
}
