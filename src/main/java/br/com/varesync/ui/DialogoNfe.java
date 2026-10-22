package br.com.varesync.ui;

import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.service.ImportacaoNfeService;
import br.com.varesync.service.LeitorNfe;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Conferência do XML da NF-e antes de gerar a entrada (6.11): emitente, nota e itens com o produto vinculado pelo
 * código de barras; itens sem vínculo recebem o produto pela busca ou pelo cadastro. "Gerar entrada" só fica
 * disponível com todos os itens vinculados.
 */
public final class DialogoNfe {

    private DialogoNfe() {
    }

    /**
     * @param pesquisa  busca de produtos (nome ou código)
     * @param porId     produto escolhido na busca ou cadastrado
     * @param cadastrar abre o cadastro de produto preenchido com o item; devolve o código do produto salvo (nulo =
     *                  sem permissão de cadastrar: o botão não aparece)
     * @param gerar     escolhas (nº do item → produto) → número da entrada gerada
     */
    public record Acoes(Function<String, List<ProdutoResumo>> pesquisa, Function<Long, ProdutoVenda> porId,
                        Function<LeitorNfe.Item, Optional<Long>> cadastrar, Function<Map<Integer, Long>, Long> gerar) {
    }

    /** @return número da entrada gerada, ou vazio se cancelado */
    public static Optional<Long> conferir(ImportacaoNfeService.Previa previa, Acoes acoes) {
        LeitorNfe.Nota nota = previa.nota();
        Map<Integer, ProdutoVenda> vinculos = new LinkedHashMap<>();
        previa.itens().forEach(i -> vinculos.put(i.item().numero(), i.produto()));

        Dialog<Long> dialogo = new Dialog<>();
        dialogo.setTitle(Dialogos.TITULO);
        dialogo.setHeaderText("Importar NF-e" + (nota.numero() == null || nota.numero().isBlank() ? ""
                : " nº " + nota.numero()));
        ButtonType gerar = new ButtonType("Gerar entrada", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(gerar,
                new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));

        LeitorNfe.Emitente e = nota.emitente();
        Label emitente = rotulo("Emitente: " + e.razaoSocial() + " (" + CpfCnpj.formatar(CpfCnpj.normalizar(
                e.documento())) + ") — " + (previa.fornecedor() != null ? "fornecedor já cadastrado"
                : "será cadastrado como fornecedor"));
        Label dadosNota = rotulo("Emissão: " + (nota.emissao() == null ? Formatos.SEM_VALOR : Formatos.data(nota.emissao()))
                + " · Valor da nota: " + (nota.valorTotal() == null ? Formatos.SEM_VALOR : Formatos.moeda(nota.valorTotal()))
                + " · " + nota.itens().size() + (nota.itens().size() == 1 ? " item" : " itens")
                + ". A entrada é gerada ABERTA: confira e confirme na tela Compras.");

        TableView<ImportacaoNfeService.ItemPrevia> tabela = new TableView<>();
        tabela.setPrefSize(940, 300);
        tabela.getColumns().add(coluna("Item", 45, i -> String.valueOf(i.item().numero())));
        tabela.getColumns().add(coluna("Código de barras", 120, i -> i.item().codigoBarras() == null ? "(sem GTIN)"
                : i.item().codigoBarras()));
        tabela.getColumns().add(coluna("Descrição na nota", 230, i -> i.item().descricao()));
        tabela.getColumns().add(coluna("Quantidade", 120, i -> Formatos.numero(i.item().quantidade(),
                i.item().quantidade().stripTrailingZeros().scale() > 0 ? Dinheiro.ESCALA_QUANTIDADE : 0) + " "
                + i.item().unidade() + (i.item().unidadeTributavel() ? " (trib.)" : "")));
        tabela.getColumns().add(coluna("Valor", 90, i -> Formatos.moeda(i.item().valorTotal())));
        tabela.getColumns().add(coluna("Produto no sistema", 260, i -> {
            ProdutoVenda p = vinculos.get(i.item().numero());
            return p == null ? "— sem vínculo: escolha ou cadastre —" : p.nome() + " (código " + p.id() + ")"
                    + (p.ativo() ? "" : " — INATIVO");
        }));
        tabela.getItems().setAll(previa.itens());
        tabela.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tabela.setRowFactory(t -> {
            TableRow<ImportacaoNfeService.ItemPrevia> linha = new TableRow<>() {
                @Override
                protected void updateItem(ImportacaoNfeService.ItemPrevia i, boolean vazio) {
                    super.updateItem(i, vazio);
                    getStyleClass().remove("linha-erro");
                    if (!vazio && i != null && vinculos.get(i.item().numero()) == null) {
                        getStyleClass().add("linha-erro");
                    }
                }
            };
            return linha;
        });

        Label mensagem = rotulo("");
        mensagem.getStyleClass().add("mensagem-erro");
        Label situacao = rotulo("");
        Button gerarBotao = (Button) dialogo.getDialogPane().lookupButton(gerar);
        Runnable atualizar = () -> {
            long faltam = vinculos.values().stream().filter(p -> p == null).count();
            tabela.refresh();
            if (previa.entradaExistente() != null) {
                situacao.setText("Esta nota já foi lançada (entrada nº " + previa.entradaExistente() + "). Não é "
                        + "possível gerar outra entrada com ela.");
                situacao.getStyleClass().add("mensagem-erro");
            } else {
                situacao.setText(faltam == 0 ? "Todos os itens têm produto." : (faltam == 1 ? "1 item está"
                        : faltam + " itens estão") + " sem produto (em vermelho): selecione e use \"Escolher "
                        + "produto…\"" + (acoes.cadastrar() != null ? " ou \"Cadastrar produto…\"." : "."));
            }
            gerarBotao.setDisable(faltam > 0 || previa.entradaExistente() != null);
        };

        Button escolher = new Button("Escolher produto…");
        escolher.setOnAction(ev -> {
            ImportacaoNfeService.ItemPrevia sel = tabela.getSelectionModel().getSelectedItem();
            if (sel == null) {
                mensagem.setText("Selecione um item da lista.");
                return;
            }
            BuscaProdutoDialogo.buscar("Produto do item " + sel.item().numero() + " — " + sel.item().descricao(),
                    "Escolher", false, primeiraPalavra(sel.item().descricao()), acoes.pesquisa()).ifPresent(p -> {
                        vinculos.put(sel.item().numero(), acoes.porId().apply(p.id()));
                        mensagem.setText("");
                        atualizar.run();
                    });
        });
        HBox botoes = new HBox(10, escolher);
        if (acoes.cadastrar() != null) {
            Button cadastrar = new Button("Cadastrar produto…");
            cadastrar.setOnAction(ev -> {
                ImportacaoNfeService.ItemPrevia sel = tabela.getSelectionModel().getSelectedItem();
                if (sel == null) {
                    mensagem.setText("Selecione um item da lista.");
                    return;
                }
                acoes.cadastrar().apply(sel.item()).ifPresent(id -> {
                    vinculos.put(sel.item().numero(), acoes.porId().apply(id));
                    mensagem.setText("");
                    atualizar.run();
                });
            });
            botoes.getChildren().add(cadastrar);
        }
        tabela.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                escolher.fire();
            }
        });

        Long[] gerada = new Long[1];
        gerarBotao.addEventFilter(ActionEvent.ACTION, ev -> {
            Map<Integer, Long> escolhidos = new LinkedHashMap<>();
            vinculos.forEach((numero, p) -> escolhidos.put(numero, p == null ? null : p.id()));
            try {
                gerada[0] = acoes.gerar().apply(escolhidos);
            } catch (NegocioException ex) {
                mensagem.setText(ex.getMessage());
                dialogo.getDialogPane().getScene().getWindow().sizeToScene();
                ev.consume();
            }
        });
        dialogo.setResultConverter(tipo -> tipo == gerar ? gerada[0] : null);

        VBox conteudo = new VBox(10, emitente, dadosNota, tabela, botoes, situacao, mensagem);
        dialogo.getDialogPane().setContent(conteudo);
        Dialogos.estilizar(dialogo.getDialogPane());
        atualizar.run();
        tabela.getSelectionModel().selectFirst();
        return dialogo.showAndWait();
    }

    /** Primeira palavra da descrição da nota, para abrir a busca já filtrada. */
    static String primeiraPalavra(String descricao) {
        String texto = descricao == null ? "" : descricao.strip();
        int espaco = texto.indexOf(' ');
        return espaco > 0 ? texto.substring(0, espaco) : texto;
    }

    /** Custo unitário do item (valor ÷ quantidade, 4 casas), para o cadastro do produto. */
    public static BigDecimal custoUnitario(LeitorNfe.Item item) {
        return Dinheiro.dividir(item.valorTotal(), item.quantidade(), Dinheiro.ESCALA_CUSTO).orElse(null);
    }

    private static Label rotulo(String texto) {
        Label rotulo = new Label(texto);
        rotulo.setWrapText(true);
        rotulo.setMaxWidth(940);
        rotulo.setMinHeight(Region.USE_PREF_SIZE);
        return rotulo;
    }

    private static <T> TableColumn<T, String> coluna(String titulo, double largura, Function<T, String> valor) {
        TableColumn<T, String> coluna = new TableColumn<>(titulo);
        coluna.setPrefWidth(largura);
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
        return coluna;
    }
}
