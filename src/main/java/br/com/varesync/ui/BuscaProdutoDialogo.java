package br.com.varesync.ui;

import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Formatos;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;

/**
 * Busca de produto (F2 do PDV, inclusão no inventário): texto → nome (sem acento), dígitos → código; Enter pesquisa,
 * ↓ vai para a lista, Enter ou duplo clique escolhe, Esc cancela.
 */
public final class BuscaProdutoDialogo {

    private BuscaProdutoDialogo() {
    }

    /**
     * @param titulo         cabeçalho do diálogo ("Buscar produto (F2)")
     * @param textoConfirmar botão que escolhe ("Incluir na venda")
     * @param mostrarEstoque mostra a coluna Estoque (o inventário não mostra: a contagem é cega)
     * @param textoInicial   termo já digitado; se houver, a busca roda ao abrir
     * @param pesquisa       termo → produtos; {@code NegocioException} aparece em vermelho
     * @return o produto escolhido, ou vazio se cancelado
     */
    public static Optional<ProdutoResumo> buscar(String titulo, String textoConfirmar, boolean mostrarEstoque,
                                                 String textoInicial,
                                                 Function<String, List<ProdutoResumo>> pesquisa) {
        Dialog<ProdutoResumo> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText(titulo);
        ButtonType escolher = new ButtonType(textoConfirmar, ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(escolher,
                new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));

        TextField termo = new TextField(textoInicial == null ? "" : textoInicial);
        termo.setPromptText("Nome, código ou código de barras");
        Label mensagem = new Label();
        mensagem.getStyleClass().add("mensagem-erro");
        TableView<ProdutoResumo> tabela = new TableView<>();
        tabela.setPlaceholder(new Label("Digite o nome e tecle Enter."));
        tabela.setPrefSize(720, 360);
        tabela.getColumns().add(coluna("Código", 80, p -> String.valueOf(p.id()), true));
        tabela.getColumns().add(coluna("Produto", 330, ProdutoResumo::nome, false));
        tabela.getColumns().add(coluna("Unidade", 70, ProdutoResumo::unidade, false));
        tabela.getColumns().add(coluna("Preço", 100, p -> Formatos.moeda(p.precoVenda()), true));
        if (mostrarEstoque) {
            tabela.getColumns().add(coluna("Estoque", 100,
                    p -> Formatos.quantidade(p.estoque(), p.unidadeFracionada()), true));
        } else {
            tabela.getColumns().add(coluna("Categoria", 150,
                    p -> p.categoriaNome() == null ? "" : p.categoriaNome(), false));
        }
        tabela.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        Runnable pesquisar = () -> {
            try {
                List<ProdutoResumo> encontrados = pesquisa.apply(termo.getText());
                tabela.getItems().setAll(encontrados);
                mensagem.setText(encontrados.isEmpty() ? "Nenhum produto encontrado." : "");
                if (!encontrados.isEmpty()) {
                    tabela.getSelectionModel().selectFirst();
                }
            } catch (NegocioException erro) {
                mensagem.setText(erro.getMessage());
            }
        };
        termo.setOnAction(e -> {
            e.consume(); // Enter no campo pesquisa; não aciona o botão de escolher
            pesquisar.run();
            if (!tabela.getItems().isEmpty()) {
                tabela.requestFocus();
            }
        });
        termo.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN && !tabela.getItems().isEmpty()) {
                e.consume();
                tabela.requestFocus();
            }
        });
        tabela.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && tabela.getSelectionModel().getSelectedItem() != null) {
                dialogo.setResult(tabela.getSelectionModel().getSelectedItem());
                dialogo.close();
            }
        });
        Button botaoEscolher = (Button) dialogo.getDialogPane().lookupButton(escolher);
        botaoEscolher.disableProperty().bind(tabela.getSelectionModel().selectedItemProperty().isNull());
        dialogo.setResultConverter(tipo -> tipo == escolher ? tabela.getSelectionModel().getSelectedItem() : null);

        dialogo.getDialogPane().setContent(new VBox(10, termo, tabela, mensagem));
        Dialogos.estilizar(dialogo.getDialogPane());
        if (!termo.getText().isBlank()) {
            pesquisar.run();
        }
        Platform.runLater(() -> {
            if (tabela.getItems().isEmpty()) {
                termo.requestFocus();
                termo.end();
            } else {
                tabela.requestFocus();
            }
        });
        return dialogo.showAndWait();
    }

    private static TableColumn<ProdutoResumo, String> coluna(String titulo, double largura,
                                                            Function<ProdutoResumo, String> valor, boolean numero) {
        TableColumn<ProdutoResumo, String> coluna = new TableColumn<>(titulo);
        coluna.setPrefWidth(largura);
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
        if (numero) {
            coluna.getStyleClass().add("coluna-numero");
        }
        return coluna;
    }
}
