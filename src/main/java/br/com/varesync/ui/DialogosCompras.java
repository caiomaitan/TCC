package br.com.varesync.ui;

import br.com.varesync.model.CustoAtualizado;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.ResultadoEntrada;
import br.com.varesync.model.SugestaoReajuste;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Diálogos da entrada de mercadoria (6.10): dados da nota (nova ou correção) e, depois de confirmar, os custos
 * recalculados com a sugestão de reajuste dos produtos que ficaram abaixo da margem mínima.
 */
public final class DialogosCompras {

    private DialogosCompras() {
    }

    /**
     * @param atual  entrada em edição; nula na entrada nova
     * @param gravar dados → número da entrada; {@code NegocioException} mantém o diálogo aberto com a mensagem
     * @return número da entrada gravada, ou vazio se cancelado
     */
    public static Optional<Long> dadosNota(List<Fornecedor> fornecedores, EntradaResumo atual,
                                           Function<DadosEntrada, Long> gravar) {
        DialogoFormulario formulario = new DialogoFormulario(atual == null ? "Nova entrada de mercadoria"
                : "Dados da nota — entrada nº " + atual.id(),
                "Informe os dados da nota fiscal. O valor total serve para conferir a soma dos itens antes de "
                        + "confirmar.", atual == null ? "Criar" : "Salvar");
        ComboBox<Fornecedor> fornecedor = new ComboBox<>();
        fornecedor.getItems().setAll(fornecedores);
        fornecedor.setMaxWidth(Double.MAX_VALUE);
        fornecedor.setPromptText(fornecedores.isEmpty() ? "Cadastre um fornecedor antes" : "Escolha o fornecedor");
        TextField numero = new TextField();
        numero.setPromptText("Ex.: 1234");
        DatePicker emissao = new DatePicker();
        emissao.setConverter(new ConversorData());
        emissao.setPromptText("dd/mm/aaaa");
        emissao.setMaxWidth(Double.MAX_VALUE);
        TextField valor = new TextField();
        valor.setPromptText("0,00");
        valor.getStyleClass().add("campo-numero");
        if (atual != null) {
            fornecedores.stream().filter(f -> f.id() == atual.fornecedorId()).findFirst()
                    .ifPresent(fornecedor::setValue);
            numero.setText(atual.numeroDocumento() == null ? "" : atual.numeroDocumento());
            emissao.setValue(atual.dataEmissao());
            valor.setText(atual.valorTotalNota() == null ? ""
                    : Formatos.numero(atual.valorTotalNota(), Dinheiro.ESCALA_DINHEIRO));
        }
        formulario.adicionarCampo("Fornecedor *", fornecedor);
        formulario.adicionarCampo("Número da nota", numero);
        formulario.adicionarCampo("Data de emissão", emissao);
        formulario.adicionarCampo("Valor total da nota", valor);
        return formulario.mostrar(() -> {
            emissao.setValue(emissao.getConverter().fromString(emissao.getEditor().getText()));
            if (!Textos.vazio(emissao.getEditor().getText()) && emissao.getValue() == null) {
                throw new NegocioException("Data de emissão inválida: use dd/mm/aaaa.");
            }
            Fornecedor escolhido = fornecedor.getValue();
            return gravar.apply(new DadosEntrada(escolhido == null ? null : escolhido.id(), numero.getText(),
                    emissao.getValue(), CampoNumero.ler(valor.getText(), "Valor total da nota")));
        }, null);
    }

