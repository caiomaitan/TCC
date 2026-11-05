package br.com.varesync.ui;

import br.com.varesync.model.ConferenciaPedido;
import br.com.varesync.model.DadosPedido;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Diálogos do pedido de compra (F16.3): dados do pedido (novo ou correção), escolha do pedido que a entrada atende e a
 * conferência pedido × nota, com as quantidades e os custos divergentes em destaque.
 */
public final class DialogosPedido {

    /** Opção "sem pedido" na escolha do pedido da entrada. */
    public static final String SEM_PEDIDO = "Nenhum (entrada sem pedido)";
    private static final String DIVERGENTE = "negativo";
    private static final String LINHA_DIVERGENTE = "linha-aviso";

    private DialogosPedido() {
    }

    /**
     * @param atual  pedido em edição; nulo no pedido novo
     * @param gravar dados → número do pedido; {@code NegocioException} mantém o diálogo aberto com a mensagem
     * @return número do pedido gravado, ou vazio se cancelado
     */
    public static Optional<Long> dados(List<Fornecedor> fornecedores, PedidoCompra atual,
                                       Function<DadosPedido, Long> gravar) {
        DialogoFormulario formulario = new DialogoFormulario(atual == null ? "Novo pedido de compra"
                : "Dados do pedido nº " + atual.id(),
                "O pedido nasce como rascunho: inclua os itens e, depois, envie-o ao fornecedor (PDF).",
                atual == null ? "Criar" : "Salvar");
        ComboBox<Fornecedor> fornecedor = new ComboBox<>();
        fornecedor.getItems().setAll(fornecedores);
        fornecedor.setMaxWidth(Double.MAX_VALUE);
        fornecedor.setPromptText(fornecedores.isEmpty() ? "Cadastre um fornecedor antes" : "Escolha o fornecedor");
        DatePicker previsao = new DatePicker();
        previsao.setConverter(new ConversorData());
        previsao.setPromptText("dd/mm/aaaa");
        previsao.setMaxWidth(Double.MAX_VALUE);
        TextArea observacao = new TextArea();
        observacao.setPrefRowCount(3);
        observacao.setWrapText(true);
        observacao.setPromptText("Ex.: entregar pela manhã");
        if (atual != null) {
            fornecedores.stream().filter(f -> f.id() == atual.fornecedorId()).findFirst()
                    .ifPresent(fornecedor::setValue);
            previsao.setValue(atual.previsaoEntrega());
            observacao.setText(atual.observacao() == null ? "" : atual.observacao());
        }
        formulario.adicionarCampo("Fornecedor *", fornecedor);
        formulario.adicionarCampo("Previsão de entrega", previsao);
        formulario.adicionarCampo("Observação", observacao);
        return formulario.mostrar(() -> {
            previsao.setValue(previsao.getConverter().fromString(previsao.getEditor().getText()));
            if (!Textos.vazio(previsao.getEditor().getText()) && previsao.getValue() == null) {
                throw new NegocioException("Previsão de entrega inválida: use dd/mm/aaaa.");
            }
            Fornecedor escolhido = fornecedor.getValue();
            return gravar.apply(new DadosPedido(escolhido == null ? null : escolhido.id(), previsao.getValue(),
                    observacao.getText()));
        }, null);
    }

    /**
     * Escolha do pedido (ENVIADO ou PARCIAL, do fornecedor da entrada) que a entrada atende; "Nenhum" desvincula.
     *
     * @param atual  pedido já vinculado, nulo sem pedido
     * @param gravar pedido escolhido (nulo = nenhum); {@code NegocioException} mantém o diálogo aberto
     * @return true se gravou
     */
    public static boolean escolherPedido(String tituloEntrada, List<PedidoCompra> pedidos, Long atual,
                                         java.util.function.Consumer<Long> gravar) {
        DialogoFormulario formulario = new DialogoFormulario("Pedido de compra — " + tituloEntrada,
                pedidos.isEmpty() ? "O fornecedor desta entrada não tem pedido enviado ou parcial."
                        : "Escolha o pedido que esta nota atende. Ao confirmar a entrada, as quantidades entram como "
                        + "recebidas no pedido.", "Salvar");
        ComboBox<Object> escolha = new ComboBox<>();
        escolha.getItems().add(SEM_PEDIDO);
        escolha.getItems().addAll(pedidos.stream().map(p -> (Object) new OpcaoPedido(p)).toList());
        escolha.setMaxWidth(Double.MAX_VALUE);
        escolha.setValue(SEM_PEDIDO);
        for (Object o : escolha.getItems()) {
            if (o instanceof OpcaoPedido op && atual != null && op.pedido().id() == atual) {
                escolha.setValue(o);
            }
        }
        formulario.adicionarCampo("Pedido", escolha);
        return formulario.mostrar(() -> {
            gravar.accept(escolha.getValue() instanceof OpcaoPedido op ? op.pedido().id() : null);
            return Boolean.TRUE;
        }, null).isPresent();
    }

