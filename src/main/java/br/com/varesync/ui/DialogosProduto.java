package br.com.varesync.ui;

import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.BiFunction;
import javafx.scene.control.TextField;

/**
 * Diálogos do cadastro de produto (F20.3): a embalagem de um código de barras e uma faixa de preço por quantidade.
 * O que é digitado vale só na tela; o produto é gravado pelo botão Salvar. Enter confirma, Esc cancela.
 */
public final class DialogosProduto {

    private DialogosProduto() {
    }

    /**
     * Quantidade e preço da embalagem do código (caixa, fardo).
     *
     * @param unidade  sigla da unidade do produto, para os rótulos
     * @param conferir (quantidade, preço) → o código com a embalagem; {@code NegocioException} mantém o diálogo aberto
     * @return o código com a embalagem nova, ou vazio se cancelado
     */
    public static Optional<CodigoBarras> embalagem(CodigoBarras codigo, String unidade,
                                                   BiFunction<BigDecimal, BigDecimal, CodigoBarras> conferir) {
        DialogoFormulario formulario = new DialogoFormulario("Embalagem do código " + codigo.codigo(),
                "Quantas unidades (" + unidade + ") a leitura deste código representa: 1 no código da unidade, 12 na "
                        + "caixa com 12. Com o preço da embalagem, o PDV cobra o menor entre ele e as unidades no "
                        + "preço do dia; sem ele, as unidades no preço do dia (com o preço por quantidade).", "OK");
        TextField quantidade = campoNumero("1");
        quantidade.setText(numero(codigo.quantidade()));
        TextField preco = campoNumero("vazio = sem preço próprio");
        preco.setText(codigo.precoEmbalagem() == null ? ""
                : Formatos.numero(codigo.precoEmbalagem(), Dinheiro.ESCALA_DINHEIRO));
        formulario.adicionarCampo("Qtd. da embalagem (" + unidade + ")", quantidade);
        formulario.adicionarCampo("Preço da embalagem", preco);
        return formulario.mostrar(() -> conferir.apply(
                CampoNumero.ler(quantidade.getText(), "Qtd. da embalagem"),
                CampoNumero.ler(preco.getText(), "Preço da embalagem")), null);
    }

    /**
     * Uma faixa de preço por quantidade.
     *
     * @param atual    faixa em edição; nula na nova
     * @param conferir (quantidade, preço) → a faixa; {@code NegocioException} mantém o diálogo aberto
     * @return a faixa, ou vazio se cancelado
     */
    public static Optional<FaixaPreco> faixa(FaixaPreco atual, String unidade, String precoVenda,
                                             BiFunction<BigDecimal, BigDecimal, FaixaPreco> conferir) {
        DialogoFormulario formulario = new DialogoFormulario(atual == null ? "Nova faixa de preço"
                : "Alterar faixa de preço", "Quando a venda chega a esta quantidade do produto, todas as unidades "
                + "dele passam ao preço da faixa. O preço tem de ser menor que o de venda (" + precoVenda + ") e "
                + "menor que o da faixa de quantidade menor.", "OK");
        TextField quantidade = campoNumero("ex.: 6");
        TextField preco = campoNumero("0,00");
        if (atual != null) {
            quantidade.setText(numero(atual.quantidadeMinima()));
            preco.setText(Formatos.numero(atual.preco(), Dinheiro.ESCALA_DINHEIRO));
        }
        formulario.adicionarCampo("A partir de (" + unidade + ")", quantidade);
        formulario.adicionarCampo("Preço por " + unidade, preco);
        return formulario.mostrar(() -> conferir.apply(
                CampoNumero.ler(quantidade.getText(), "Quantidade"),
                CampoNumero.ler(preco.getText(), "Preço")), null);
    }

    /** Sem separador de milhar nem zeros no fim: "1.234" seria relido como 1,234 (decisão da Fase 2). */
    private static String numero(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static TextField campoNumero(String exemplo) {
        TextField campo = new TextField();
        campo.setPromptText(exemplo);
        campo.getStyleClass().add("campo-numero");
        return campo;
    }
}