    /**
     * Parcelas da nota (F15.3): uma linha por parcela, com vencimento e valor; lista vazia = sem parcelas.
     *
     * @return true se gravou
     */
    public static boolean parcelas(String titulo, List<ParcelaEntrada> atuais, Consumer<List<ParcelaEntrada>> gravar) {
        DialogoFormulario formulario = new DialogoFormulario("Parcelas — " + titulo,
                "Ao confirmar a entrada, cada parcela vira uma conta a pagar.", "Salvar");
        record Linha(DatePicker vencimento, TextField valor) {
        }
        List<Linha> linhas = new ArrayList<>();
        VBox lista = new VBox(6);
        Consumer<ParcelaEntrada> adicionar = p -> {
            DatePicker vencimento = new DatePicker(p == null ? null : p.vencimento());
            vencimento.setConverter(new ConversorData());
            vencimento.setPromptText("dd/mm/aaaa");
            TextField valor = new TextField(p == null ? "" : Formatos.numero(p.valor(), Dinheiro.ESCALA_DINHEIRO));
            valor.setPromptText("0,00");
            valor.getStyleClass().add("campo-numero");
            Linha linha = new Linha(vencimento, valor);
            Button remover = new Button("Remover");
            HBox caixa = new HBox(6, vencimento, valor, remover);
            remover.setOnAction(e -> {
                linhas.remove(linha);
                lista.getChildren().remove(caixa);
                caixa.getScene().getWindow().sizeToScene();
            });
            linhas.add(linha);
            lista.getChildren().add(caixa);
        };
        atuais.forEach(adicionar);
        Button mais = new Button("Adicionar parcela");
        mais.setOnAction(e -> {
            adicionar.accept(null);
            mais.getScene().getWindow().sizeToScene();
        });
        formulario.adicionarCampo("Parcelas", lista);
        formulario.adicionarCampo("", mais);
        return formulario.mostrar(() -> {
            List<ParcelaEntrada> parcelas = new ArrayList<>();
            for (Linha l : linhas) {
                int numero = parcelas.size() + 1;
                java.time.LocalDate data = l.vencimento().getConverter().fromString(l.vencimento().getEditor().getText());
                parcelas.add(new ParcelaEntrada(numero, data, CampoNumero.ler(l.valor().getText(),
                        "Valor da parcela " + numero)));
            }
            gravar.accept(parcelas);
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /**
     * Resultado da confirmação: custo médio de cada produto e, se houver, os produtos abaixo da margem mínima com o
     * preço sugerido e o atalho para o cadastro do produto.
     *
     * @param abrirProduto abre o cadastro do produto (nulo = sem o atalho, para quem não tem PRODUTOS)
     */
    public static void resultado(ResultadoEntrada r, Consumer<Long> abrirProduto) {
        Dialog<Void> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText("Entrada nº " + r.entrada().id() + " confirmada");
        dialogo.getDialogPane().getButtonTypes().add(new ButtonType("Fechar", ButtonBar.ButtonData.OK_DONE));

        TableView<CustoAtualizado> custos = new TableView<>();
        custos.setPrefSize(720, 170);
        custos.getColumns().add(coluna("Produto", 260, CustoAtualizado::produtoNome));
        custos.getColumns().add(coluna("Custo anterior", 110, c -> custo(c.custoAnterior())));
        custos.getColumns().add(coluna("Custo da nota", 110, c -> custo(c.custoEntrada())));
        custos.getColumns().add(coluna("Novo custo médio", 120, c -> custo(c.custoNovo())));
        custos.getItems().setAll(r.custos());
        custos.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        VBox conteudo = new VBox(10, new Label("Estoque e custo médio atualizados:"), custos);
        if (r.sugestoes().isEmpty()) {
            conteudo.getChildren().add(new Label("Nenhum produto ficou abaixo da margem mínima ("
                    + Formatos.percentual(r.margemMinimaPct()) + ")."));
        } else {
            Label aviso = new Label(r.sugestoes().size() + (r.sugestoes().size() == 1 ? " produto ficou" : " produtos "
                    + "ficaram") + " abaixo da margem mínima (" + Formatos.percentual(r.margemMinimaPct())
                    + ") com o novo custo. Confira o preço de venda:");
            aviso.setWrapText(true);
            aviso.getStyleClass().add("mensagem-erro");
            TableView<SugestaoReajuste> sugestoes = new TableView<>();
            sugestoes.setPrefSize(720, 170);
            sugestoes.getColumns().add(coluna("Produto", 240, SugestaoReajuste::produtoNome));
            sugestoes.getColumns().add(coluna("Novo custo", 100, s -> custo(s.custoNovo())));
            sugestoes.getColumns().add(coluna("Preço atual", 100, s -> Formatos.moeda(s.precoVenda())));
            sugestoes.getColumns().add(coluna("Margem", 80, s -> s.margem() == null ? Formatos.SEM_VALOR
                    : Formatos.percentual(s.margem())));
            sugestoes.getColumns().add(coluna("Preço sugerido", 110, s -> s.precoSugerido() == null
                    ? Formatos.SEM_VALOR : Formatos.moeda(s.precoSugerido())));
            sugestoes.getItems().setAll(r.sugestoes());
            sugestoes.getSelectionModel().selectFirst();
            sugestoes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            conteudo.getChildren().addAll(aviso, sugestoes);
            if (abrirProduto != null) {
                Button abrir = new Button("Reajustar preço do produto selecionado…");
                abrir.setOnAction(e -> {
                    SugestaoReajuste s = sugestoes.getSelectionModel().getSelectedItem();
                    if (s != null) {
                        abrirProduto.accept(s.produtoId());
                    }
                });
                conteudo.getChildren().add(new HBox(abrir));
            }
        }
        dialogo.getDialogPane().setContent(conteudo);
        Dialogos.estilizar(dialogo.getDialogPane());
        dialogo.showAndWait();
    }

    private static String custo(BigDecimal valor) {
        return valor == null ? Formatos.SEM_VALOR : "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }

    private static <T> TableColumn<T, String> coluna(String titulo, double largura, Function<T, String> valor) {
        TableColumn<T, String> coluna = new TableColumn<>(titulo);
        coluna.setPrefWidth(largura);
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
        return coluna;
    }
}
