package br.com.varesync.ui;

import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.TipoCaixaMovimento;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
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
        T executar(TipoCaixaMovimento tipo, String valor, String motivo, Long contaId);
    }

    private DialogosCaixa() {
    }

    /**
     * Campo da conta, já com a padrão do caixa escolhida; nulo sem contas (integração desligada: o campo não aparece).
     */
    private static ComboBox<ContaFinanceira> campoConta(List<ContaFinanceira> contas) {
        if (contas.isEmpty()) {
            return null;
        }
        ComboBox<ContaFinanceira> conta = new ComboBox<>();
        conta.setId("campoConta");
        conta.getItems().setAll(contas);
        conta.setMaxWidth(Double.MAX_VALUE);
        contas.stream().filter(ContaFinanceira::padraoCaixa).findFirst().ifPresent(conta::setValue);
        return conta;
    }

    private static Long contaEscolhida(ComboBox<ContaFinanceira> conta) {
        return conta == null || conta.getValue() == null ? null : conta.getValue().id();
    }

    /**
     * @param contas contas para escolher de onde sai o fundo de troco; vazio = integração desligada
     * @return o resultado da abertura, ou vazio se cancelado
     */
    public static <T> Optional<T> abrir(String terminal, List<ContaFinanceira> contas,
                                        BiFunction<String, Long, T> acao) {
        DialogoFormulario formulario = new DialogoFormulario("Abrir caixa — " + terminal,
                "Conte o dinheiro deixado na gaveta para troco e informe o total. Pode ser zero.", "Abrir caixa");
        TextField fundo = campoValor();
        formulario.adicionarCampo("Fundo de troco (R$) *", fundo);
        ComboBox<ContaFinanceira> conta = campoConta(contas);
        if (conta != null) {
            formulario.adicionarCampo("Sai da conta *", conta);
        }
        return formulario.mostrar(() -> acao.apply(fundo.getText(), contaEscolhida(conta)), null);
    }

    /**
     * @param contas contas para a sangria entrar ou o suprimento sair; vazio = caixa não integrado
     * @return o resultado da gravação, ou vazio se cancelado
     */
    public static <T> Optional<T> movimentar(List<ContaFinanceira> contas, Movimentar<T> acao) {
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
        ComboBox<ContaFinanceira> conta = campoConta(contas);
        if (conta != null) {
            formulario.adicionarCampo("Conta *", conta);
        }
        return formulario.mostrar(() -> acao.executar(tipo.getValue(), valor.getText(), motivo.getEditor().getText(),
                contaEscolhida(conta)), null);
    }

    /**
     * Fechamento às cegas: pede só o dinheiro contado, sem mostrar o esperado.
     *
     * @param contas contas para o dinheiro contado entrar; vazio = caixa não integrado
     * @return o resultado do fechamento, ou vazio se cancelado (a ação pode devolver nulo para desistir)
     */
    public static <T> Optional<T> fechar(String terminal, List<ContaFinanceira> contas,
                                         BiFunction<String, Long, T> acao) {
        DialogoFormulario formulario = new DialogoFormulario("Fechar caixa — " + terminal,
                "Conte todo o dinheiro da gaveta (inclusive o fundo de troco) e informe o total. O valor esperado só "
                        + "aparece depois do fechamento.", "Fechar caixa");
        TextField contado = campoValor();
        formulario.adicionarCampo("Dinheiro contado (R$) *", contado);
        ComboBox<ContaFinanceira> conta = campoConta(contas);
        if (conta != null) {
            formulario.adicionarCampo("Entra na conta *", conta);
        }
        return formulario.mostrar(() -> acao.apply(contado.getText(), contaEscolhida(conta)), null);
    }

    private static TextField campoValor() {
        TextField campo = new TextField();
        campo.getStyleClass().add("campo-numero");
        campo.setPromptText("0,00");
        return campo;
    }
}
