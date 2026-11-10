package br.com.varesync.controller;

import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.ConteudoLiquido;
import br.com.varesync.model.UnidadeConteudo;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.model.HistoricoPreco;
import br.com.varesync.model.Lote;
import br.com.varesync.model.ParteLote;
import br.com.varesync.model.Produto;
import br.com.varesync.model.ProdutoFornecedor;
import br.com.varesync.model.UnidadeMedida;
import br.com.varesync.service.CategoriaService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.ProdutoFornecedorService;
import br.com.varesync.service.ProdutoService;
import br.com.varesync.service.RegistroEmUsoException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosProduto;
import br.com.varesync.ui.DialogosVinculo;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Gtin;
import br.com.varesync.util.Precificacao;
import br.com.varesync.util.Textos;
import br.com.varesync.model.DadosVinculo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * Cadastro de produto (6.3, Fig. 20 revisada): estoque atual somente leitura, estoque inicial só no cadastro novo,
 * códigos de barras, simulador de margem atualizado enquanto digita, aba Fornecedores (F16.1: código no fornecedor,
 * fator, prazo e principal) e aba de histórico de preço e custo.
 */
public class ProdutoController {

    /** Item "sem categoria" da lista (o produto grava categoria nula). */
    private static final Categoria SEM_CATEGORIA = new Categoria(0, "(sem categoria)", true);

    private final ProdutoService produtoService;
    private final CategoriaService categoriaService;
    private final ProdutoFornecedorService vinculoService;
    private final Sessao sessao;
    /** Produto em edição; nulo no cadastro novo. */
    private Produto produto;
    private boolean balancaAtiva;

    @FXML private Label titulo;
    @FXML private Label situacao;
    @FXML private TabPane abas;
    @FXML private Tab abaHistorico;
    @FXML private Tab abaFornecedores;
    @FXML private TableView<ProdutoFornecedor> tabelaVinculos;
    @FXML private TableColumn<ProdutoFornecedor, String> colunaVinculoFornecedor;
    @FXML private TableColumn<ProdutoFornecedor, String> colunaVinculoCodigo;
    @FXML private TableColumn<ProdutoFornecedor, String> colunaVinculoFator;
    @FXML private TableColumn<ProdutoFornecedor, String> colunaVinculoPrazo;
    @FXML private TableColumn<ProdutoFornecedor, String> colunaVinculoCusto;
    @FXML private TableColumn<ProdutoFornecedor, String> colunaVinculoPrincipal;
    @FXML private Button botaoIncluirVinculo;
    @FXML private Button botaoAlterarVinculo;
    @FXML private Button botaoPrincipalVinculo;
    @FXML private Button botaoRemoverVinculo;
    @FXML private TextField campoCodigo;
    @FXML private TextField campoNome;
    @FXML private ComboBox<Categoria> campoCategoria;
    @FXML private ComboBox<UnidadeMedida> campoUnidade;
    @FXML private TextField campoPreco;
    @FXML private TextField campoCusto;
    @FXML private TextField campoEstoqueMinimo;
    @FXML private TextField campoConteudo;
    @FXML private ComboBox<UnidadeConteudo> campoUnidadeConteudo;
    @FXML private Label precoPorMedida;
    @FXML private Label rotuloEstoqueInicial;
    @FXML private TextField campoEstoqueInicial;
    @FXML private CheckBox campoControlaValidade;
    @FXML private Label rotuloLoteInicial;
    @FXML private HBox painelLoteInicial;
    @FXML private TextField campoLoteInicial;
    @FXML private DatePicker campoValidadeInicial;
    @FXML private Label estoqueAtual;
    @FXML private Label rotuloEstoqueAtual;
    @FXML private TextField campoPlu;
    @FXML private TextField campoValidadeBalanca;
    @FXML private Label ultimaAlteracao;
    @FXML private TextField campoNovoCodigo;
    @FXML private TableView<CodigoBarras> listaCodigos;
    @FXML private TableColumn<CodigoBarras, String> colunaCodigoBarras;
    @FXML private TableColumn<CodigoBarras, String> colunaQtdEmbalagem;
    @FXML private TableColumn<CodigoBarras, String> colunaPrecoEmbalagem;
    @FXML private Button botaoEmbalagem;
    @FXML private Tab abaFaixas;
    @FXML private TableView<FaixaPreco> tabelaFaixas;
    @FXML private TableColumn<FaixaPreco, String> colunaFaixaQuantidade;
    @FXML private TableColumn<FaixaPreco, String> colunaFaixaPreco;
    @FXML private TableColumn<FaixaPreco, String> colunaFaixaDesconto;
    @FXML private TableColumn<FaixaPreco, String> colunaFaixaMargem;
    @FXML private Button botaoAlterarFaixa;
    @FXML private Button botaoRemoverFaixa;
    @FXML private Button botaoPrincipal;
    @FXML private Button botaoRemoverCodigo;
    @FXML private Label lucroUnitario;
    @FXML private Label margem;
    @FXML private Label markup;
    @FXML private TextField campoMargemDesejada;
    @FXML private Label precoSugerido;
    @FXML private Button botaoAplicarPreco;
    @FXML private TableView<HistoricoPreco> tabelaHistorico;
    @FXML private TableColumn<HistoricoPreco, String> colunaData;
    @FXML private TableColumn<HistoricoPreco, String> colunaUsuario;
    @FXML private TableColumn<HistoricoPreco, String> colunaPrecoHistorico;
    @FXML private TableColumn<HistoricoPreco, String> colunaCustoHistorico;
    @FXML private Label mensagem;
    @FXML private Button botaoExcluir;
    @FXML private Button botaoSituacao;

