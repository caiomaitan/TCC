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
import java.util.function.BiFunction;
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
 * código de barras ou pelo código do fornecedor (F16.1, com o fator); itens sem vínculo recebem o produto pela busca ou
 * pelo cadastro, com o fator perguntado (o vínculo fica gravado para as próximas notas). "Gerar entrada" só fica
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
     * @param gerar     escolhas feitas à mão (nº do item → produto) e os fatores delas → número da entrada gerada
     */
    public record Acoes(Function<String, List<ProdutoResumo>> pesquisa, Function<Long, ProdutoVenda> porId,
                        Function<LeitorNfe.Item, Optional<Long>> cadastrar,
                        BiFunction<Map<Integer, Long>, Map<Integer, BigDecimal>, Long> gerar) {
    }

    /** @return número da entrada gerada, ou vazio se cancelado */
    public static Optional<Long> conferir(ImportacaoNfeService.Previa previa, Acoes acoes) {
        LeitorNfe.Nota nota = previa.nota();
        Map<Integer, ProdutoVenda> vinculos = new LinkedHashMap<>();
        previa.itens().forEach(i -> vinculos.put(i.item().numero(), i.produto()));
        // Escolhas feitas à mão nesta conferência e o fator de cada uma (F16.1).
        Map<Integer, BigDecimal> fatores = new LinkedHashMap<>();

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
        tabela.getColumns().add(coluna("Descrição na nota", 200, i -> i.item().descricao()));
        tabela.getColumns().add(coluna("Quantidade", 120, i -> {
            BigDecimal fator = fatores.containsKey(i.item().numero()) ? fatores.get(i.item().numero()) : i.fator();
            if (fator != null && vinculos.get(i.item().numero()) != null) {
                return quantidade(i.item().comercial().quantidade()) + " " + i.item().comercial().unidade() + " × "
                        + quantidade(fator) + " = " + quantidade(i.item().comercial().quantidade().multiply(fator));
            }
            return quantidade(i.item().quantidade()) + " " + i.item().unidade()
                    + (i.item().unidadeTributavel() ? " (trib.)" : "");
        }));
        tabela.getColumns().add(coluna("Valor", 90, i -> Formatos.moeda(i.item().valorTotal())));
        tabela.getColumns().add(coluna("Produto no sistema", 250, i -> {
            ProdutoVenda p = vinculos.get(i.item().numero());
            return p == null ? "— sem vínculo: escolha ou cadastre —" : p.nome() + " (código " + p.id() + ")"
                    + (p.ativo() ? "" : " — INATIVO");
        }));
        tabela.getColumns().add(coluna("Vínculo", 115, i -> vinculos.get(i.item().numero()) == null ? ""
                : fatores.containsKey(i.item().numero()) ? "Escolhido agora"
                : i.peloFornecedor() ? "Cód. fornecedor" : i.embalagem() ? "Cód. embalagem" : "Cód. de barras"));
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
                    "Escolher", false, primeiraPalavra(sel.item().descricao()), acoes.pesquisa())
                    .ifPresent(p -> escolhido(sel, acoes.porId().apply(p.id()), vinculos, fatores, mensagem, atualizar));
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
                acoes.cadastrar().apply(sel.item()).ifPresent(id -> escolhido(sel, acoes.porId().apply(id), vinculos,
                        fatores, mensagem, atualizar));
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
            // Só as escolhas feitas à mão: os demais itens o service vincula de novo (código de barras ou do fornecedor).
            Map<Integer, Long> escolhidos = new LinkedHashMap<>();
            fatores.keySet().forEach(numero -> escolhidos.put(numero, vinculos.get(numero).id()));
            try {
                gerada[0] = acoes.gerar().apply(escolhidos, fatores);
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

    /** Produto escolhido à mão: pergunta o fator (cancelar desfaz a escolha) e atualiza a tabela. */
    private static void escolhido(ImportacaoNfeService.ItemPrevia sel, ProdutoVenda produto,
                                  Map<Integer, ProdutoVenda> vinculos, Map<Integer, BigDecimal> fatores, Label mensagem,
                                  Runnable atualizar) {
        LeitorNfe.Comercial comercial = sel.item().comercial();
        BigDecimal sugerido = fatores.containsKey(sel.item().numero()) ? fatores.get(sel.item().numero())
                : sel.fator() != null ? sel.fator() : ImportacaoNfeService.fatorSugerido(sel.item());
        DialogosVinculo.fatorDaNota(sel.item().descricao(), quantidade(comercial.quantidade()) + " "
                        + comercial.unidade(), produto.unidade(), sugerido)
                .ifPresent(fator -> {
                    vinculos.put(sel.item().numero(), produto);
                    fatores.put(sel.item().numero(), fator);
                    mensagem.setText("");
                    atualizar.run();
                });
    }

    private static String quantidade(BigDecimal valor) {
        return Formatos.numero(valor, valor.stripTrailingZeros().scale() > 0 ? Dinheiro.ESCALA_QUANTIDADE : 0);
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
