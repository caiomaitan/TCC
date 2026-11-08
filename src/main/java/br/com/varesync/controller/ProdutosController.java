package br.com.varesync.controller;

import br.com.varesync.model.Categoria;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.SituacaoProduto;
import br.com.varesync.service.CategoriaService;
import br.com.varesync.service.ProdutoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Janelas;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.util.Callback;

/**
 * Busca de produtos (6.3, Fig. 21 revisada): texto ou código, filtros de categoria e situação.
 * Duplo clique (ou Enter) abre o cadastro do produto numa janela própria.
 */
public class ProdutosController {

    /** Item "Todas" do filtro de categoria. */
    private static final Categoria TODAS = new Categoria(0, "Todas", true);

    private final ProdutoService produtoService;
    private final CategoriaService categoriaService;
    private final Sessao sessao;
    private final Callback<Class<?>, Object> fabricaControllers;

    @FXML private TextField campoBusca;
    @FXML private ComboBox<Categoria> filtroCategoria;
    @FXML private ComboBox<SituacaoProduto> filtroSituacao;
    @FXML private TableView<ProdutoResumo> tabela;
    @FXML private TableColumn<ProdutoResumo, Long> colunaCodigo;
    @FXML private TableColumn<ProdutoResumo, String> colunaNome;
    @FXML private TableColumn<ProdutoResumo, String> colunaCategoria;
    @FXML private TableColumn<ProdutoResumo, BigDecimal> colunaPreco;
    @FXML private TableColumn<ProdutoResumo, ProdutoResumo> colunaEstoque;
    @FXML private TableColumn<ProdutoResumo, String> colunaAlteracao;
    @FXML private Label resumo;
    @FXML private Button botaoFiscal;

    public ProdutosController(ProdutoService produtoService, CategoriaService categoriaService, Sessao sessao,
                              Callback<Class<?>, Object> fabricaControllers) {
        this.produtoService = produtoService;
        this.categoriaService = categoriaService;
        this.sessao = sessao;
        this.fabricaControllers = fabricaControllers;
    }