    public ProdutoController(ProdutoService produtoService, CategoriaService categoriaService,
                             ProdutoFornecedorService vinculoService, Sessao sessao) {
        this.produtoService = produtoService;
        this.categoriaService = categoriaService;
        this.vinculoService = vinculoService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        campoUnidade.getItems().setAll(produtoService.unidades(sessao));
        balancaAtiva = produtoService.balancaAtiva(sessao);

        // F20.3: cada código com a quantidade e o preço da embalagem (caixa, fardo).
        colunaCodigoBarras.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().codigo()
                + (c.getValue().principal() ? "  (principal)" : "")));
        colunaQtdEmbalagem.setCellValueFactory(c -> new ReadOnlyStringWrapper(numero(c.getValue().quantidade())));
        colunaPrecoEmbalagem.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().precoEmbalagem() == null
                ? "—" : Formatos.moeda(c.getValue().precoEmbalagem())));
        listaCodigos.getSelectionModel().selectedItemProperty().addListener((obs, a, b) -> atualizarBotoesCodigo());
        listaCodigos.setRowFactory(t -> {
            javafx.scene.control.TableRow<CodigoBarras> linha = new javafx.scene.control.TableRow<>();
            linha.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !linha.isEmpty()) {
                    editarEmbalagem();
                }
            });
            return linha;
        });
        prepararFaixas();

        coluna(colunaData, h -> Formatos.dataHora(h.criadoEm()));
        coluna(colunaUsuario, HistoricoPreco::usuarioNome);
        coluna(colunaPrecoHistorico, h -> Formatos.moeda(h.precoAnterior()) + " → " + Formatos.moeda(h.precoNovo()));
        coluna(colunaCustoHistorico, h -> custo(h.custoAnterior()) + " → " + custo(h.custoNovo()));
        prepararVinculos();

        campoPreco.textProperty().addListener((obs, a, b) -> recalcular());
        campoCusto.textProperty().addListener((obs, a, b) -> recalcular());
        campoMargemDesejada.textProperty().addListener((obs, a, b) -> recalcular());
        campoUnidade.valueProperty().addListener((obs, a, b) -> atualizarPlu());
        campoPlu.textProperty().addListener((obs, a, b) -> atualizarPlu());
        campoUnidadeConteudo.getItems().setAll(UnidadeConteudo.values());
        campoConteudo.textProperty().addListener((obs, a, b) -> atualizarPrecoPorMedida());
        campoUnidadeConteudo.valueProperty().addListener((obs, a, b) -> atualizarPrecoPorMedida());
        campoPreco.textProperty().addListener((obs, a, b) -> atualizarPrecoPorMedida());
        campoUnidade.valueProperty().addListener((obs, a, b) -> atualizarPrecoPorMedida());

        rotuloEstoqueInicial.managedProperty().bind(rotuloEstoqueInicial.visibleProperty());
        campoEstoqueInicial.managedProperty().bind(campoEstoqueInicial.visibleProperty());
        rotuloEstoqueInicial.visibleProperty().bind(campoEstoqueInicial.visibleProperty());
        // F17.3: lote e validade do estoque inicial só no produto novo com o controle ligado.
        campoValidadeInicial.setConverter(new ConversorData());
        painelLoteInicial.visibleProperty().bind(campoEstoqueInicial.visibleProperty()
                .and(campoControlaValidade.selectedProperty()));
        painelLoteInicial.managedProperty().bind(painelLoteInicial.visibleProperty());
        rotuloLoteInicial.visibleProperty().bind(painelLoteInicial.visibleProperty());
        rotuloLoteInicial.managedProperty().bind(painelLoteInicial.visibleProperty());
        // No produto novo, o estoque atual é "—" e o lugar dele fica com o estoque inicial (a tela cabe em 1280×720).
        estoqueAtual.visibleProperty().bind(campoEstoqueInicial.visibleProperty().not());
        estoqueAtual.managedProperty().bind(estoqueAtual.visibleProperty());
        rotuloEstoqueAtual.visibleProperty().bind(estoqueAtual.visibleProperty());
        rotuloEstoqueAtual.managedProperty().bind(estoqueAtual.visibleProperty());
    }

    private static void coluna(TableColumn<HistoricoPreco, String> coluna, Function<HistoricoPreco, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    /** Produto novo já preenchido com o item de uma NF-e (6.11): nome, código de barras e custo da nota. */
    public void preencherNovo(String nome, String codigoBarras, BigDecimal custo) {
        carregar(null);
        campoNome.setText(nome == null ? "" : nome);
        if (custo != null) {
            campoCusto.setText(Formatos.numero(custo, Dinheiro.ESCALA_CUSTO));
        }
        if (codigoBarras != null) {
            listaCodigos.getItems().add(new CodigoBarras(codigoBarras, true));
        }
    }

    // ----- Preço por quantidade (F20.3) -----

    private void prepararFaixas() {
        colunaFaixaQuantidade.setCellValueFactory(c -> new ReadOnlyStringWrapper(numero(c.getValue().quantidadeMinima())
                + " " + siglaUnidade().toLowerCase()));
        colunaFaixaPreco.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().preco())));
        colunaFaixaDesconto.setCellValueFactory(c -> new ReadOnlyStringWrapper(lerSemErro(campoPreco)
                .filter(p -> p.signum() > 0)
                .map(p -> Formatos.percentual(p.subtract(c.getValue().preco()).multiply(BigDecimal.valueOf(100))
                        .divide(p, Dinheiro.ESCALA_PERCENTUAL, Dinheiro.ARREDONDAMENTO)))
                .orElse(Formatos.SEM_VALOR)));
        colunaFaixaMargem.setCellValueFactory(c -> new ReadOnlyStringWrapper(lerSemErro(campoCusto)
                .map(custo -> Formatos.percentual(Precificacao.margem(custo, c.getValue().preco())))
                .orElse(Formatos.SEM_VALOR)));
        tabelaFaixas.getSelectionModel().selectedItemProperty().addListener((obs, a, b) -> atualizarBotoesFaixa());
        tabelaFaixas.setRowFactory(t -> {
            javafx.scene.control.TableRow<FaixaPreco> linha = new javafx.scene.control.TableRow<>();
            linha.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !linha.isEmpty()) {
                    alterarFaixa();
                }
            });
            return linha;
        });
    }

    private void atualizarBotoesFaixa() {
        boolean nenhuma = tabelaFaixas.getSelectionModel().getSelectedItem() == null;
        botaoAlterarFaixa.setDisable(nenhuma);
        botaoRemoverFaixa.setDisable(nenhuma);
    }

    @FXML
    private void incluirFaixa() {
        DialogosProduto.faixa(null, siglaUnidade(), precoDigitado(), (q, p) -> conferirFaixa(null, q, p))
                .ifPresent(f -> {
                    tabelaFaixas.getItems().add(f);
                    ordenarFaixas(f);
                });
    }

    @FXML
    private void alterarFaixa() {
        FaixaPreco atual = tabelaFaixas.getSelectionModel().getSelectedItem();
        if (atual == null) {
            return;
        }
        DialogosProduto.faixa(atual, siglaUnidade(), precoDigitado(), (q, p) -> conferirFaixa(atual, q, p))
                .ifPresent(f -> {
                    tabelaFaixas.getItems().set(tabelaFaixas.getItems().indexOf(atual), f);
                    ordenarFaixas(f);
                });
    }

    @FXML
    private void removerFaixa() {
        FaixaPreco atual = tabelaFaixas.getSelectionModel().getSelectedItem();
        if (atual != null) {
            tabelaFaixas.getItems().remove(atual);
            Mensagem.informar(mensagem, "Faixa a partir de " + numero(atual.quantidadeMinima())
                    + " removida da tela: clique em Salvar para gravar.");
        }
    }

    /** A faixa digitada vale sozinha; a quantidade não pode repetir a de outra faixa da tela. */
    private FaixaPreco conferirFaixa(FaixaPreco editando, BigDecimal quantidade, BigDecimal preco) {
        FaixaPreco f = ProdutoService.conferirFaixa(editando == null ? 0 : editando.id(), quantidade, preco,
                unidadeFracionada());
        boolean repetida = tabelaFaixas.getItems().stream()
                .anyMatch(o -> o != editando && o.quantidadeMinima().compareTo(f.quantidadeMinima()) == 0);
        if (repetida) {
            throw new NegocioException("Já existe uma faixa a partir de " + numero(f.quantidadeMinima()) + ".");
        }
        return f;
    }

    private void ordenarFaixas(FaixaPreco selecionar) {
        tabelaFaixas.getItems().sort(java.util.Comparator.comparing(FaixaPreco::quantidadeMinima));
        tabelaFaixas.getSelectionModel().select(selecionar);
        Mensagem.informar(mensagem, "Faixa a partir de " + numero(selecionar.quantidadeMinima()) + " por "
                + Formatos.moeda(selecionar.preco()) + " na tela: clique em Salvar para gravar.");
    }

    private String siglaUnidade() {
        UnidadeMedida unidade = campoUnidade.getValue();
        return unidade == null ? UnidadeMedida.PADRAO : unidade.sigla();
    }

    private boolean unidadeFracionada() {
        UnidadeMedida unidade = campoUnidade.getValue();
        return unidade != null && unidade.fracionada();
    }

    private String precoDigitado() {
        return lerSemErro(campoPreco).map(Formatos::moeda).orElse("o preço de venda");
    }

    /** Sem separador de milhar nem zeros no fim (12; 0,5). */
    private static String numero(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Código do produto na tela (o que acabou de ser salvo, no cadastro novo), ou vazio. */
    public Optional<Long> produtoId() {
        return produto == null ? Optional.empty() : Optional.of(produto.id());
    }

    /** Mostra o produto {@code id}, ou o formulário vazio se nulo. */
    public void carregar(Long id) {
        produto = id == null ? null : produtoService.buscar(sessao, id);
        carregarCategorias();
        if (produto == null) {
            titulo.setText("Novo produto");
            situacao.setText("");
            campoCodigo.setText("");
            campoNome.setText("");
            campoCategoria.setValue(SEM_CATEGORIA);
            campoUnidade.getItems().stream().filter(u -> u.sigla().equals(UnidadeMedida.PADRAO)).findFirst()
                    .ifPresent(campoUnidade::setValue);
            campoPreco.setText("");
            campoCusto.setText("");
            campoEstoqueMinimo.setText("");
            campoConteudo.setText("");
            campoUnidadeConteudo.setValue(null);
            campoEstoqueInicial.setText("");
            campoEstoqueInicial.setVisible(true);
            campoControlaValidade.setSelected(false);
            campoLoteInicial.clear();
            campoValidadeInicial.setValue(null);
            estoqueAtual.setText(Formatos.SEM_VALOR);
            campoPlu.setText("");
            campoValidadeBalanca.setText("");
            ultimaAlteracao.setText(Formatos.SEM_VALOR);
            listaCodigos.getItems().clear();
            tabelaFaixas.getItems().clear();
            tabelaHistorico.getItems().clear();
            abaHistorico.setDisable(true);
            tabelaVinculos.getItems().clear();
            abaFornecedores.setDisable(true);
            botaoExcluir.setDisable(true);
            botaoSituacao.setDisable(true);
            botaoSituacao.setText("Inativar");
        } else {
            titulo.setText(produto.nome());
            situacao.setText(produto.ativo() ? "Ativo" : "Inativo — não aparece no PDV nem na busca padrão");
            campoCodigo.setText(String.valueOf(produto.id()));
            campoNome.setText(produto.nome());
            campoUnidade.getItems().stream().filter(u -> u.sigla().equals(produto.unidade())).findFirst()
                    .ifPresent(campoUnidade::setValue);
            campoPreco.setText(Formatos.numero(produto.precoVenda(), Dinheiro.ESCALA_DINHEIRO));
            campoCusto.setText(Formatos.numero(produto.custo(), Dinheiro.ESCALA_CUSTO));
            // sem separador de milhar: "1.234" seria lido como 1,234 (Dinheiro.ler)
            campoEstoqueMinimo.setText(produto.estoqueMinimo().stripTrailingZeros().toPlainString().replace('.', ','));
            campoEstoqueInicial.setVisible(false);
            campoConteudo.setText(produto.conteudo().vazio() ? ""
                    : produto.conteudo().quantidade().stripTrailingZeros().toPlainString().replace('.', ','));
            campoUnidadeConteudo.setValue(produto.conteudo().unidade());
            campoControlaValidade.setSelected(produto.controlaValidade());
            estoqueAtual.setText(Formatos.quantidade(produto.estoqueAtual(), produto.unidadeFracionada()) + " "
                    + produto.unidade());
            estoqueAtual.getStyleClass().remove("negativo");
            if (produto.estoqueAtual().signum() < 0) {
                estoqueAtual.getStyleClass().add("negativo");
            }
            campoPlu.setText(produto.plu() == null ? "" : String.valueOf(produto.plu()));
            campoValidadeBalanca.setText(produto.validadeBalancaDias() == null ? ""
                    : String.valueOf(produto.validadeBalancaDias()));
            ultimaAlteracao.setText(Formatos.dataHora(produto.atualizadoEm()) + " por " + produto.atualizadoPorNome());
            listaCodigos.getItems().setAll(produto.codigosBarras());
            tabelaFaixas.getItems().setAll(produtoService.faixas(sessao, produto.id()));
            tabelaHistorico.getItems().setAll(produtoService.historico(sessao, produto.id()));
            abaHistorico.setDisable(false);
            abaFornecedores.setDisable(false);
            carregarVinculos(null);
            botaoExcluir.setDisable(false);
            botaoSituacao.setDisable(false);
            botaoSituacao.setText(produto.ativo() ? "Inativar" : "Reativar");
        }
        campoNovoCodigo.clear();
        campoMargemDesejada.clear();
        atualizarBotoesCodigo();
        atualizarBotoesFaixa();
        atualizarPlu();
        recalcular();
        abas.getSelectionModel().selectFirst();
        Platform.runLater(campoNome::requestFocus);
    }

    // ----- Fornecedores (F16.1) -----

    private void prepararVinculos() {
        colunaVinculoFornecedor.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().fornecedorNome()
                + (c.getValue().fornecedorAtivo() ? "" : " (inativo)")));
        colunaVinculoCodigo.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().codigoNoFornecedor() == null ? "" : c.getValue().codigoNoFornecedor()));
        colunaVinculoFator.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                ProdutoFornecedorService.descreverFator(c.getValue(), produto == null ? "" : produto.unidade())));
        colunaVinculoPrazo.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                String.valueOf(c.getValue().prazoEntregaDias())));
        colunaVinculoCusto.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().ultimoCusto() == null
                ? Formatos.SEM_VALOR : custo(c.getValue().ultimoCusto())));
        colunaVinculoPrincipal.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().principal() ? "Sim" : ""));
        tabelaVinculos.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> atualizarBotoesVinculo());
        tabelaVinculos.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tabelaVinculos.getSelectionModel().getSelectedItem() != null) {
                alterarVinculo();
            }
        });
        atualizarBotoesVinculo();
    }

    /** Recarrega a lista e seleciona o vínculo {@code selecionar}, se informado. */
    private void carregarVinculos(Long selecionar) {
        tabelaVinculos.getItems().setAll(vinculoService.listar(sessao, produto.id()));
        tabelaVinculos.getItems().stream().filter(v -> selecionar != null && v.id() == selecionar).findFirst()
                .ifPresent(v -> tabelaVinculos.getSelectionModel().select(v));
        atualizarBotoesVinculo();
    }

    private void atualizarBotoesVinculo() {
        ProdutoFornecedor v = tabelaVinculos.getSelectionModel().getSelectedItem();
        botaoAlterarVinculo.setDisable(v == null);
        botaoRemoverVinculo.setDisable(v == null);
        botaoPrincipalVinculo.setDisable(v == null || v.principal());
    }

    @FXML
    private void incluirVinculo() {
        if (produto == null) {
            return;
        }
        DialogosVinculo.editar(null, vinculoService.fornecedores(sessao), produto.unidade(),
                        dados -> vinculoService.salvar(sessao, produto.id(), null, dados))
                .ifPresent(id -> {
                    carregarVinculos(id);
                    Mensagem.sucesso(mensagem, "Fornecedor incluído no produto.");
                });
    }

    @FXML
    private void alterarVinculo() {
        ProdutoFornecedor v = tabelaVinculos.getSelectionModel().getSelectedItem();
        if (produto == null || v == null) {
            return;
        }
        DialogosVinculo.editar(v, List.of(), produto.unidade(),
                        dados -> vinculoService.salvar(sessao, produto.id(), v.id(), dados))
                .ifPresent(id -> {
                    carregarVinculos(id);
                    Mensagem.sucesso(mensagem, "Fornecedor do produto salvo.");
                });
    }

    @FXML
    private void tornarPrincipal() {
        ProdutoFornecedor v = tabelaVinculos.getSelectionModel().getSelectedItem();
        if (produto == null || v == null) {
            return;
        }
        try {
            vinculoService.salvar(sessao, produto.id(), v.id(), new DadosVinculo(v.fornecedorId(),
                    v.codigoNoFornecedor(), v.unidadeCompra(), v.fator(), v.prazoEntregaDias(), true));
            carregarVinculos(v.id());
            Mensagem.sucesso(mensagem, v.fornecedorNome() + " é agora o fornecedor principal.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void removerVinculo() {
        ProdutoFornecedor v = tabelaVinculos.getSelectionModel().getSelectedItem();
        if (produto == null || v == null || !Dialogos.confirmar("Remover " + v.fornecedorNome()
                + " dos fornecedores deste produto? As notas dele deixam de reconhecer o item pelo código do "
                + "fornecedor.")) {
            return;
        }
        try {
            vinculoService.remover(sessao, produto.id(), v.id());
            carregarVinculos(null);
            Mensagem.sucesso(mensagem, "Fornecedor removido do produto.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /** Categorias ativas, mais a do produto se ela foi inativada depois. */
    private void carregarCategorias() {
        List<Categoria> categorias = new ArrayList<>();
        categorias.add(SEM_CATEGORIA);
        for (Categoria categoria : categoriaService.listar(sessao, true)) {
            boolean doProduto = produto != null && produto.categoriaId() != null
                    && produto.categoriaId() == categoria.id();
            if (categoria.ativa() || doProduto) {
                categorias.add(categoria);
            }
        }
        campoCategoria.getItems().setAll(categorias);
        if (produto != null && produto.categoriaId() != null) {
            categorias.stream().filter(c -> c.id() == produto.categoriaId()).findFirst()
                    .ifPresent(campoCategoria::setValue);
        } else {
            campoCategoria.setValue(SEM_CATEGORIA);
        }
    }

    // ----- Códigos de barras -----

    @FXML
    private void adicionarCodigo(ActionEvent evento) {
        evento.consume(); // Enter no campo adiciona o código e não aciona o botão Salvar
        String codigo = Textos.normalizarEspacos(campoNovoCodigo.getText());
        Optional<String> erro = Gtin.validar(codigo);
        if (erro.isPresent()) {
            Mensagem.erro(mensagem, erro.get());
            campoNovoCodigo.selectAll();
            campoNovoCodigo.requestFocus();
            return;
        }
        if (listaCodigos.getItems().stream().anyMatch(c -> c.codigo().equals(codigo))) {
            Mensagem.erro(mensagem, "O código " + codigo + " já está na lista.");
            campoNovoCodigo.selectAll();
            return;
        }
        listaCodigos.getItems().add(new CodigoBarras(codigo, listaCodigos.getItems().isEmpty()));
        campoNovoCodigo.clear();
        campoNovoCodigo.requestFocus();
        Mensagem.limpar(mensagem);
    }

    @FXML
    private void removerCodigo() {
        CodigoBarras selecionado = listaCodigos.getSelectionModel().getSelectedItem();
        if (selecionado == null) {
            return;
        }
        listaCodigos.getItems().remove(selecionado);
        if (selecionado.principal()) {
            // O novo principal é o primeiro código da unidade (a embalagem não pode ser principal, F20.3).
            for (int i = 0; i < listaCodigos.getItems().size(); i++) {
                CodigoBarras c = listaCodigos.getItems().get(i);
                if (!c.ehEmbalagem()) {
                    listaCodigos.getItems().remove(i);
                    listaCodigos.getItems().add(0, c.comPrincipal(true));
                    break;
                }
            }
        }
    }

    @FXML
    private void marcarPrincipal() {
        CodigoBarras selecionado = listaCodigos.getSelectionModel().getSelectedItem();
        if (selecionado == null) {
            return;
        }
        if (selecionado.ehEmbalagem()) {
            Mensagem.erro(mensagem, "O código principal é o da unidade: o código " + selecionado.codigo() + " é de "
                    + "uma embalagem. Tire a embalagem dele (Embalagem…) antes de marcá-lo como principal.");
            return;
        }
        List<CodigoBarras> codigos = new ArrayList<>();
        codigos.add(selecionado.comPrincipal(true));
        listaCodigos.getItems().stream().filter(c -> !c.codigo().equals(selecionado.codigo()))
                .map(c -> c.comPrincipal(false)).forEach(codigos::add);
        listaCodigos.getItems().setAll(codigos);
        listaCodigos.getSelectionModel().selectFirst();
    }

    /** F20.3: quantidade e preço da embalagem do código selecionado (caixa, fardo); vale ao salvar o produto. */
    @FXML
    private void editarEmbalagem() {
        CodigoBarras selecionado = listaCodigos.getSelectionModel().getSelectedItem();
        if (selecionado == null) {
            return;
        }
        DialogosProduto.embalagem(selecionado, siglaUnidade(),
                (q, p) -> ProdutoService.conferirEmbalagem(selecionado, q, p, unidadeFracionada()))
                .ifPresent(novo -> {
                    int indice = listaCodigos.getItems().indexOf(selecionado);
                    listaCodigos.getItems().set(indice, novo);
                    listaCodigos.getSelectionModel().select(indice);
                    Mensagem.informar(mensagem, "Embalagem do código " + novo.codigo() + " na tela: clique em Salvar "
                            + "para gravar.");
                });
    }

    private void atualizarBotoesCodigo() {
        CodigoBarras selecionado = listaCodigos.getSelectionModel().getSelectedItem();
        botaoRemoverCodigo.setDisable(selecionado == null);
        botaoPrincipal.setDisable(selecionado == null || selecionado.principal());
        botaoEmbalagem.setDisable(selecionado == null);
    }

    /**
     * PLU só com balança ativa e unidade fracionada; um PLU já gravado pode ser apagado. A validade na balança (F18.2)
     * acompanha o PLU: sem PLU, fica desabilitada (a validade guardada sai ao gravar).
     */
    private void atualizarPlu() {
        UnidadeMedida unidade = campoUnidade.getValue();
        boolean liberado = balancaAtiva && unidade != null && unidade.fracionada();
        campoPlu.setDisable(!liberado && Textos.vazio(campoPlu.getText()));
        campoValidadeBalanca.setDisable(campoPlu.isDisabled() || Textos.vazio(campoPlu.getText()));
    }

    // ----- Simulador (6.3, A.6) -----

    private void recalcular() {
        Optional<BigDecimal> preco = lerSemErro(campoPreco);
        Optional<BigDecimal> custo = lerSemErro(campoCusto);
        if (preco.isPresent() && custo.isPresent()) {
            BigDecimal lucro = Precificacao.lucroUnitario(custo.get(), preco.get());
            lucroUnitario.setText(Formatos.moeda(lucro));
            alternarNegativo(lucroUnitario, lucro.signum() < 0);
            Optional<BigDecimal> m = Precificacao.margem(custo.get(), preco.get());
            margem.setText(Formatos.percentual(m));
            alternarNegativo(margem, m.map(v -> v.signum() < 0).orElse(false));
            markup.setText(Formatos.percentual(Precificacao.markup(custo.get(), preco.get())));
        } else {
            lucroUnitario.setText(Formatos.SEM_VALOR);
            margem.setText(Formatos.SEM_VALOR);
            markup.setText(Formatos.SEM_VALOR);
        }
        Optional<BigDecimal> desejada = lerSemErro(campoMargemDesejada);
        Optional<BigDecimal> sugerido = custo.isPresent() && desejada.isPresent() && desejada.get().signum() >= 0
                ? Precificacao.precoSugerido(custo.get(), desejada.get())
                : Optional.empty();
        precoSugerido.setText(Formatos.moeda(sugerido));
        botaoAplicarPreco.setDisable(sugerido.isEmpty());
        tabelaFaixas.refresh(); // desconto e margem das faixas acompanham o preço e o custo digitados
    }

    @FXML
    private void aplicarPrecoSugerido() {
        Optional<BigDecimal> custo = lerSemErro(campoCusto);
        Optional<BigDecimal> desejada = lerSemErro(campoMargemDesejada);
        if (custo.isEmpty() || desejada.isEmpty()) {
            return;
        }
        Precificacao.precoSugerido(custo.get(), desejada.get())
                .ifPresent(p -> campoPreco.setText(Formatos.numero(p, Dinheiro.ESCALA_DINHEIRO)));
    }

    private static void alternarNegativo(Label rotulo, boolean negativo) {
        rotulo.getStyleClass().remove("negativo");
        if (negativo) {
            rotulo.getStyleClass().add("negativo");
        }
    }

    // ----- Salvar, excluir, inativar -----

    @FXML
    private void salvar() {
        try {
            DadosProduto dados = lerFormulario();
            Long id = produto == null ? null : produto.id();
            if (id != null && !confirmarControleValidade()) {
                return;
            }
            List<String> avisos = produtoService.avisos(sessao, id, dados);
            if (!avisos.isEmpty() && !Dialogos.confirmar(String.join("\n", avisos) + "\n\nSalvar mesmo assim?")) {
                return;
            }
            if (id == null) {
                id = produtoService.criar(sessao, dados);
            } else {
                produtoService.alterar(sessao, id, dados);
            }
            carregar(id);
            atualizarTituloJanela();
            Mensagem.sucesso(mensagem, "Produto salvo.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    private DadosProduto lerFormulario() {
        Categoria categoria = campoCategoria.getValue();
        UnidadeMedida unidade = campoUnidade.getValue();
        return new DadosProduto(
                campoNome.getText(),
                categoria == null || categoria == SEM_CATEGORIA ? null : categoria.id(),
                unidade == null ? null : unidade.sigla(),
                ler(campoPreco, "Preço de venda"),
                ler(campoCusto, "Custo"),
                ler(campoEstoqueMinimo, "Estoque mínimo"),
                lerPlu(),
                List.copyOf(listaCodigos.getItems()),
                produto == null ? ler(campoEstoqueInicial, "Estoque inicial") : null,
                campoControlaValidade.isSelected(),
                produto == null && campoControlaValidade.isSelected() ? loteInicial() : null,
                lerConteudo(),
                lerValidadeBalanca(),
                List.copyOf(tabelaFaixas.getItems()));
    }

    /**
     * F17.3: ao ligar o controle num produto existente, avisa que o saldo atual vai para o "SEM LOTE" (e que a
     * transferência passa esse saldo para os lotes certos); ao desligar, que os lotes ficam parados e saem dos
     * alertas. Sem mudança no controle, não pergunta nada.
     */
    private boolean confirmarControleValidade() {
        boolean ligar = campoControlaValidade.isSelected();
        if (ligar == produto.controlaValidade()) {
            return true;
        }
        String saldo = Formatos.quantidade(produto.estoqueAtual(), produto.unidadeFracionada()) + " "
                + produto.unidade();
        return Dialogos.confirmar(ligar
                ? "Ligar o controle de validade e lotes de \"" + produto.nome() + "\"?\n\nO saldo atual (" + saldo
                        + ") que ainda não está em lotes vai para o \"" + Lote.SEM_LOTE + "\". Depois, em Estoque › "
                        + "aba Lotes, use \"Transferir…\" para passar esse saldo para os lotes certos (código e "
                        + "validade). A partir de agora, a entrada de mercadoria pede a divisão em lotes."
                : "Desligar o controle de validade e lotes de \"" + produto.nome() + "\"?\n\nOs lotes ficam "
                        + "parados como estão, saem dos alertas e do relatório de validade e não são mais "
                        + "movimentados. Se o controle voltar, a diferença para o saldo do produto vai para o \""
                        + Lote.SEM_LOTE + "\".");
    }

    /** Lote do estoque inicial (F17.3): sem código nem validade, vai para o "SEM LOTE". */
    private ParteLote loteInicial() {
        String codigo = Textos.normalizarEspacos(campoLoteInicial.getText());
        if (codigo.isEmpty() && campoValidadeInicial.getValue() == null) {
            return null;
        }
        if (campoValidadeInicial.getValue() == null) {
            throw new NegocioException("Informe a validade do lote do estoque inicial (ou apague o código para "
                    + "deixar o estoque no " + Lote.SEM_LOTE + ").");
        }
        return ParteLote.novo(codigo, campoValidadeInicial.getValue(), null);
    }

    @FXML
    private void excluir() {
        if (produto == null) {
            return;
        }
        if (!Dialogos.confirmar("Excluir o produto \"" + produto.nome() + "\"? Esta ação não pode ser desfeita.")) {
            return;
        }
        try {
            produtoService.excluir(sessao, produto.id());
            fechar();
        } catch (RegistroEmUsoException e) {
            if (produto.ativo() && Dialogos.confirmar(e.getMessage() + "\n\nInativar o produto agora?")) {
                mudarSituacao(false);
            } else {
                Mensagem.erro(mensagem, e.getMessage());
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void alternarSituacao() {
        if (produto == null) {
            return;
        }
        boolean ativar = !produto.ativo();
        if (!ativar && !Dialogos.confirmar("Inativar o produto \"" + produto.nome() + "\"? Ele deixa de aparecer "
                + "no PDV e na busca padrão, mas continua nos relatórios.")) {
            return;
        }
        mudarSituacao(ativar);
    }

    private void mudarSituacao(boolean ativo) {
        try {
            produtoService.alterarSituacao(sessao, produto.id(), ativo);
            carregar(produto.id());
            Mensagem.sucesso(mensagem, ativo ? "Produto reativado." : "Produto inativado.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    @FXML
    private void fechar() {
        ((Stage) titulo.getScene().getWindow()).close();
    }

    private void atualizarTituloJanela() {
        if (titulo.getScene() != null && titulo.getScene().getWindow() instanceof Stage janela) {
            janela.setTitle(Dialogos.TITULO + " — Produto " + produto.id());
        }
    }

    // ----- Leitura dos campos -----

    /** Vazio → nulo; texto inválido → mensagem com o nome do campo. */
    private static BigDecimal ler(TextField campo, String rotulo) {
        String texto = campo.getText();
        if (Textos.vazio(texto)) {
            return null;
        }
        try {
            return Dinheiro.ler(texto.replace("%", ""));
        } catch (IllegalArgumentException e) {
            throw new NegocioException(rotulo + ": valor inválido (\"" + texto.strip() + "\").");
        }
    }

    private static Optional<BigDecimal> lerSemErro(TextField campo) {
        try {
            return Optional.ofNullable(ler(campo, ""));
        } catch (NegocioException e) {
            return Optional.empty();
        }
    }

    /** F18.1: conteúdo líquido digitado; quantidade vazia = sem conteúdo (a unidade é ignorada). */
    private ConteudoLiquido lerConteudo() {
        BigDecimal quantidade = ler(campoConteudo, "Conteúdo líquido");
        if (quantidade == null) {
            return ConteudoLiquido.SEM;
        }
        return new ConteudoLiquido(quantidade, campoUnidadeConteudo.getValue());
    }

    /** "= R$ 17,98 o kg" ao lado do conteúdo, com o preço de venda digitado. */
    private void atualizarPrecoPorMedida() {
        Optional<BigDecimal> quantidade = lerSemErro(campoConteudo);
        Optional<BigDecimal> preco = lerSemErro(campoPreco);
        UnidadeConteudo unidade = campoUnidadeConteudo.getValue();
        UnidadeMedida venda = campoUnidade.getValue();
        boolean porMedida = venda != null && ("KG".equals(venda.sigla()) || "L".equals(venda.sigla()));
        if (quantidade.isEmpty() || quantidade.get().signum() <= 0 || preco.isEmpty() || unidade == null
                || porMedida) {
            precoPorMedida.setText("");
            return;
        }
        BigDecimal valor = new ConteudoLiquido(quantidade.get(), unidade).precoPorMedida(preco.get());
        precoPorMedida.setText("= " + Formatos.moeda(valor) + " o " + unidade.medida() + " na etiqueta");
    }

    /** F18.2: dias de validade na balança; vazio = sem (0), e sem PLU não vai nada (o service tira a guardada). */
    private Integer lerValidadeBalanca() {
        String texto = Textos.normalizarEspacos(campoValidadeBalanca.getText());
        if (texto.isEmpty() || campoValidadeBalanca.isDisabled()) {
            return 0;
        }
        if (!Textos.soDigitos(texto) || texto.length() > 3) {
            throw new NegocioException("Os dias de validade na balança devem ser um número de 1 a 990.");
        }
        return Integer.valueOf(texto);
    }

    private Integer lerPlu() {
        String texto = Textos.normalizarEspacos(campoPlu.getText());
        if (texto.isEmpty()) {
            return null;
        }
        if (!Textos.soDigitos(texto) || texto.length() > 6) {
            throw new NegocioException("O PLU deve ser um número de 1 a 999999.");
        }
        return Integer.valueOf(texto);
    }

    private static String custo(BigDecimal valor) {
        return valor == null ? Formatos.SEM_VALOR : "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }
}
