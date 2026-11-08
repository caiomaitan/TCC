package br.com.varesync.controller;

import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.ResultadoEntrada;
import br.com.varesync.model.SugestaoFiscal;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.SugestaoCompra;
import br.com.varesync.service.EntradaService;
import br.com.varesync.service.FiscalService;
import br.com.varesync.service.ImportacaoNfeService;
import br.com.varesync.service.LeitorNfe;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.PedidoCompraService;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.SugestaoCompraService;
import br.com.varesync.ui.BuscaProdutoDialogo;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.DialogoFiscalNota;
import br.com.varesync.ui.DialogoNfe;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCompras;
import br.com.varesync.ui.DialogosPedido;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.TableCell;
import javafx.scene.control.Tooltip;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Callback;

/**
 * Compras — entrada de mercadoria (6.10): lista de entradas, dados da nota, itens (por código de barras, código ou
 * busca; custo unitário ou valor total da linha), confirmar (com o aviso de divergência do total e, depois, a
 * sugestão de reajuste) e excluir a entrada aberta. Confirmada, fica somente leitura. Aba Sugestão de compra (F16.2):
 * por fornecedor principal ou categoria, calculada em {@link Task}, com a ruptura em destaque.
 */
public class ComprasController {

    static final String CUSTO_UNITARIO = "Custo unitário";
    static final String VALOR_DA_LINHA = "Valor total da linha";
    private static final String NEGATIVO = "negativo";
    private static final String DIVISAO_INCOMPLETA = "linha-aviso";
    private static final KeyCombination CTRL_N = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);

    private final EntradaService entradaService;
    private final Sessao sessao;
    private final Callback<Class<?>, Object> fabricaControllers;
    private final ImportacaoNfeService importacaoNfe;
    private final FiscalService fiscalService;
    private final SugestaoCompraService sugestaoService;
    private final PedidoCompraService pedidoService;
    /** Produtos marcados na sugestão para o "Gerar pedido" (F16.3). */
    private final Set<Long> marcados = new HashSet<>();
    /** Última sugestão calculada (todas as linhas do filtro); nula antes de calcular. */
    private SugestaoCompraService.Resultado sugestao;
    /** Entrada mostrada nos detalhes; nula sem seleção. */
    private EntradaResumo entrada;
    /** Produto escolhido para incluir; nulo quando se altera um item ou nada foi escolhido. */
    private ProdutoVenda produto;
    /** Item em alteração; nulo ao incluir. */
    private ItemEntrada itemEmEdicao;

    @FXML private VBox raiz;
    @FXML private TabPane abas;
    @FXML private Tab abaSugestao;
    @FXML private Tab abaPedidos;
    @FXML private VBox pedidos;
    @FXML private PedidosController pedidosController;
    @FXML private CheckBox sugestaoSoRuptura;
    @FXML private TableColumn<SugestaoCompra.Linha, Long> colunaSugMarcar;
    @FXML private Button botaoGerarPedido;
    @FXML private Button botaoPedido;
    @FXML private Button botaoConferir;
    @FXML private ComboBox<Opcao> sugestaoFornecedor;
    @FXML private ComboBox<Opcao> sugestaoCategoria;
    @FXML private CheckBox sugestaoSoComSugestao;
    @FXML private Button botaoCalcularSugestao;
    @FXML private ProgressIndicator progressoSugestao;
    @FXML private Label sugestaoParametros;
    @FXML private TableView<SugestaoCompra.Linha> tabelaSugestao;
    @FXML private Label sugestaoVazia;
    @FXML private TableColumn<SugestaoCompra.Linha, Long> colunaSugCodigo;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugProduto;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugFornecedor;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugSaldo;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugMedia;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugCobertura;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugPendente;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugSugestao;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugCompra;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugCusto;
    @FXML private TableColumn<SugestaoCompra.Linha, String> colunaSugValor;
    @FXML private Label sugestaoTotal;
    @FXML private Label sugestaoRupturas;
    @FXML private Label mensagemSugestao;
    @FXML private CheckBox filtroConfirmadas;
    @FXML private TableView<EntradaResumo> tabelaEntradas;
    @FXML private TableColumn<EntradaResumo, Long> colunaNumero;
    @FXML private TableColumn<EntradaResumo, String> colunaFornecedor;
    @FXML private TableColumn<EntradaResumo, String> colunaNota;
    @FXML private TableColumn<EntradaResumo, String> colunaEmissao;
    @FXML private TableColumn<EntradaResumo, String> colunaValorNota;
    @FXML private TableColumn<EntradaResumo, String> colunaSomaItens;
    @FXML private TableColumn<EntradaResumo, Integer> colunaItens;
    @FXML private TableColumn<EntradaResumo, String> colunaSituacao;
    @FXML private TableColumn<EntradaResumo, String> colunaConfirmada;
    @FXML private Label tituloEntrada;
    @FXML private Label situacao;
    @FXML private Button botaoEditarNota;
    @FXML private Button botaoParcelas;
    @FXML private Button botaoExcluir;
    @FXML private Button botaoConfirmar;
    @FXML private HBox painelItem;
    @FXML private TextField campoProduto;
    @FXML private Label produtoEscolhido;
    @FXML private TextField campoQuantidade;
    @FXML private ComboBox<String> modoValor;
    @FXML private TextField campoValor;
    @FXML private Button botaoGravarItem;
    @FXML private Button botaoRemover;
    @FXML private Button botaoLotes;
    @FXML private TableView<ItemEntrada> tabelaItens;
    @FXML private Label semItens;
    @FXML private TableColumn<ItemEntrada, Long> colunaItemCodigo;
    @FXML private TableColumn<ItemEntrada, String> colunaItemBarras;
    @FXML private TableColumn<ItemEntrada, String> colunaItemProduto;
    @FXML private TableColumn<ItemEntrada, String> colunaItemUnidade;
    @FXML private TableColumn<ItemEntrada, String> colunaItemQuantidade;
    @FXML private TableColumn<ItemEntrada, String> colunaItemCusto;
    @FXML private TableColumn<ItemEntrada, String> colunaItemValor;
    @FXML private TableColumn<ItemEntrada, String> colunaItemCustoAtual;
    @FXML private TableColumn<ItemEntrada, String> colunaItemPreco;
    @FXML private Label totalItens;
    @FXML private Label totalNota;
    @FXML private Label diferenca;
    @FXML private Label mensagem;

    public ComprasController(EntradaService entradaService, ImportacaoNfeService importacaoNfe,
                             SugestaoCompraService sugestaoService, PedidoCompraService pedidoService,
                             FiscalService fiscalService, Sessao sessao,
                             Callback<Class<?>, Object> fabricaControllers) {
        this.entradaService = entradaService;
        this.importacaoNfe = importacaoNfe;
        this.fiscalService = fiscalService;
        this.sugestaoService = sugestaoService;
        this.pedidoService = pedidoService;
        this.sessao = sessao;
        this.fabricaControllers = fabricaControllers;
    }

    @FXML
    private void initialize() {
        colunaNumero.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().id()));
        texto(colunaFornecedor, EntradaResumo::fornecedorNome);
        texto(colunaNota, e -> e.numeroDocumento() == null ? "" : e.numeroDocumento());
        texto(colunaEmissao, e -> e.dataEmissao() == null ? "" : Formatos.data(e.dataEmissao()));
        texto(colunaValorNota, e -> e.valorTotalNota() == null ? "" : Formatos.moeda(e.valorTotalNota()));
        texto(colunaSomaItens, e -> Formatos.moeda(e.somaItens()));
        colunaItens.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().totalItens()));
        texto(colunaSituacao, e -> e.status().rotulo());
        texto(colunaConfirmada, e -> e.confirmadoEm() == null ? ""
                : Formatos.dataHora(e.confirmadoEm()) + " — " + e.confirmadoPorNome());
        tabelaEntradas.getSelectionModel().selectedItemProperty().addListener((obs, anterior, selecionada) -> {
            if (selecionada == null || entrada == null || selecionada.id() != entrada.id()) {
                mostrar(selecionada);
            }
        });

        colunaItemCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        texto(colunaItemBarras, i -> i.codigoBarras() == null ? "" : i.codigoBarras());
        texto(colunaItemProduto, ItemEntrada::produtoNome);
        texto(colunaItemUnidade, ItemEntrada::unidade);
        texto(colunaItemQuantidade, i -> Formatos.quantidade(i.quantidade(), i.unidadeFracionada()));
        texto(colunaItemCusto, i -> custo(i.custoUnitario()));
        texto(colunaItemValor, i -> Formatos.moeda(i.valorTotal()));
        texto(colunaItemCustoAtual, i -> custo(i.custoAtual()));
        texto(colunaItemPreco, i -> Formatos.moeda(i.precoVenda()));
        tabelaItens.getSelectionModel().selectedItemProperty().addListener((obs, anterior, item) -> {
            if (item != null && entrada != null && entrada.aberta()) {
                editarItem(item);
            }
            botaoRemover.setDisable(item == null || entrada == null || !entrada.aberta());
        });
        tabelaItens.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(ItemEntrada item, boolean vazio) {
                super.updateItem(item, vazio);
                getStyleClass().remove(DIVISAO_INCOMPLETA);
                setTooltip(null);
                if (!vazio && item != null && item.divisaoIncompleta() && entrada != null && entrada.aberta()) {
                    getStyleClass().add(DIVISAO_INCOMPLETA);
                    setTooltip(new Tooltip(textoFaltaDividir(item)));
                }
            }
        });
        texto(colunaItemProduto, i -> i.produtoNome() + (i.divisaoIncompleta() && entrada != null && entrada.aberta()
                ? " — lotes: " + textoFaltaDividir(i) : ""));
        tabelaItens.getSelectionModel().selectedItemProperty().addListener((obs, anterior, item) ->
                atualizarBotaoLotes(item));

        modoValor.getItems().setAll(CUSTO_UNITARIO, VALOR_DA_LINHA);
        modoValor.setValue(CUSTO_UNITARIO);
        modoValor.valueProperty().addListener((obs, antes, modo) -> campoValor.setPromptText(
                VALOR_DA_LINHA.equals(modo) ? "0,00" : "0,0000"));
        campoValor.setPromptText("0,0000");
        campoQuantidade.setPromptText("0");

        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (abas.getSelectionModel().getSelectedItem() == abaSugestao) {
                if (e.getCode() == KeyCode.F5) {
                    e.consume();
                    calcularSugestao();
                }
            } else if (abas.getSelectionModel().getSelectedItem() != abaPedidos && CTRL_N.match(e)) {
                e.consume();
                nova();
            }
        });
        prepararSugestao();
        mostrar(null);
        pesquisar();
        Platform.runLater(() -> tabelaEntradas.requestFocus());
    }

    private static <T> void texto(TableColumn<T, String> coluna, Function<T, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    // ----- Sugestão de compra (F16.2) -----

    static final Opcao TODOS = new Opcao(0, "Todos");
    static final Opcao TODAS = new Opcao(0, "Todas");

    private void prepararSugestao() {
        sugestaoFornecedor.getItems().add(TODOS);
        sugestaoFornecedor.getItems().addAll(sugestaoService.fornecedores(sessao));
        sugestaoFornecedor.setValue(TODOS);
        sugestaoCategoria.getItems().add(TODAS);
        sugestaoCategoria.getItems().addAll(sugestaoService.categorias(sessao));
        sugestaoCategoria.setValue(TODAS);

        colunaSugCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        texto(colunaSugProduto, SugestaoCompra.Linha::produto);
        colunaSugMarcar.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        colunaSugMarcar.setCellFactory(c -> new TableCell<>() {
            private final CheckBox marca = new CheckBox();

            {
                marca.setOnAction(e -> {
                    SugestaoCompra.Linha l = getTableRow() == null ? null : getTableRow().getItem();
                    if (l != null) {
                        marcar(l.produtoId(), marca.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(Long produtoId, boolean vazio) {
                super.updateItem(produtoId, vazio);
                setText(null);
                if (vazio || produtoId == null) {
                    setGraphic(null);
                } else {
                    marca.setSelected(marcados.contains(produtoId));
                    setGraphic(marca);
                }
            }
        });
        texto(colunaSugFornecedor, l -> l.fornecedor() != null ? l.fornecedor()
                : l.aviso() != null ? "(principal inativo)" : "(sem principal)");
        texto(colunaSugSaldo, l -> Formatos.quantidade(l.saldo(), l.fracionada()) + " " + l.unidade());
        texto(colunaSugMedia, l -> Formatos.quantidade(l.mediaDia(), true));
        texto(colunaSugCobertura, l -> l.coberturaDias() == null ? Formatos.SEM_VALOR
                : Formatos.numero(BigDecimal.valueOf(l.coberturaDias()), 0));
        texto(colunaSugPendente, l -> Formatos.quantidade(l.pendente(), l.fracionada()));
        texto(colunaSugSugestao, l -> Formatos.quantidade(l.sugestao(), l.fracionada()));
        texto(colunaSugCompra, l -> l.fornecedorId() == null || l.sugestao().signum() == 0 ? ""
                : Formatos.quantidade(l.sugestaoNaUnidadeDeCompra(), l.sugestaoNaUnidadeDeCompra()
                .stripTrailingZeros().scale() > 0) + " " + (l.unidadeCompra() == null ? "un. de compra"
                : l.unidadeCompra()));
        texto(colunaSugCusto, l -> custo(l.ultimoCusto()));
        texto(colunaSugValor, l -> Formatos.moeda(l.valorEstimado()));
        tabelaSugestao.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(SugestaoCompra.Linha l, boolean vazio) {
                super.updateItem(l, vazio);
                getStyleClass().removeAll("linha-erro", "linha-aviso");
                setTooltip(null);
                if (!vazio && l != null && l.ruptura()) {
                    getStyleClass().add("linha-erro");
                } else if (!vazio && l != null && l.aviso() != null) {
                    getStyleClass().add("linha-aviso");
                }
                if (!vazio && l != null && l.aviso() != null) {
                    setTooltip(new Tooltip(l.aviso()));
                }
            }
        });
        botaoCalcularSugestao.disableProperty().bind(progressoSugestao.visibleProperty());
        botaoGerarPedido.disableProperty().bind(progressoSugestao.visibleProperty());
    }

    private void marcar(long produtoId, boolean marcado) {
        if (marcado) {
            marcados.add(produtoId);
        } else {
            marcados.remove(produtoId);
        }
        mostrarMarcados();
    }

    private void mostrarMarcados() {
        botaoGerarPedido.setText(marcados.isEmpty() ? "Gerar pedido…" : "Gerar pedido (" + marcados.size() + ")…");
    }

    /** Marca as linhas visíveis com sugestão maior que zero. */
    @FXML
    private void marcarComSugestao() {
        tabelaSugestao.getItems().stream().filter(l -> l.sugestao().signum() > 0)
                .forEach(l -> marcados.add(l.produtoId()));
        tabelaSugestao.refresh();
        mostrarMarcados();
    }

    @FXML
    private void desmarcarSugestao() {
        marcados.clear();
        tabelaSugestao.refresh();
        mostrarMarcados();
    }

    /**
     * "Gerar pedido" (F16.3): um rascunho por fornecedor principal das linhas marcadas, com a sugestão e o último custo;
     * as linhas sem fornecedor principal ativo ficam de fora, com aviso. Depois mostra os pedidos na aba Pedidos.
     */
    @FXML
    private void gerarPedido() {
        if (marcados.isEmpty()) {
            Mensagem.erro(mensagemSugestao, "Marque na coluna Pedir os produtos que vão para o pedido (ou use "
                    + "\"Marcar os com sugestão\").");
            return;
        }
        if (!Dialogos.confirmar("Gerar pedidos de compra com os " + marcados.size() + " produto(s) marcado(s)?\n\n"
                + "Sai um rascunho para cada fornecedor principal, com a quantidade da sugestão e o último custo. "
                + "Produto sem fornecedor principal ativo fica de fora.")) {
            return;
        }
        try {
            PedidoCompraService.Geracao g = pedidoService.gerarDaSugestao(sessao, marcados);
            marcados.clear();
            mostrarMarcados();
            tabelaSugestao.refresh();
            String aviso = g.avisos().isEmpty() ? "" : "Ficaram de fora: " + String.join(" ", g.avisos());
            abas.getSelectionModel().select(abaPedidos);
            pedidosController.mostrarCriados(g.pedidos(), aviso);
        } catch (NegocioException e) {
            Mensagem.erro(mensagemSugestao, e.getMessage());
        }
    }

    /** Alerta de ruptura do painel (F16.4): aba Sugestão de compra com todos os produtos em ruptura. */
    public void mostrarRupturas() {
        abas.getSelectionModel().select(abaSugestao);
        sugestaoFornecedor.setValue(TODOS);
        sugestaoCategoria.setValue(TODAS);
        sugestaoSoComSugestao.setSelected(false);
        sugestaoSoRuptura.setSelected(true);
        carregarSugestao();
    }

    @FXML
    private void calcularSugestao() {
        carregarSugestao();
    }

    /** Calcula em {@link Task}; "Calcular" fica desabilitado enquanto isso. */
    Task<SugestaoCompraService.Resultado> carregarSugestao() {
        Long fornecedorId = idOuTodos(sugestaoFornecedor.getValue());
        Long categoriaId = idOuTodos(sugestaoCategoria.getValue());
        Task<SugestaoCompraService.Resultado> tarefa = new Task<>() {
            @Override
            protected SugestaoCompraService.Resultado call() {
                return sugestaoService.consultar(sessao, fornecedorId, categoriaId);
            }
        };
        progressoSugestao.setVisible(true);
        Mensagem.limpar(mensagemSugestao);
        tarefa.setOnSucceeded(e -> {
            progressoSugestao.setVisible(false);
            sugestao = tarefa.getValue();
            filtrarSugestao();
        });
        tarefa.setOnFailed(e -> {
            progressoSugestao.setVisible(false);
            Mensagem.erro(mensagemSugestao, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        return Tarefas.executar("sugestao-compra", tarefa);
    }

    /** Mostra a última sugestão calculada com o filtro "Só com sugestão". */
    @FXML
    private void filtrarSugestao() {
        if (sugestao == null) {
            return;
        }
        List<SugestaoCompra.Linha> linhas = sugestao.linhas().stream()
                .filter(l -> !sugestaoSoComSugestao.isSelected() || l.sugestao().signum() > 0)
                .filter(l -> !sugestaoSoRuptura.isSelected() || l.ruptura()).toList();
        tabelaSugestao.getItems().setAll(linhas);
        sugestaoVazia.setText(sugestaoSoRuptura.isSelected() ? "Nenhum produto em ruptura com estes filtros."
                : sugestaoSoComSugestao.isSelected()
                ? "Nenhum produto precisa de compra com estes filtros." : "Nenhum produto ativo com estes filtros.");
        sugestaoParametros.setText("Média da venda líquida de devoluções dos últimos "
                + sugestao.parametros().diasMedia() + " dias (hoje incluído) · cobertura de "
                + sugestao.parametros().coberturaDias() + " dias + o prazo de entrega do fornecedor principal · "
                + "menos o saldo e o pendente dos pedidos enviados e parciais · arredondada para cima no fator.");
        BigDecimal total = linhas.stream().map(SugestaoCompra.Linha::valorEstimado)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long comSugestao = linhas.stream().filter(l -> l.sugestao().signum() > 0).count();
        sugestaoTotal.setText(comSugestao + (comSugestao == 1 ? " produto" : " produtos") + " a comprar · valor "
                + "estimado " + Formatos.moeda(total));
        long rupturas = linhas.stream().filter(SugestaoCompra.Linha::ruptura).count();
        sugestaoRupturas.setText(rupturas == 0 ? "" : rupturas + (rupturas == 1 ? " produto" : " produtos")
                + " em ruptura (em vermelho)");
    }

    private static Long idOuTodos(Opcao opcao) {
        return opcao == null || opcao.id() == 0 ? null : opcao.id();
    }

    // ----- Lista e nota -----

    @FXML
    private void pesquisar() {
        Long selecionada = entrada == null ? null : entrada.id();
        tabelaEntradas.getItems().setAll(entradaService.listar(sessao, filtroConfirmadas.isSelected()));
        selecionar(selecionada);
    }

    /** Seleciona a entrada {@code id} (recarregando os detalhes) ou a primeira da lista. */
    private void selecionar(Long id) {
        Optional<EntradaResumo> mesma = id == null ? Optional.empty()
                : tabelaEntradas.getItems().stream().filter(e -> e.id() == id).findFirst();
        entrada = null; // força recarregar os detalhes
        if (mesma.isPresent()) {
            tabelaEntradas.getSelectionModel().select(mesma.get());
        } else if (!tabelaEntradas.getItems().isEmpty()) {
            tabelaEntradas.getSelectionModel().selectFirst();
        } else {
            tabelaEntradas.getSelectionModel().clearSelection();
            mostrar(null);
        }
    }

    @FXML
    private void nova() {
        DialogosCompras.dadosNota(entradaService.fornecedores(sessao, null), null,
                dados -> entradaService.criar(sessao, dados)).ifPresent(id -> {
                    tabelaEntradas.getItems().setAll(entradaService.listar(sessao, filtroConfirmadas.isSelected()));
                    selecionar(id);
                    Mensagem.sucesso(mensagem, "Entrada nº " + id + " criada. Inclua os itens da nota.");
                    Platform.runLater(campoProduto::requestFocus);
                });
    }

    /**
     * Importação do XML da NF-e (6.11): escolhe o arquivo, mostra a conferência (fornecedor, itens e vínculo com os
     * produtos) e gera uma entrada aberta, que fica selecionada para conferir e confirmar.
     */
    @FXML
    private void importarNfe() {
        FileChooser escolha = new FileChooser();
        escolha.setTitle("XML da NF-e");
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML da NF-e", "*.xml"));
        File arquivo = escolha.showOpenDialog(tabelaEntradas.getScene().getWindow());
        if (arquivo != null) {
            importarNfe(arquivo.toPath());
        }
    }

    private void importarNfe(Path arquivo) {
        byte[] xml;
        try {
            xml = Files.readAllBytes(arquivo);
        } catch (IOException e) {
            Mensagem.erro(mensagem, "Não foi possível ler o arquivo " + arquivo.getFileName() + ".");
            return;
        }
        ImportacaoNfeService.Previa previa;
        try {
            previa = importacaoNfe.ler(sessao, xml);
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            return;
        }
        Map<Integer, Long> escolhidosNaConferencia = new LinkedHashMap<>();
        DialogoNfe.conferir(previa, new DialogoNfe.Acoes(
                termo -> entradaService.pesquisarProdutos(sessao, termo),
                id -> entradaService.produtoPorCodigo(sessao, String.valueOf(id)),
                sessao.pode(Permissao.PRODUTOS) ? this::cadastrarProduto : null,
                (escolhidos, fatores) -> {
                    long id = importacaoNfe.gerarEntrada(sessao, xml, escolhidos, fatores);
                    escolhidosNaConferencia.putAll(escolhidos);
                    return id;
                }))
                .ifPresent(id -> {
                    tabelaEntradas.getItems().setAll(entradaService.listar(sessao, filtroConfirmadas.isSelected()));
                    selecionar(id);
                    String fiscal = preencherDadosFiscais(xml, escolhidosNaConferencia, previa.nota().numero());
                    Mensagem.sucesso(mensagem, "Entrada nº " + id + " gerada pelo XML da NF-e. Confira os itens e "
                            + "confirme a entrada." + fiscal);
                });
    }

    /**
     * F19.3: depois de gerar a entrada, quem tem FISCAL confirma o NCM, o CEST e a origem da nota nos produtos com
     * esses campos vazios. Falha aqui não desfaz a entrada (já gravada): só vira mensagem.
     *
     * @return complemento da mensagem de sucesso ("" se nada foi preenchido)
     */
    private String preencherDadosFiscais(byte[] xml, Map<Integer, Long> escolhidos, String numeroNota) {
        if (!sessao.pode(Permissao.FISCAL)) {
            return "";
        }
        try {
            List<SugestaoFiscal> sugestoes = fiscalService.sugestoesDaNota(sessao, xml, escolhidos);
            if (sugestoes.isEmpty()) {
                return "";
            }
            List<SugestaoFiscal> confirmadas = DialogoFiscalNota.confirmar(tabelaEntradas.getScene().getWindow(),
                    sugestoes).orElse(List.of());
            if (confirmadas.isEmpty()) {
                return " Os dados fiscais da nota não foram usados.";
            }
            int alterados = fiscalService.preencherDaNota(sessao, confirmadas, numeroNota);
            return alterados == 0 ? "" : " Dados fiscais preenchidos em " + (alterados == 1 ? "1 produto"
                    : alterados + " produtos") + ".";
        } catch (NegocioException e) {
            return " Os dados fiscais não foram preenchidos: " + e.getMessage();
        }
    }

    /** Cadastro de produto preenchido com o item da nota; devolve o código do produto se ele foi salvo. */
    private Optional<Long> cadastrarProduto(LeitorNfe.Item item) {
        ProdutoController produto = Janelas.abrirModal(tabelaEntradas.getScene().getWindow(), "/fxml/produto.fxml",
                "Novo produto", fabricaControllers, (ProdutoController c) -> c.preencherNovo(item.descricao(),
                        item.codigoBarras(), DialogoNfe.custoUnitario(item)));
        return produto.produtoId();
    }

    @FXML
    private void editarNota() {
        if (entrada == null || !entrada.aberta()) {
            return;
        }
        long id = entrada.id();
        DialogosCompras.dadosNota(entradaService.fornecedores(sessao, entrada.fornecedorId()), entrada, dados -> {
            entradaService.alterarDados(sessao, id, dados);
            return id;
        }).ifPresent(gravada -> {
            pesquisar();
            Mensagem.sucesso(mensagem, "Dados da nota salvos.");
        });
    }

    /** Vincula a entrada aberta a um pedido enviado ou parcial do mesmo fornecedor (F16.3), ou desvincula. */
    @FXML
    private void escolherPedido() {
        if (entrada == null || !entrada.aberta()) {
            return;
        }
        EntradaResumo atual = entrada;
        try {
            List<PedidoCompra> abertos = entradaService.pedidosParaVincular(sessao, atual.id());
            if (DialogosPedido.escolherPedido(atual.titulo(), abertos, atual.pedidoId(),
                    pedidoId -> entradaService.vincularPedido(sessao, atual.id(), pedidoId))) {
                pesquisar();
                EntradaResumo gravada = entradaService.buscar(sessao, atual.id());
                Mensagem.sucesso(mensagem, gravada.pedidoId() == null ? "Entrada sem pedido."
                        : "Entrada vinculada ao pedido nº " + gravada.pedidoId() + ". Confira com o pedido antes de "
                        + "confirmar.");
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /** Conferência pedido × nota (F16.3), com as divergências em destaque. */
    @FXML
    private void conferirPedido() {
        if (entrada == null || entrada.pedidoId() == null) {
            return;
        }
        try {
            DialogosPedido.conferir(entrada.titulo(), entradaService.conferencia(sessao, entrada.id()));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /** Parcelas da nota (F15.3): ao confirmar a entrada, cada uma vira um título a pagar. */
    @FXML
    private void editarParcelas() {
        if (entrada == null || !entrada.aberta()) {
            return;
        }
        EntradaResumo atual = entrada;
        if (DialogosCompras.parcelas(atual.titulo(), entradaService.parcelas(sessao, atual.id()),
                parcelas -> entradaService.salvarParcelas(sessao, atual.id(), parcelas))) {
            Optional<String> aviso = EntradaService.avisoParcelas(atual, entradaService.parcelas(sessao, atual.id()));
            if (aviso.isPresent()) {
                Mensagem.erro(mensagem, aviso.get());
            } else {
                Mensagem.sucesso(mensagem, "Parcelas salvas.");
            }
        }
    }

    @FXML
    private void excluir() {
        if (entrada == null || !entrada.aberta()) {
            return;
        }
        long id = entrada.id();
        if (!Dialogos.confirmar("Excluir a entrada nº " + id + " (" + entrada.fornecedorNome() + ") com "
                + itens(entrada.totalItens()) + "? O estoque não foi alterado por ela.")) {
            return;
        }
        try {
            entradaService.excluir(sessao, id);
            entrada = null;
            pesquisar();
            Mensagem.sucesso(mensagem, "Entrada nº " + id + " excluída.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            pesquisar();
        }
    }

    /** Preenche o cabeçalho, os itens e os totais (nula = sem seleção). */
    private void mostrar(EntradaResumo selecionada) {
        entrada = selecionada;
        Mensagem.limpar(mensagem);
        limparItem();
        boolean aberta = selecionada != null && selecionada.aberta();
        painelItem.setDisable(!aberta);
        // Confirmada (ou sem seleção), os botões somem: desabilitados, cortavam o título da entrada.
        for (Button botao : List.of(botaoEditarNota, botaoPedido, botaoParcelas, botaoExcluir, botaoConfirmar)) {
            botao.setVisible(aberta);
            botao.setManaged(aberta);
        }
        boolean comPedido = selecionada != null && selecionada.pedidoId() != null;
        botaoConferir.setVisible(comPedido);
        botaoConferir.setManaged(comPedido);
        botaoRemover.setDisable(true);
        atualizarBotaoLotes(null);
        if (selecionada == null) {
            tituloEntrada.setText("Selecione uma entrada");
            situacao.setText("");
            tabelaItens.getItems().clear();
            semItens.setText("Selecione ou crie uma entrada.");
            totalItens.setText("");
            totalNota.setText("");
            diferenca.setText("");
            return;
        }
        tituloEntrada.setText(selecionada.titulo() + (comPedido ? " · pedido nº " + selecionada.pedidoId() : ""));
        situacao.setText(aberta ? "Aberta — lançada por " + selecionada.criadoPorNome() + " em "
                + Formatos.dataHora(selecionada.criadoEm())
                : "Confirmada por " + selecionada.confirmadoPorNome() + " em "
                + Formatos.dataHora(selecionada.confirmadoEm()) + " — somente leitura");
        semItens.setText(aberta ? "Nenhum item. Leia o código de barras ou digite o código ou o nome do produto."
                : "Nenhum item nesta entrada.");
        tabelaItens.getItems().setAll(entradaService.itens(sessao, selecionada.id()));
        mostrarTotais(selecionada);
    }

    private void mostrarTotais(EntradaResumo e) {
        totalItens.setText("Soma dos itens: " + Formatos.moeda(e.somaItens()) + " (" + itens(e.totalItens()) + ")");
        totalNota.setText(e.valorTotalNota() == null ? "Valor da nota não informado"
                : "Valor da nota: " + Formatos.moeda(e.valorTotalNota()));
        diferenca.getStyleClass().remove(NEGATIVO);
        Optional<BigDecimal> d = e.diferencaTotal();
        if (d.isEmpty()) {
            diferenca.setText("");
        } else if (d.get().signum() == 0) {
            diferenca.setText("Soma confere com a nota");
        } else {
            diferenca.setText("Diferença: " + Formatos.moeda(d.get()));
            diferenca.getStyleClass().add(NEGATIVO);
        }
    }

    /** Recarrega a linha da entrada (itens, soma) sem trocar a seleção. */
    private void atualizarEntrada(Long manterItemId) {
        EntradaResumo atual = entradaService.buscar(sessao, entrada.id());
        int indice = tabelaEntradas.getItems().indexOf(entrada);
        entrada = atual;
        if (indice >= 0) {
            tabelaEntradas.getItems().set(indice, atual);
            tabelaEntradas.getSelectionModel().select(indice);
        }
        tabelaItens.getItems().setAll(entradaService.itens(sessao, atual.id()));
        mostrarTotais(atual);
        if (manterItemId != null) {
            tabelaItens.getItems().stream().filter(i -> i.id() == manterItemId).findFirst()
                    .ifPresent(i -> tabelaItens.scrollTo(i));
        }
    }

    // ----- Itens -----

    /** Só dígitos → código de barras ou código interno; texto (ou vazio) → busca de produto. */
    @FXML
    private void escolherProduto() {
        if (entrada == null || !entrada.aberta()) {
            return;
        }
        String texto = Textos.normalizarEspacos(campoProduto.getText());
        try {
            if (!texto.isEmpty() && Textos.soDigitos(texto)) {
                escolher(entradaService.produtoPorCodigo(sessao, texto));
                return;
            }
            Optional<ProdutoResumo> escolhido = BuscaProdutoDialogo.buscar("Buscar produto", "Escolher", true, texto,
                    termo -> entradaService.pesquisarProdutos(sessao, termo));
            escolhido.ifPresent(p -> escolher(entradaService.produtoPorCodigo(sessao, String.valueOf(p.id()))));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            campoProduto.selectAll();
        }
    }

    private void escolher(ProdutoVenda escolhido) {
        itemEmEdicao = null;
        tabelaItens.getSelectionModel().clearSelection();
        produto = escolhido;
        campoProduto.setText(escolhido.codigo());
        produtoEscolhido.setText(escolhido.nome() + " (" + escolhido.unidade() + ") — custo médio atual "
                + custo(escolhido.custo()));
        botaoGravarItem.setText("Incluir");
        Mensagem.limpar(mensagem);
        campoQuantidade.requestFocus();
    }

    private void editarItem(ItemEntrada item) {
        itemEmEdicao = item;
        produto = null;
        campoProduto.setText(item.codigoBarras() == null ? String.valueOf(item.produtoId()) : item.codigoBarras());
        campoProduto.setDisable(true);
        produtoEscolhido.setText(item.produtoNome() + " (" + item.unidade() + ") — alterando o item");
        // sem separador de milhar: "1.234" seria relido como 1,234 (decisão da Fase 2)
        campoQuantidade.setText(item.quantidade().stripTrailingZeros().toPlainString().replace('.', ','));
        modoValor.setValue(CUSTO_UNITARIO);
        campoValor.setText(Formatos.numero(item.custoUnitario(), Dinheiro.ESCALA_CUSTO));
        botaoGravarItem.setText("Salvar item");
    }

    @FXML
    private void focarValor() {
        campoValor.requestFocus();
        campoValor.selectAll();
    }

    @FXML
    private void gravarItem() {
        if (entrada == null || !entrada.aberta()) {
            return;
        }
        try {
            BigDecimal quantidade = CampoNumero.ler(campoQuantidade.getText(), "Quantidade");
            BigDecimal valor = CampoNumero.ler(campoValor.getText(), modoValor.getValue());
            BigDecimal custoUnitario = VALOR_DA_LINHA.equals(modoValor.getValue()) ? null : valor;
            BigDecimal valorLinha = VALOR_DA_LINHA.equals(modoValor.getValue()) ? valor : null;
            if (custoUnitario == null && valorLinha == null) {
                throw new NegocioException("Informe o " + modoValor.getValue().toLowerCase(Formatos.PT_BR) + ".");
            }
            long itemId;
            String nome;
            if (itemEmEdicao != null) {
                itemId = itemEmEdicao.id();
                nome = itemEmEdicao.produtoNome();
                entradaService.alterarItem(sessao, entrada.id(), itemId, quantidade, custoUnitario, valorLinha);
            } else if (produto != null) {
                nome = produto.nome();
                itemId = entradaService.adicionarItem(sessao, entrada.id(), produto.id(), quantidade, custoUnitario,
                        valorLinha);
            } else {
                throw new NegocioException("Escolha o produto: leia o código de barras ou digite o código ou o nome.");
            }
            String feito = nome + (itemEmEdicao == null ? " incluído." : " alterado.");
            atualizarEntrada(itemId);
            limparItem();
            Mensagem.sucesso(mensagem, feito);
            campoProduto.requestFocus();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void limparItem() {
        produto = null;
        itemEmEdicao = null;
        campoProduto.clear();
        campoProduto.setDisable(false);
        campoQuantidade.clear();
        campoValor.clear();
        produtoEscolhido.setText("Nenhum produto");
        botaoGravarItem.setText("Incluir");
        tabelaItens.getSelectionModel().clearSelection();
    }

    @FXML
    private void removerItem() {
        ItemEntrada item = tabelaItens.getSelectionModel().getSelectedItem();
        if (entrada == null || !entrada.aberta() || item == null) {
            return;
        }
        if (!Dialogos.confirmar("Remover " + item.produtoNome() + " da entrada nº " + entrada.id() + "?")) {
            return;
        }
        try {
            entradaService.removerItem(sessao, entrada.id(), item.id());
            limparItem();
            atualizarEntrada(null);
            Mensagem.sucesso(mensagem, item.produtoNome() + " removido.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- Divisão em lotes (F17.3) -----

    private void atualizarBotaoLotes(ItemEntrada item) {
        boolean pode = item != null && item.controlaValidade() && entrada != null && entrada.aberta();
        botaoLotes.setDisable(!pode);
    }

    /** "faltam 6 UN" ou "passou 2 UN". */
    private static String textoFaltaDividir(ItemEntrada item) {
        BigDecimal falta = item.faltaDividir();
        return (falta.signum() > 0 ? "faltam " : "passou ")
                + Formatos.quantidade(falta.abs(), item.unidadeFracionada()) + " " + item.unidade();
    }

    /** "Lotes…": divisão do item selecionado em lotes (código, validade e quantidade). */
    @FXML
    private void dividirEmLotes() {
        ItemEntrada item = itemEmEdicao != null ? itemEmEdicao : tabelaItens.getSelectionModel().getSelectedItem();
        if (entrada == null || !entrada.aberta() || item == null || !item.controlaValidade()) {
            return;
        }
        long entradaId = entrada.id();
        try {
            boolean gravou = DialogosCompras.lotes(item, entradaService.lotesDoItem(sessao, entradaId, item.id()),
                    lotes -> entradaService.salvarLotesDoItem(sessao, entradaId, item.id(), lotes));
            if (gravou) {
                limparItem();
                atualizarEntrada(item.id());
                ItemEntrada atual = tabelaItens.getItems().stream().filter(i -> i.id() == item.id()).findFirst()
                        .orElse(item);
                Mensagem.sucesso(mensagem, "Lotes de " + item.produtoNome() + " gravados"
                        + (atual.divisaoIncompleta() ? ": " + textoFaltaDividir(atual) + " para completar a divisão."
                        : ": divisão completa."));
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- Confirmar -----

    /** Confirmação com o resumo e, se houver, o aviso de que a soma dos itens difere do valor da nota. */
    @FXML
    private void confirmar() {
        if (entrada == null || !entrada.aberta()) {
            return;
        }
        EntradaResumo e = entradaService.buscar(sessao, entrada.id());
        if (e.totalItens() == 0) {
            Mensagem.erro(mensagem, "A entrada não tem itens. Inclua os produtos da nota antes de confirmar.");
            return;
        }
        // F17.3: item de produto com validade precisa da divisão em lotes somando a quantidade.
        List<ItemEntrada> incompletos = entradaService.itens(sessao, e.id()).stream()
                .filter(ItemEntrada::divisaoIncompleta).toList();
        if (!incompletos.isEmpty()) {
            Mensagem.erro(mensagem, "Falta dividir em lotes (botão \"Lotes…\"): " + incompletos.stream()
                    .map(i -> i.produtoNome() + " (" + textoFaltaDividir(i) + ")")
                    .collect(java.util.stream.Collectors.joining("; ")) + ".");
            return;
        }
        StringBuilder texto = new StringBuilder("Confirmar a entrada nº ").append(e.id()).append(" (")
                .append(e.fornecedorNome()).append(e.numeroDocumento() == null ? "" : ", NF " + e.numeroDocumento())
                .append(")?\n\n").append(itens(e.totalItens())).append(", ").append(Formatos.moeda(e.somaItens()))
                .append(".\n");
        e.avisoTotal().ifPresent(aviso -> texto.append("\nATENÇÃO: ").append(aviso).append('\n'));
        List<ParcelaEntrada> parcelas = entradaService.parcelas(sessao, e.id());
        EntradaService.avisoParcelas(e, parcelas).ifPresent(aviso -> texto.append("\nATENÇÃO: ").append(aviso)
                .append('\n'));
        if (!parcelas.isEmpty()) {
            texto.append('\n').append(parcelas.size()).append(" parcela(s) vão virar contas a pagar.\n");
        }
        if (e.pedidoId() != null) {
            texto.append("\nAs quantidades entram como recebidas no pedido nº ").append(e.pedidoId()).append(".\n");
            if (entradaService.conferencia(sessao, e.id()).temDivergencia()) {
                texto.append("ATENÇÃO: a nota tem quantidade ou custo diferente do pedido (veja \"Conferir com o "
                        + "pedido…\").\n");
            }
        }
        texto.append("\nO estoque e o custo médio dos produtos serão atualizados. Depois de confirmada, a entrada não "
                + "pode mais ser alterada (correções só por ajuste de estoque).");
        if (!Dialogos.confirmar(texto.toString())) {
            return;
        }
        try {
            ResultadoEntrada r = entradaService.confirmar(sessao, e.id());
            filtroConfirmadas.setSelected(true); // a confirmada continua à vista
            tabelaEntradas.getItems().setAll(entradaService.listar(sessao, true));
            selecionar(e.id());
            Mensagem.sucesso(mensagem, "Entrada nº " + e.id() + " confirmada: estoque e custo médio de "
                    + itens(r.custos().size()) + " atualizados" + (r.sugestoes().isEmpty() ? "."
                    : "; " + r.sugestoes().size() + " abaixo da margem mínima."));
            DialogosCompras.resultado(r, sessao.pode(Permissao.PRODUTOS) ? this::abrirProduto : null);
        } catch (NegocioException ex) {
            Mensagem.erro(mensagem, ex.getMessage());
            pesquisar();
        }
    }

    /** Atalho da sugestão de reajuste: o cadastro do produto (simulador de preço) numa janela própria. */
    private void abrirProduto(long produtoId) {
        Janelas.abrirModal(raiz.getScene().getWindow(), "/fxml/produto.fxml", "Produto " + produtoId,
                fabricaControllers, (ProdutoController c) -> c.carregar(produtoId));
    }

    // ----- Formatação -----

    private static String itens(long quantidade) {
        return quantidade == 1 ? "1 item" : quantidade + " itens";
    }

    private static String custo(BigDecimal valor) {
        return valor == null ? Formatos.SEM_VALOR : "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }
}