    @FXML
    private void initialize() {
        colunaCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().id()));
        texto(colunaNome, ProdutoResumo::nome);
        texto(colunaCategoria, p -> p.categoriaNome() == null ? "" : p.categoriaNome());
        colunaPreco.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().precoVenda()));
        colunaPreco.setCellFactory(col -> celulaNumero(Formatos::moeda));
        colunaEstoque.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        colunaEstoque.setComparator(Comparator.comparing(ProdutoResumo::estoque));
        colunaEstoque.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ProdutoResumo produto, boolean vazio) {
                super.updateItem(produto, vazio);
                getStyleClass().remove("negativo");
                if (vazio || produto == null) {
                    setText(null);
                    return;
                }
                setText(Formatos.quantidade(produto.estoque(), produto.unidadeFracionada()) + " " + produto.unidade());
                if (produto.estoque().signum() < 0) {
                    getStyleClass().add("negativo");
                }
            }
        });
        texto(colunaAlteracao, p -> Formatos.dataHora(p.atualizadoEm()) + " — " + p.atualizadoPorNome());

        tabela.setRowFactory(t -> {
            TableRow<ProdutoResumo> linha = new TableRow<>() {
                @Override
                protected void updateItem(ProdutoResumo produto, boolean vazio) {
                    super.updateItem(produto, vazio);
                    getStyleClass().remove("inativo");
                    if (!vazio && produto != null && !produto.ativo()) {
                        getStyleClass().add("inativo");
                    }
                }
            };
            linha.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !linha.isEmpty()) {
                    abrir(linha.getItem().id());
                }
            });
            return linha;
        });
        tabela.setOnKeyPressed(e -> {
            ProdutoResumo selecionado = tabela.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && selecionado != null) {
                abrir(selecionado.id());
                e.consume();
            }
        });

        boolean fiscal = sessao.pode(Permissao.FISCAL);
        botaoFiscal.setVisible(fiscal);
        botaoFiscal.setManaged(fiscal);
        filtroSituacao.getItems().setAll(SituacaoProduto.values());
        filtroSituacao.setValue(SituacaoProduto.ATIVOS);
        carregarCategorias();
        filtroCategoria.setOnAction(e -> pesquisar());
        filtroSituacao.setOnAction(e -> pesquisar());

        KeyCombination atalhoNovo = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);
        tabela.getParent().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (atalhoNovo.match(e)) {
                e.consume();
                novo();
            }
        });

        pesquisar();
        Platform.runLater(campoBusca::requestFocus);
    }

    private static void texto(TableColumn<ProdutoResumo, String> coluna, Function<ProdutoResumo, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    private static TableCell<ProdutoResumo, BigDecimal> celulaNumero(Function<BigDecimal, String> formato) {
        return new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal valor, boolean vazio) {
                super.updateItem(valor, vazio);
                setText(vazio || valor == null ? null : formato.apply(valor));
            }
        };
    }

    private void carregarCategorias() {
        Categoria escolhida = filtroCategoria.getValue();
        filtroCategoria.getItems().setAll(TODAS);
        filtroCategoria.getItems().addAll(categoriaService.listar(sessao, true));
        filtroCategoria.setValue(escolhida == null ? TODAS
                : filtroCategoria.getItems().stream().filter(c -> c.id() == escolhida.id()).findFirst().orElse(TODAS));
    }

    /** Aberta pelo alerta do Painel: só os produtos ativos com preço abaixo do custo. */
    public void mostrarAbaixoDoCusto() {
        filtroSituacao.setValue(SituacaoProduto.ABAIXO_DO_CUSTO);
        pesquisar();
    }

    @FXML
    private void pesquisar() {
        Categoria categoria = filtroCategoria.getValue();
        Long categoriaId = categoria == null || categoria == TODAS ? null : categoria.id();
        SituacaoProduto situacao = filtroSituacao.getValue() == null ? SituacaoProduto.ATIVOS : filtroSituacao.getValue();
        Long selecionado = tabela.getSelectionModel().getSelectedItem() == null ? null
                : tabela.getSelectionModel().getSelectedItem().id();

        tabela.getItems().setAll(produtoService.pesquisar(sessao, campoBusca.getText(), categoriaId, situacao));
        int total = tabela.getItems().size();
        resumo.setText(total == 1 ? "1 produto" : total + " produtos");
        if (selecionado != null) {
            tabela.getItems().stream().filter(p -> p.id() == selecionado).findFirst()
                    .ifPresent(p -> tabela.getSelectionModel().select(p));
        }
    }

    @FXML
    private void novo() {
        abrir(null);
    }

    /** Abre o cadastro (nulo = novo) e, ao fechar, refaz a busca. */
    private void abrir(Long id) {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/produto.fxml",
                id == null ? "Novo produto" : "Produto " + id, fabricaControllers,
                (ProdutoController c) -> c.carregar(id));
        pesquisar();
    }

    @FXML
    private void abrirPromocoes() {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/promocoes.fxml", "Promoções", fabricaControllers,
                (PromocoesController c) -> { });
    }

    /** Cadastro fiscal (F19.2 e F19.3), só para quem tem FISCAL; ao fechar, refaz a busca. */
    @FXML
    public void abrirFiscal() {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/fiscal.fxml", "Cadastro fiscal", fabricaControllers,
                (FiscalController c) -> { });
        pesquisar();
    }

    /** Etiquetas de gôndola (F18.1); começa nas pendentes. Também aberta pelo alerta do Painel (F18.3). */
    @FXML
    public void abrirEtiquetas() {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/etiquetas.fxml", "Etiquetas de gôndola",
                fabricaControllers, (EtiquetasController c) -> { });
    }

    /** Carga de balança (F18.2). Também aberta pelo alerta do Painel (F18.3). */
    @FXML
    public void abrirCargaBalanca() {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/carga-balanca.fxml", "Carga de balança",
                fabricaControllers, (CargaBalancaController c) -> { });
    }

    /** Importação por planilha (6.11); ao fechar, refaz a busca e a lista de categorias (pode ter criado novas). */
    @FXML
    private void abrirImportacao() {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/importacao-produtos.fxml", "Importar planilha",
                fabricaControllers, (ImportacaoProdutosController c) -> { });
        carregarCategorias();
        pesquisar();
    }

    @FXML
    private void abrirCategorias() {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/categorias.fxml", "Categorias",
                fabricaControllers, (CategoriasController c) -> { });
        carregarCategorias();
        pesquisar();
    }

    /** Cadastro de unidades de medida (F10.5). */
    @FXML
    private void abrirUnidades() {
        Janelas.abrirModal(tabela.getScene().getWindow(), "/fxml/unidades.fxml", "Unidades de medida",
                fabricaControllers, (UnidadesController c) -> { });
    }
}
