package br.com.varesync.ui;

import br.com.varesync.model.OrigemMercadoria;
import br.com.varesync.model.SugestaoFiscal;
import br.com.varesync.service.ValidacaoFiscal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Confirmação dos dados fiscais da NF-e (F19.3): depois de gerar a entrada, os produtos da nota com NCM, CEST ou
 * origem vazios, com o que a nota traz para cada um. Cada produto marcado (todos, de início) recebe os campos vazios.
 */
public final class DialogoFiscalNota {

    public static final String CABECALHO = "Dados fiscais da NF-e";

    private DialogoFiscalNota() {
    }

    /** @return as sugestões marcadas; vazio se o usuário escolheu "Agora não" */
    public static Optional<List<SugestaoFiscal>> confirmar(Window dono, List<SugestaoFiscal> sugestoes) {
        Dialog<List<SugestaoFiscal>> dialogo = new Dialog<>();
        if (dono != null) {
            dialogo.initOwner(dono);
        }
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText(CABECALHO);
        ButtonType preencher = new ButtonType("Preencher os marcados", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(preencher,
                new ButtonType("Agora não", ButtonBar.ButtonData.CANCEL_CLOSE));

        Label explicacao = new Label("A nota traz NCM, CEST ou origem para " + (sugestoes.size() == 1 ? "1 produto"
                : sugestoes.size() + " produtos") + " com esses campos vazios no cadastro. Só os campos vazios são "
                + "preenchidos; o que já está no cadastro não muda. Confira: a origem é a do fornecedor (quem compra "
                + "de um importador costuma usar a origem 2).");
        explicacao.setWrapText(true);
        explicacao.setMaxWidth(720);
        explicacao.setMinHeight(Region.USE_PREF_SIZE);

        Map<CheckBox, SugestaoFiscal> marcas = new LinkedHashMap<>();
        VBox lista = new VBox(6);
        for (SugestaoFiscal s : sugestoes) {
            CheckBox marca = new CheckBox(texto(s));
            marca.setSelected(true);
            marca.setWrapText(true);
            marca.setMaxWidth(700);
            marcas.put(marca, s);
            lista.getChildren().add(marca);
        }
        ScrollPane rolagem = new ScrollPane(lista);
        rolagem.setFitToWidth(true);
        rolagem.setPrefViewportHeight(Math.min(320, 30.0 * sugestoes.size() + 10));
        rolagem.setPrefViewportWidth(720);

        dialogo.getDialogPane().setContent(new VBox(10, explicacao, rolagem));
        Dialogos.estilizar(dialogo.getDialogPane());
        dialogo.setResultConverter(tipo -> {
            if (tipo != preencher) {
                return null;
            }
            List<SugestaoFiscal> marcadas = new ArrayList<>();
            marcas.forEach((marca, s) -> {
                if (marca.isSelected()) {
                    marcadas.add(s);
                }
            });
            return marcadas;
        });
        return dialogo.showAndWait();
    }

    /** "Café 500 g (código 3), item 1: NCM 0901.21.00 · CEST 17.099.00 · origem 0 — Nacional". */
    static String texto(SugestaoFiscal s) {
        List<String> campos = new ArrayList<>();
        if (s.ncm() != null) {
            campos.add("NCM " + ValidacaoFiscal.formatarNcm(s.ncm()));
        }
        if (s.cest() != null) {
            campos.add("CEST " + ValidacaoFiscal.formatarCest(s.cest()));
        }
        if (s.origem() != null) {
            campos.add("origem " + OrigemMercadoria.rotulo(s.origem()));
        }
        return s.produtoNome() + " (código " + s.produtoId() + "), " + (s.itens().contains(",") ? "itens " : "item ")
                + s.itens() + ": " + String.join(" · ", campos);
    }
}
