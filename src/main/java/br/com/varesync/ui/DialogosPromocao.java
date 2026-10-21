package br.com.varesync.ui;

import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.Promocao;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Formulário da promoção (6.8): produto (código, código de barras ou busca), preço promocional e período. */
public final class DialogosPromocao {

    private DialogosPromocao() {
    }

    /** O que o formulário consulta e grava; {@code NegocioException} aparece no diálogo, que continua aberto. */
    public record Acoes(Function<String, ProdutoVenda> porCodigo, Function<String, List<ProdutoResumo>> pesquisa,
                        Function<Long, ProdutoVenda> porId, Function<DadosPromocao, List<String>> avisos,
                        Function<DadosPromocao, Long> gravar) {
    }

    /**
     * @param atual promoção em edição (o produto não muda); nula na nova
     * @return número da promoção gravada, ou vazio se cancelado
     */
    public static Optional<Long> formulario(Promocao atual, LocalDate hoje, Acoes acoes) {
        DialogoFormulario formulario = new DialogoFormulario(atual == null ? "Nova promoção"
                : "Promoção nº " + atual.id(), "O preço promocional vale no PDV do dia de início ao dia de fim, "
                + "inclusive. Duas promoções ativas do mesmo produto não podem ter datas sobrepostas.",
                atual == null ? "Criar" : "Salvar");
        ProdutoVenda[] produto = new ProdutoVenda[1];
        Label infoProduto = new Label();
        infoProduto.setWrapText(true);
        infoProduto.setMaxWidth(380);
        infoProduto.setMinHeight(Region.USE_PREF_SIZE);
        TextField preco = campoNumero("0,00");
        DatePicker inicio = data(atual == null ? hoje : atual.inicio());
        DatePicker fim = data(atual == null ? hoje.plusDays(6) : atual.fim());
        CheckBox ativa = new CheckBox("Ativa");
        ativa.setSelected(atual == null || atual.ativa());

        if (atual == null) {
            TextField codigo = new TextField();
            codigo.setPromptText("Código ou código de barras + Enter");
            Button buscar = new Button("Buscar…");
            HBox.setHgrow(codigo, Priority.ALWAYS);
            Runnable porCodigo = () -> {
                try {
                    produto[0] = acoes.porCodigo().apply(codigo.getText());
                    infoProduto.setText(descrever(produto[0]));
                    infoProduto.getStyleClass().remove("mensagem-erro");
                    preco.requestFocus();
                } catch (NegocioException e) {
                    produto[0] = null;
                    infoProduto.setText(e.getMessage());
                    infoProduto.getStyleClass().add("mensagem-erro");
                }
            };
            codigo.setOnAction(e -> {
                e.consume();
                porCodigo.run();
            });
            buscar.setOnAction(e -> BuscaProdutoDialogo.buscar("Buscar produto", "Escolher", false,
                    Textos.soDigitos(codigo.getText()) ? "" : codigo.getText(), acoes.pesquisa()).ifPresent(p -> {
                        codigo.setText(String.valueOf(p.id()));
                        porCodigo.run();
                    }));
            formulario.adicionarCampo("Produto *", new HBox(8, codigo, buscar));
        } else {
            produto[0] = acoes.porId().apply(atual.produtoId());
            infoProduto.setText(descrever(produto[0]));
            preco.setText(Formatos.numero(atual.precoPromocional(), Dinheiro.ESCALA_DINHEIRO));
            formulario.adicionarCampo("Produto", new Label(atual.produtoNome() + " (código " + atual.produtoId()
                    + ")"));
        }
        formulario.adicionarCampo("", infoProduto);
        formulario.adicionarCampo("Preço promocional *", preco);
        formulario.adicionarCampo("Início *", inicio);
        formulario.adicionarCampo("Fim *", fim);
        formulario.adicionarCampo("", ativa);

        return formulario.mostrar(() -> {
            DadosPromocao dados = new DadosPromocao(produto[0] == null ? null : produto[0].id(),
                    DialogosCompras.ler(preco.getText(), "Preço promocional"), lerData(inicio, "início"),
                    lerData(fim, "fim"), ativa.isSelected());
            List<String> avisos = acoes.avisos().apply(dados);
            if (!avisos.isEmpty() && !Dialogos.confirmar(String.join("\n", avisos) + "\n\nSalvar mesmo assim?")) {
                throw new NegocioException("Nada foi gravado. Ajuste o preço ou confirme o aviso.");
            }
            return acoes.gravar().apply(dados);
        }, null);
    }

    private static String descrever(ProdutoVenda p) {
        return p.nome() + " — preço de venda " + Formatos.moeda(p.precoVenda()) + " / " + p.unidade() + ", custo R$ "
                + Formatos.numero(p.custo(), Dinheiro.ESCALA_CUSTO) + (p.ativo() ? "" : " (inativo)");
    }

    private static TextField campoNumero(String exemplo) {
        TextField campo = new TextField();
        campo.setPromptText(exemplo);
        campo.getStyleClass().add("campo-numero");
        return campo;
    }

    private static DatePicker data(LocalDate valor) {
        DatePicker campo = new DatePicker(valor);
        campo.setConverter(new ConversorData());
        campo.setPromptText("dd/mm/aaaa");
        campo.setMaxWidth(Double.MAX_VALUE);
        return campo;
    }

    /** O texto digitado vale mesmo sem sair do campo. */
    private static LocalDate lerData(DatePicker campo, String rotulo) {
        String texto = campo.getEditor().getText();
        if (Textos.vazio(texto)) {
            return null;
        }
        LocalDate data = campo.getConverter().fromString(texto);
        if (data == null) {
            throw new NegocioException("Data de " + rotulo + " inválida: use dd/mm/aaaa.");
        }
        campo.setValue(data);
        return data;
    }
}
