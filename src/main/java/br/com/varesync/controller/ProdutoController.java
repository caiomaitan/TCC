package br.com.varesync.controller;

import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.HistoricoPreco;
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
import br.com.varesync.ui.DialogosVinculo;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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
    @FXML private Label rotuloEstoqueInicial;
    @FXML private TextField campoEstoqueInicial;
    @FXML private Label estoqueAtual;
    @FXML private TextField campoPlu;
    @FXML private Label ultimaAlteracao;
    @FXML private TextField campoNovoCodigo;
    @FXML private ListView<CodigoBarras> listaCodigos;
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

        listaCodigos.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(CodigoBarras codigo, boolean vazio) {
                super.updateItem(codigo, vazio);
                setText(vazio || codigo == null ? null
                        : codigo.codigo() + (codigo.principal() ? "   (principal)" : ""));
            }
        });
        listaCodigos.getSelectionModel().selectedItemProperty().addListener((obs, a, b) -> atualizarBotoesCodigo());

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

        rotuloEstoqueInicial.managedProperty().bind(rotuloEstoqueInicial.visibleProperty());
        campoEstoqueInicial.managedProperty().bind(campoEstoqueInicial.visibleProperty());
        rotuloEstoqueInicial.visibleProperty().bind(campoEstoqueInicial.visibleProperty());
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
            campoEstoqueInicial.setText("");
            campoEstoqueInicial.setVisible(true);
            estoqueAtual.setText(Formatos.SEM_VALOR);
            campoPlu.setText("");
            ultimaAlteracao.setText(Formatos.SEM_VALOR);
            listaCodigos.getItems().clear();
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
            estoqueAtual.setText(Formatos.quantidade(produto.estoqueAtual(), produto.unidadeFracionada()) + " "
                    + produto.unidade());
            estoqueAtual.getStyleClass().remove("negativo");
            if (produto.estoqueAtual().signum() < 0) {
                estoqueAtual.getStyleClass().add("negativo");
            }
            campoPlu.setText(produto.plu() == null ? "" : String.valueOf(produto.plu()));
            ultimaAlteracao.setText(Formatos.dataHora(produto.atualizadoEm()) + " por " + produto.atualizadoPorNome());
            listaCodigos.getItems().setAll(produto.codigosBarras());
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
        if (selecionado.principal() && !listaCodigos.getItems().isEmpty()) {
            CodigoBarras primeiro = listaCodigos.getItems().get(0);
            listaCodigos.getItems().set(0, new CodigoBarras(primeiro.codigo(), true));
        }
    }

    @FXML
    private void marcarPrincipal() {
        CodigoBarras selecionado = listaCodigos.getSelectionModel().getSelectedItem();
        if (selecionado == null) {
            return;
        }
        List<CodigoBarras> codigos = new ArrayList<>();
        codigos.add(new CodigoBarras(selecionado.codigo(), true));
        listaCodigos.getItems().stream().filter(c -> !c.codigo().equals(selecionado.codigo()))
                .map(c -> new CodigoBarras(c.codigo(), false)).forEach(codigos::add);
        listaCodigos.getItems().setAll(codigos);
        listaCodigos.getSelectionModel().selectFirst();
    }

    private void atualizarBotoesCodigo() {
        CodigoBarras selecionado = listaCodigos.getSelectionModel().getSelectedItem();
        botaoRemoverCodigo.setDisable(selecionado == null);
        botaoPrincipal.setDisable(selecionado == null || selecionado.principal());
    }

    /** PLU só com balança ativa e unidade fracionada; um PLU já gravado pode ser apagado. */
    private void atualizarPlu() {
        UnidadeMedida unidade = campoUnidade.getValue();
        boolean liberado = balancaAtiva && unidade != null && unidade.fracionada();
        campoPlu.setDisable(!liberado && Textos.vazio(campoPlu.getText()));
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
                produto == null ? ler(campoEstoqueInicial, "Estoque inicial") : null);
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
