package br.com.varesync.ui;

import br.com.varesync.model.DadosVinculo;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.ProdutoFornecedor;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.ProdutoFornecedorService;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Diálogos do fornecedor do produto (F16.1): incluir ou alterar o vínculo na aba Fornecedores do cadastro e o fator
 * do produto escolhido à mão na importação da NF-e.
 */
public final class DialogosVinculo {

    private DialogosVinculo() {
    }

    /**
     * Inclui ({@code atual} nulo) ou altera um vínculo.
     *
     * @param fornecedores ativos, para o vínculo novo
     * @param unidadeVenda sigla da unidade do produto ("UN")
     * @param salvar       grava e devolve o id; {@link NegocioException} mantém o diálogo aberto
     * @return id gravado, ou vazio se cancelado
     */
    public static Optional<Long> editar(ProdutoFornecedor atual, List<Fornecedor> fornecedores, String unidadeVenda,
                                        Function<DadosVinculo, Long> salvar) {
        DialogoFormulario f = new DialogoFormulario(atual == null ? "Incluir fornecedor do produto"
                : "Fornecedor do produto: " + atual.fornecedorNome(),
                "O fator diz quantas unidades de venda (" + unidadeVenda + ") vêm em 1 unidade de compra: caixa com 12 "
                        + "= 12. O código no fornecedor é o da NF-e dele (cProd): com ele, a importação reconhece o "
                        + "item sozinha.", atual == null ? "Incluir" : "Salvar");
        ComboBox<Fornecedor> fornecedor = new ComboBox<>();
        fornecedor.setMaxWidth(Double.MAX_VALUE);
        Label fornecedorFixo = new Label(atual == null ? "" : atual.fornecedorNome());
        if (atual == null) {
            fornecedor.getItems().setAll(fornecedores);
            fornecedor.setPromptText("Escolha o fornecedor");
            f.adicionarCampo("Fornecedor *", fornecedor);
        } else {
            f.adicionarCampo("Fornecedor", fornecedorFixo);
        }
        TextField codigo = new TextField(atual == null || atual.codigoNoFornecedor() == null ? ""
                : atual.codigoNoFornecedor());
        codigo.setPromptText("cProd da NF-e (opcional)");
        TextField unidade = new TextField(atual == null || atual.unidadeCompra() == null ? "" : atual.unidadeCompra());
        unidade.setPromptText("ex.: CX (opcional)");
        TextField fator = new TextField(atual == null ? "1" : numero(atual.fator()));
        fator.getStyleClass().add("campo-numero");
        TextField prazo = new TextField(atual == null ? "0" : String.valueOf(atual.prazoEntregaDias()));
        prazo.getStyleClass().add("campo-numero");
        CheckBox principal = new CheckBox("Fornecedor principal (dá o prazo e o fator da sugestão de compra)");
        principal.setSelected(atual != null && atual.principal());
        f.adicionarCampo("Código no fornecedor", codigo);
        f.adicionarCampo("Unidade de compra", unidade);
        f.adicionarCampo("Fator *", fator);
        f.adicionarCampo("Prazo de entrega (dias)", prazo);
        f.adicionarCampo("", principal);
        return f.mostrar(() -> {
            if (atual == null && fornecedor.getValue() == null) {
                throw new NegocioException("Escolha o fornecedor.");
            }
            BigDecimal valorFator = CampoNumero.ler(fator.getText(), "Fator");
            int dias = inteiro(prazo.getText(), "Prazo de entrega");
            return salvar.apply(new DadosVinculo(atual == null ? fornecedor.getValue().id() : atual.fornecedorId(),
                    codigo.getText(), unidade.getText(), valorFator, dias, principal.isSelected()));
        }, null);
    }

    /**
     * Fator do produto escolhido à mão para um item da NF-e: fica gravado no vínculo com o fornecedor.
     *
     * @param sugerido o que a nota indica (unidade tributável ÷ comercial) ou 1
     * @return o fator, ou vazio se cancelado
     */
    public static Optional<BigDecimal> fatorDaNota(String descricaoItem, String quantidadeComercial,
                                                   String unidadeVenda, BigDecimal sugerido) {
        DialogoFormulario f = new DialogoFormulario("Unidades por embalagem da nota",
                "Item da nota: " + descricaoItem + " (" + quantidadeComercial + "). Quantas unidades de venda ("
                        + unidadeVenda + ") vêm em 1 unidade da nota? A quantidade da entrada será a da nota × o fator, "
                        + "e o vínculo fica gravado para as próximas notas deste fornecedor.", "Confirmar");
        TextField fator = new TextField(numero(sugerido));
        fator.getStyleClass().add("campo-numero");
        f.adicionarCampo("Fator *", fator);
        return f.mostrar(() -> {
            BigDecimal valor = CampoNumero.ler(fator.getText(), "Fator");
            return ProdutoFornecedorService.fatorDigitado(valor);
        }, null);
    }

    /** Sem separador de milhar: "1.000" seria lido como 1,000 ({@code Dinheiro.ler}). */
    private static String numero(BigDecimal valor) {
        BigDecimal v = valor.stripTrailingZeros();
        return (v.scale() < 0 ? v.setScale(0) : v).toPlainString().replace('.', ',');
    }

    private static int inteiro(String texto, String rotulo) {
        String t = texto == null ? "" : texto.strip();
        if (t.isEmpty()) {
            return 0;
        }
        if (!Textos.soDigitos(t) || t.length() > 4) {
            throw new NegocioException(rotulo + ": valor inválido (\"" + t + "\"). Informe os dias, de 0 a "
                    + ProdutoFornecedorService.PRAZO_MAXIMO + ".");
        }
        return Integer.parseInt(t);
    }
}