    /** Pedido na lista de escolha: "Nº 12 — enviado em 03/11/2026 — previsão 10/11/2026 — R$ 480,00". */
    public record OpcaoPedido(PedidoCompra pedido) {
        @Override
        public String toString() {
            return "Nº " + pedido.id() + " — " + pedido.status().rotulo().toLowerCase(Formatos.PT_BR)
                    + (pedido.enviadoEm() == null ? "" : " em " + Formatos.data(pedido.enviadoEm()
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()))
                    + (pedido.previsaoEntrega() == null ? "" : " — previsão " + Formatos.data(pedido.previsaoEntrega()))
                    + " — " + Formatos.moeda(pedido.valorPrevisto());
        }
    }

    /** Conferência pedido × nota (F16.3), só leitura. */
    public static void conferir(String tituloEntrada, ConferenciaPedido c) {
        Dialog<Void> dialogo = new Dialog<>();
        dialogo.setTitle("Conferência pedido × nota");
        dialogo.setHeaderText(c.pedido().titulo() + " × " + tituloEntrada);
        dialogo.getDialogPane().getButtonTypes().add(new ButtonType("Fechar", ButtonType.CLOSE.getButtonData()));
        Dialogos.estilizar(dialogo.getDialogPane());

        TableView<ConferenciaPedido.Linha> tabela = tabela(c);
        Label resumo = new Label(c.temDivergencia()
                ? "Em destaque, as linhas com quantidade ou custo diferente do pedido. A entrada pode ser confirmada "
                + "assim mesmo: o que vier a mais ou fora do pedido também entra no estoque."
                : "A nota confere com o que faltava receber do pedido, nas quantidades e nos custos.");
        resumo.setWrapText(true);
        resumo.setMinHeight(Region.USE_PREF_SIZE);
        resumo.getStyleClass().add("subtitulo");
        VBox conteudo = new VBox(8, tabela, resumo);
        conteudo.setPrefSize(1080, 380);
        dialogo.getDialogPane().setContent(conteudo);
        dialogo.setResizable(true);
        dialogo.showAndWait();
    }

    static TableView<ConferenciaPedido.Linha> tabela(ConferenciaPedido c) {
        TableView<ConferenciaPedido.Linha> tabela = new TableView<>();
        tabela.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tabela.getColumns().add(coluna("Produto", 200, ConferenciaPedido.Linha::produto, l -> false, false));
        tabela.getColumns().add(coluna("Pedida", 70, l -> qtd(l, l.pedida()), l -> false, true));
        tabela.getColumns().add(coluna("Recebida antes", 100, l -> l.foraDoPedido() ? "" : qtd(l, l.recebidaAntes()),
                l -> false, true));
        tabela.getColumns().add(coluna("Faltava", 70, l -> qtd(l, l.pendenteAntes()), l -> false, true));
        tabela.getColumns().add(coluna("Nesta nota", 80, l -> qtd(l, l.nestaNota()),
                ConferenciaPedido.Linha::quantidadeDivergente, true));
        tabela.getColumns().add(coluna("Falta depois", 85, l -> qtd(l, l.pendenteDepois()), l -> false, true));
        tabela.getColumns().add(coluna("Custo previsto", 100, l -> custo(l.custoPrevisto()), l -> false, true));
        tabela.getColumns().add(coluna("Custo da nota", 100, l -> custo(l.custoNota()),
                ConferenciaPedido.Linha::custoDivergente, true));
        tabela.getColumns().add(coluna("Situação", 230, l -> l.situacao()
                + (l.custoDivergente() ? " · custo diferente" : ""), l -> l.quantidadeDivergente()
                || l.custoDivergente(), false));
        tabela.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(ConferenciaPedido.Linha l, boolean vazio) {
                super.updateItem(l, vazio);
                getStyleClass().remove(LINHA_DIVERGENTE);
                if (!vazio && l != null && (l.quantidadeDivergente() || l.custoDivergente())) {
                    getStyleClass().add(LINHA_DIVERGENTE);
                }
            }
        });
        tabela.getItems().setAll(c.linhas());
        return tabela;
    }

    private static TableColumn<ConferenciaPedido.Linha, String> coluna(String titulo, double largura,
            Function<ConferenciaPedido.Linha, String> valor, Predicate<ConferenciaPedido.Linha> destaque,
            boolean numero) {
        TableColumn<ConferenciaPedido.Linha, String> coluna = new TableColumn<>(titulo);
        coluna.setPrefWidth(largura);
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
        if (numero) {
            coluna.getStyleClass().add("coluna-numero");
        }
        coluna.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String texto, boolean vazio) {
                super.updateItem(texto, vazio);
                setText(vazio ? null : texto);
                getStyleClass().remove(DIVERGENTE);
                ConferenciaPedido.Linha l = vazio || getTableRow() == null ? null : getTableRow().getItem();
                if (l != null && destaque.test(l)) {
                    getStyleClass().add(DIVERGENTE);
                }
            }
        });
        return coluna;
    }

    private static String qtd(ConferenciaPedido.Linha l, BigDecimal valor) {
        return valor == null ? "" : Formatos.quantidade(valor, l.fracionada());
    }

    private static String custo(BigDecimal valor) {
        return valor == null ? "" : "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }
}
