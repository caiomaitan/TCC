package br.com.varesync.ui;

import br.com.varesync.model.ResultadoAjuste;
import br.com.varesync.model.TipoAjuste;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Diálogos de ajuste manual de estoque (6.4): Entrada/Saída/Perda e "Ajustar saldo para…".
 * A gravação é feita pela ação recebida; {@code NegocioException} mantém o diálogo aberto com a mensagem.
 */
public final class DialogosEstoque {

    static final List<String> SUGESTOES_SALDO = List.of("Acerto de contagem", "Conferência de estoque");

    /** Grava o ajuste com os textos digitados. */
    @FunctionalInterface
    public interface Ajustar {
        ResultadoAjuste executar(TipoAjuste tipo, String quantidade, String motivo, String observacao);
    }

    /** Grava o novo saldo com os textos digitados. */
    @FunctionalInterface
    public interface AjustarSaldo {
        ResultadoAjuste executar(String novoSaldo, String motivo);
    }

    private DialogosEstoque() {
    }

    /**
     * @param saldoAtual "10 UN", só para orientar
     * @return o ajuste gravado, ou vazio se cancelado
     */
    public static Optional<ResultadoAjuste> ajustar(String produto, String saldoAtual, Ajustar acao) {
        DialogoFormulario formulario = new DialogoFormulario("Ajustar estoque — " + produto,
                "Saldo atual: " + saldoAtual + ". Digite a quantidade sem sinal: o tipo define se entra ou sai.",
                "Registrar");
        ComboBox<TipoAjuste> tipo = new ComboBox<>();
        tipo.getItems().setAll(TipoAjuste.values());
        TextField quantidade = new TextField();
        quantidade.getStyleClass().add("campo-numero");
        ComboBox<String> motivo = new ComboBox<>();
        motivo.setMaxWidth(Double.MAX_VALUE);
        TextField observacao = new TextField();
        observacao.setPromptText("Opcional; obrigatória na perda \"Outros\"");
        tipo.valueProperty().addListener((obs, anterior, novo) -> trocarSugestoes(motivo, novo));
        tipo.setValue(TipoAjuste.ENTRADA);

        formulario.adicionarCampo("Tipo *", tipo);
        formulario.adicionarCampo("Quantidade *", quantidade);
        formulario.adicionarCampo("Motivo *", motivo);
        formulario.adicionarCampo("Observação", observacao);
        return formulario.mostrar(() -> acao.executar(tipo.getValue(), quantidade.getText(), texto(motivo),
                observacao.getText()), null);
    }

    /**
     * @param saldoAtual "10 UN", só para orientar
     * @param previa     texto que explica o movimento que será gerado para o saldo digitado
     * @return o ajuste gravado, ou vazio se cancelado
     */
    public static Optional<ResultadoAjuste> ajustarSaldoPara(String produto, String saldoAtual,
                                                             Function<String, String> previa, AjustarSaldo acao) {
        DialogoFormulario formulario = new DialogoFormulario("Ajustar saldo — " + produto,
                "Saldo atual: " + saldoAtual + ". Informe o saldo correto: o sistema calcula a diferença e registra "
                        + "uma entrada ou uma saída de ajuste.", "Ajustar");
        TextField novoSaldo = new TextField();
        novoSaldo.getStyleClass().add("campo-numero");
        Label movimento = new Label();
        movimento.setWrapText(true);
        movimento.getStyleClass().add("subtitulo");
        novoSaldo.textProperty().addListener((obs, anterior, texto) -> movimento.setText(previa.apply(texto)));
        ComboBox<String> motivo = new ComboBox<>();
        motivo.setMaxWidth(Double.MAX_VALUE);
        motivo.setEditable(true);
        motivo.getItems().setAll(SUGESTOES_SALDO);
        motivo.getEditor().setText(SUGESTOES_SALDO.get(0));

        formulario.adicionarCampo("Novo saldo *", novoSaldo);
        formulario.adicionarCampo("", movimento);
        formulario.adicionarCampo("Motivo *", motivo);
        return formulario.mostrar(() -> acao.executar(novoSaldo.getText(), texto(motivo)), null);
    }

    /** Na perda o motivo vem da lista; nos demais tipos a lista só sugere e o texto é livre. */
    private static void trocarSugestoes(ComboBox<String> motivo, TipoAjuste tipo) {
        motivo.setValue(null);
        motivo.getEditor().clear();
        motivo.setEditable(tipo != null && !tipo.motivoDaLista());
        motivo.getItems().setAll(tipo == null ? List.of() : tipo.sugestoes());
        motivo.setPromptText(tipo != null && tipo.motivoDaLista() ? "Escolha o motivo" : "Escolha ou digite");
    }

    private static String texto(ComboBox<String> motivo) {
        if (motivo.isEditable()) {
            return motivo.getEditor().getText();
        }
        return motivo.getValue() == null ? "" : motivo.getValue();
    }
}
