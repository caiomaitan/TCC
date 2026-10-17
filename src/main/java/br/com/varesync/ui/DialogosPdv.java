package br.com.varesync.ui;

import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
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
 * Diálogos do PDV (6.6): quantidade (F3 e produto fracionado), desconto (F4/F5), motivo do cancelamento (F9),
 * consulta de preço (F7), busca de produto (F2) e o troco em destaque depois da venda. Enter confirma, Esc cancela.
 * As ações recebidas gravam; {@code NegocioException} mantém o diálogo aberto com a mensagem.
 */
public final class DialogosPdv {

    static final List<String> MOTIVOS_CANCELAMENTO = List.of("Cliente desistiu", "Erro de digitação",
            "Troca de produto", "Pagamento não aprovado");

    /** Aplica o desconto com o tipo escolhido e o valor digitado. */
    @FunctionalInterface
    public interface Desconto<T> {
        T aplicar(TipoDesconto tipo, String valor);
    }

    private DialogosPdv() {
    }

    /** @return o resultado da ação com a quantidade digitada, ou vazio se cancelado */
    public static <T> Optional<T> quantidade(String titulo, String instrucao, Function<String, T> acao) {
        DialogoFormulario formulario = new DialogoFormulario(titulo, instrucao, "Confirmar");
        TextField quantidade = campoNumero("0,000");
        formulario.adicionarCampo("Quantidade *", quantidade);
        return formulario.mostrar(() -> acao.apply(quantidade.getText()), null);
    }

    /** Desconto em R$ ou % (o tipo começa em %). @return o resultado da ação, ou vazio se cancelado */
    public static <T> Optional<T> desconto(String titulo, String instrucao, Desconto<T> acao) {
        DialogoFormulario formulario = new DialogoFormulario(titulo, instrucao, "Aplicar");
        TextField valor = campoNumero("0");
        ComboBox<TipoDesconto> tipo = new ComboBox<>();
        tipo.getItems().setAll(TipoDesconto.values());
        tipo.setValue(TipoDesconto.PERCENTUAL);
        formulario.adicionarCampo("Desconto *", valor);
        formulario.adicionarCampo("Em", tipo);
        return formulario.mostrar(() -> acao.aplicar(tipo.getValue(), valor.getText()), null);
    }

    /** Motivo com sugestões editáveis. @return o resultado da ação, ou vazio se cancelado */
    public static <T> Optional<T> motivoCancelamento(String titulo, String instrucao, Function<String, T> acao) {
        DialogoFormulario formulario = new DialogoFormulario(titulo, instrucao, "Cancelar venda");
        ComboBox<String> motivo = new ComboBox<>();
        motivo.setEditable(true);
        motivo.setMaxWidth(Double.MAX_VALUE);
        motivo.setPromptText("Escolha ou digite");
        motivo.getItems().setAll(MOTIVOS_CANCELAMENTO);
        formulario.adicionarCampo("Motivo *", motivo);
        return formulario.mostrar(() -> acao.apply(motivo.getEditor().getText()), null);
    }

    /**
     * Consulta de preço (F7): cada Enter mostra o resultado da consulta sem fechar; Esc fecha.
     *
     * @param consulta código → texto do resultado; {@code NegocioException} aparece em vermelho
     */
    public static void consultarPreco(Function<String, String> consulta) {
        Dialog<Void> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText("Consultar preço (F7)");
        dialogo.getDialogPane().getButtonTypes().add(new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE));
        TextField codigo = new TextField();
        codigo.setPromptText("Leia o código de barras ou digite o código");
        Label resultado = new Label("O produto não é incluído na venda.");
        resultado.setWrapText(true);
        resultado.setMaxWidth(420);
        resultado.getStyleClass().add("consulta-preco");
        codigo.setOnAction(e -> {
            e.consume();
            try {
                resultado.setText(consulta.apply(codigo.getText()));
                resultado.getStyleClass().remove("mensagem-erro");
            } catch (NegocioException erro) {
                resultado.setText(erro.getMessage());
                if (!resultado.getStyleClass().contains("mensagem-erro")) {
                    resultado.getStyleClass().add("mensagem-erro");
                }
            }
            codigo.selectAll();
        });
        dialogo.getDialogPane().setContent(new VBox(12, new Label("Código"), codigo, resultado));
        dialogo.getDialogPane().setPrefWidth(480);
        Dialogos.estilizar(dialogo.getDialogPane());
        Platform.runLater(codigo::requestFocus);
        dialogo.showAndWait();
    }

    /**
     * Busca de produto (F2): texto → nome (sem acento), dígitos → código; Enter pesquisa, ↓ vai para a lista,
     * Enter ou duplo clique escolhe.
     *
     * @return o produto escolhido, ou vazio se cancelado
     */
    public static Optional<ProdutoResumo> buscarProduto(String textoInicial,
                                                        Function<String, List<ProdutoResumo>> pesquisa) {
        Dialog<ProdutoResumo> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText("Buscar produto (F2)");
        ButtonType incluir = new ButtonType("Incluir na venda", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(incluir,
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
        tabela.getColumns().add(coluna("Estoque", 100,
                p -> Formatos.quantidade(p.estoque(), p.unidadeFracionada()), true));
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
            e.consume(); // Enter no campo pesquisa; não aciona "Incluir"
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
        Button botaoIncluir = (Button) dialogo.getDialogPane().lookupButton(incluir);
        botaoIncluir.disableProperty().bind(tabela.getSelectionModel().selectedItemProperty().isNull());
        dialogo.setResultConverter(tipo -> tipo == incluir ? tabela.getSelectionModel().getSelectedItem() : null);

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

    /**
     * Venda finalizada: troco em destaque e a oferta do comprovante.
     *
     * @return true se a pessoa quer ver o comprovante
     */
    public static boolean vendaFinalizada(long vendaId, BigDecimal troco) {
        Dialog<Boolean> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText("Venda nº " + vendaId + " finalizada");
        ButtonType comprovante = new ButtonType("Ver comprovante", ButtonBar.ButtonData.OTHER);
        ButtonType proxima = new ButtonType("Próxima venda", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(comprovante, proxima);
        Label rotulo = new Label(troco.signum() > 0 ? "Troco" : "Sem troco");
        rotulo.getStyleClass().add("titulo-secao");
        Label valor = new Label(Formatos.moeda(troco));
        valor.getStyleClass().add("troco-destaque");
        VBox conteudo = new VBox(6, rotulo, valor);
        conteudo.setMinWidth(360);
        dialogo.getDialogPane().setContent(conteudo);
        dialogo.setResultConverter(tipo -> tipo == comprovante);
        Dialogos.estilizar(dialogo.getDialogPane());
        return dialogo.showAndWait().orElse(false);
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

    private static TextField campoNumero(String exemplo) {
        TextField campo = new TextField();
        campo.getStyleClass().add("campo-numero");
        campo.setPromptText(exemplo);
        return campo;
    }
}
