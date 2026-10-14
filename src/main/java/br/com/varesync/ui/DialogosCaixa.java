package br.com.varesync.ui;

import br.com.varesync.model.TipoCaixaMovimento;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

/**
 * Diálogos do caixa (6.5): abertura com fundo de troco, sangria/suprimento e fechamento às cegas. A gravação é feita
 * pela ação recebida; {@code NegocioException} mantém o diálogo aberto com a mensagem. Enter confirma, Esc cancela.
 * Também usados pelo PDV (F8 e F12).
 */
public final class DialogosCaixa {

    static final List<String> SUGESTOES_SANGRIA = List.of("Recolhimento ao cofre", "Depósito bancário",
            "Pagamento de fornecedor");
    static final List<String> SUGESTOES_SUPRIMENTO = List.of("Reforço de troco");

    /** Grava a sangria ou o suprimento com os textos digitados. */
    @FunctionalInterface
    public interface Movimentar<T> {
        T executar(TipoCaixaMovimento tipo, String valor, String motivo);
    }

    private DialogosCaixa() {
    }

    /** @return o resultado da abertura, ou vazio se cancelado */
    public static <T> Optional<T> abrir(String terminal, Function<String, T> acao) {
        DialogoFormulario formulario = new DialogoFormulario("Abrir caixa — " + terminal,
                "Conte o dinheiro deixado na gaveta para troco e informe o total. Pode ser zero.", "Abrir caixa");
        TextField fundo = campoValor();
        formulario.adicionarCampo("Fundo de troco (R$) *", fundo);
        return formulario.mostrar(() -> acao.apply(fundo.getText()), null);
    }

    /** @return o resultado da gravação, ou vazio se cancelado */
    public static <T> Optional<T> movimentar(Movimentar<T> acao) {
        DialogoFormulario formulario = new DialogoFormulario("Sangria ou suprimento",
                "Sangria retira dinheiro da gaveta; suprimento coloca dinheiro para troco.", "Registrar");
        ComboBox<TipoCaixaMovimento> tipo = new ComboBox<>();
        tipo.getItems().setAll(TipoCaixaMovimento.values());
        TextField valor = campoValor();
        ComboBox<String> motivo = new ComboBox<>();
        motivo.setEditable(true);
        motivo.setMaxWidth(Double.MAX_VALUE);
        motivo.setPromptText("Escolha ou digite");
        tipo.valueProperty().addListener((obs, anterior, novo) -> {
            motivo.getItems().setAll(novo == TipoCaixaMovimento.SUPRIMENTO ? SUGESTOES_SUPRIMENTO : SUGESTOES_SANGRIA);
            motivo.getEditor().clear();
        });
        tipo.setValue(TipoCaixaMovimento.SANGRIA);

        formulario.adicionarCampo("Tipo *", tipo);
        formulario.adicionarCampo("Valor (R$) *", valor);
        formulario.adicionarCampo("Motivo *", motivo);
        return formulario.mostrar(() -> acao.executar(tipo.getValue(), valor.getText(), motivo.getEditor().getText()),
                null);
    }

    /**
     * Fechamento às cegas: pede só o dinheiro contado, sem mostrar o esperado.
     *
     * @return o resultado do fechamento, ou vazio se cancelado (a ação pode devolver nulo para desistir)
     */
    public static <T> Optional<T> fechar(String terminal, Function<String, T> acao) {
        DialogoFormulario formulario = new DialogoFormulario("Fechar caixa — " + terminal,
                "Conte todo o dinheiro da gaveta (inclusive o fundo de troco) e informe o total. O valor esperado só "
                        + "aparece depois do fechamento.", "Fechar caixa");
        TextField contado = campoValor();
        formulario.adicionarCampo("Dinheiro contado (R$) *", contado);
        return formulario.mostrar(() -> acao.apply(contado.getText()), null);
    }

    private static TextField campoValor() {
        TextField campo = new TextField();
        campo.getStyleClass().add("campo-numero");
        campo.setPromptText("0,00");
        return campo;
    }
}
