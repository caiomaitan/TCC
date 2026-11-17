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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
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
        resultado.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE); // F20.3: várias linhas (faixas, embalagens)
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
            if (dialogo.getDialogPane().getScene() != null) {
                dialogo.getDialogPane().getScene().getWindow().sizeToScene(); // o diálogo cresce com o texto
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
     * Busca de produto (F2), com a coluna de estoque; ver {@link BuscaProdutoDialogo}.
     *
     * @return o produto escolhido, ou vazio se cancelado
     */
    public static Optional<ProdutoResumo> buscarProduto(String textoInicial,
                                                        Function<String, List<ProdutoResumo>> pesquisa) {
        return BuscaProdutoDialogo.buscar("Buscar produto (F2)", "Incluir na venda", true, textoInicial, pesquisa);
    }

    /**
     * Venda finalizada: troco em destaque e a oferta do comprovante.
     *
     * @return true se a pessoa quer ver o comprovante
     */
    public static boolean vendaFinalizada(long vendaId, BigDecimal troco) {
        return vendaFinalizada(vendaId, troco, null, false, "Ver comprovante");
    }

    /**
     * Com a NFC-e (F23.6): o troco, a situação da nota ({@code aviso}, em vermelho com {@code erro}) e o botão que
     * mostra o DANFE ou, com a nota recusada, o comprovante não fiscal.
     *
     * @return se a pessoa pediu o documento do botão
     */
    public static boolean vendaFinalizada(long vendaId, BigDecimal troco, String aviso, boolean erro,
                                          String textoBotao) {
        Dialog<Boolean> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText("Venda nº " + vendaId + " finalizada");
        ButtonType comprovante = new ButtonType(textoBotao, ButtonBar.ButtonData.OTHER);
        ButtonType proxima = new ButtonType("Próxima venda", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(comprovante, proxima);
        Label rotulo = new Label(troco.signum() > 0 ? "Troco" : "Sem troco");
        rotulo.getStyleClass().add("titulo-secao");
        Label valor = new Label(Formatos.moeda(troco));
        valor.getStyleClass().add("troco-destaque");
        VBox conteudo = new VBox(6, rotulo, valor);
        if (aviso != null) {
            Label situacao = new Label(aviso);
            situacao.setWrapText(true);
            situacao.setMinHeight(Region.USE_PREF_SIZE);
            situacao.setMaxWidth(460);
            situacao.getStyleClass().add(erro ? "mensagem-erro" : "mensagem-sucesso");
            situacao.setId("situacaoNfce");
            conteudo.getChildren().add(situacao);
        }
        conteudo.setMinWidth(360);
        dialogo.getDialogPane().setContent(conteudo);
        dialogo.setResultConverter(tipo -> tipo == comprovante);
        Dialogos.estilizar(dialogo.getDialogPane());
        return dialogo.showAndWait().orElse(false);
    }

    private static TextField campoNumero(String exemplo) {
        TextField campo = new TextField();
        campo.getStyleClass().add("campo-numero");
        campo.setPromptText(exemplo);
        return campo;
    }
}
