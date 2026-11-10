package br.com.varesync.controller;

import br.com.varesync.model.InclusaoInventario;
import br.com.varesync.model.InventarioResumo;
import br.com.varesync.model.ItemContagem;
import br.com.varesync.model.ItemDivergencia;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ResultadoInventario;
import br.com.varesync.model.StatusInventario;
import br.com.varesync.report.ExportadorCsv;
import br.com.varesync.report.RelResultadoInventario;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.InventarioService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.BuscaProdutoDialogo;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosInventario;
import br.com.varesync.ui.Exportacao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Inventário (6.7, Fig. 18 revisada): lista de inventários; aba Contagem — incluir produtos (código, busca, categoria
 * ou todos os ativos) e contar com o leitor, sem mostrar o estoque do sistema (contagem cega); aba Divergências — o
 * resultado do inventário (R13) com sistema, contado, diferença, valor e totais; Aplicar e Cancelar. Aplicado ou
 * cancelado, o inventário fica somente leitura.
 */
public class InventarioController {

    private static final String NEGATIVO = "negativo";
    private static final String NAO_CONTADO = "nao-contado";
    private static final KeyCombination CTRL_N = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);

    private final InventarioService inventarioService;
    private final AcessoService acessoService;
    private final RelatorioPdf relatorioPdf;
    private final Sessao sessao;
    /** Inventário mostrado embaixo; nulo sem seleção. */
    private InventarioResumo inventario;
    /** Item que recebe a próxima contagem; nulo sem escolha. */
    private ItemContagem itemAtual;

    @FXML private VBox raiz;
    @FXML private CheckBox filtroEncerrados;
    @FXML private TableView<InventarioResumo> tabelaInventarios;
    @FXML private TableColumn<InventarioResumo, Long> colunaNumero;
    @FXML private TableColumn<InventarioResumo, String> colunaDescricao;
    @FXML private TableColumn<InventarioResumo, String> colunaSituacao;
    @FXML private TableColumn<InventarioResumo, String> colunaCriadoEm;
    @FXML private TableColumn<InventarioResumo, String> colunaCriadoPor;
    @FXML private TableColumn<InventarioResumo, Integer> colunaItens;
    @FXML private TableColumn<InventarioResumo, Integer> colunaContados;
    @FXML private TableColumn<InventarioResumo, String> colunaAplicado;
    @FXML private Label tituloInventario;
    @FXML private Label situacao;
    @FXML private Button botaoAplicar;
    @FXML private Button botaoCancelar;
    @FXML private Label orientacao;
    @FXML private TabPane abas;
    @FXML private Tab abaContagem;
    @FXML private Tab abaDivergencias;
    @FXML private HBox painelInclusao;
    @FXML private TextField campoInclusao;
    @FXML private Button botaoRemover;
    @FXML private HBox painelContagem;
    @FXML private TextField campoLeitura;
    @FXML private Label produtoAtual;
    @FXML private TextField campoQuantidade;
    @FXML private Button botaoGravar;
    @FXML private TableView<ItemContagem> tabelaContagem;
    @FXML private Label semItens;
    @FXML private TableColumn<ItemContagem, Long> colunaContagemCodigo;
    @FXML private TableColumn<ItemContagem, String> colunaContagemBarras;
    @FXML private TableColumn<ItemContagem, String> colunaContagemProduto;
    @FXML private TableColumn<ItemContagem, String> colunaContagemUnidade;
    @FXML private TableColumn<ItemContagem, ItemContagem> colunaContagemContado;
    @FXML private TableColumn<ItemContagem, String> colunaContagemPor;
    @FXML private TableColumn<ItemContagem, String> colunaContagemEm;
    @FXML private Label resumoContagem;
    @FXML private CheckBox filtroSoDiferencas;
    @FXML private Button botaoR13Csv;
    @FXML private Button botaoR13Pdf;
    @FXML private TableView<ItemDivergencia> tabelaDivergencias;
    @FXML private TableColumn<ItemDivergencia, Long> colunaDivCodigo;
    @FXML private TableColumn<ItemDivergencia, String> colunaDivProduto;
    @FXML private TableColumn<ItemDivergencia, String> colunaDivUnidade;
    @FXML private TableColumn<ItemDivergencia, ItemDivergencia> colunaDivSistema;
    @FXML private TableColumn<ItemDivergencia, ItemDivergencia> colunaDivContado;
    @FXML private TableColumn<ItemDivergencia, ItemDivergencia> colunaDivDiferenca;
    @FXML private TableColumn<ItemDivergencia, String> colunaDivCusto;
    @FXML private TableColumn<ItemDivergencia, BigDecimal> colunaDivValor;
    @FXML private Label totalSobra;
    @FXML private Label totalFalta;
    @FXML private Label totalResultado;
    @FXML private Label totalNaoContados;
    @FXML private Label detalheSobra;
    @FXML private Label detalheFalta;
    @FXML private Label detalheResultado;
    @FXML private Label detalheNaoContados;
    @FXML private Label mensagem;

    public InventarioController(InventarioService inventarioService, AcessoService acessoService,
                                RelatorioPdf relatorioPdf, Sessao sessao) {
        this.inventarioService = inventarioService;
        this.acessoService = acessoService;
        this.relatorioPdf = relatorioPdf;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaNumero.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().id()));
        colunaDescricao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().descricao()));
        colunaSituacao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().status().rotulo()));
        colunaCriadoEm.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.dataHora(c.getValue().criadoEm())));
        colunaCriadoEm.setComparator(Comparator.comparing(InventarioController::dataOrdenavel));
        colunaCriadoPor.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().criadoPorNome()));
        colunaItens.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().totalItens()));
        colunaContados.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().itensContados()));
        colunaAplicado.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().aplicadoEm() == null ? ""
                : Formatos.dataHora(c.getValue().aplicadoEm()) + " — " + c.getValue().aplicadoPorNome()));
        tabelaInventarios.getSelectionModel().selectedItemProperty().addListener((obs, anterior, selecionado) -> {
            if (selecionado == null || inventario == null || selecionado.id() != inventario.id()) {
                mostrar(selecionado);
            }
        });

        colunaContagemCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        colunaContagemBarras.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().codigoBarras() == null ? "" : c.getValue().codigoBarras()));
        colunaContagemProduto.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome()));
        colunaContagemUnidade.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().unidade()));
        colunaContagemContado.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        colunaContagemContado.setComparator(Comparator.comparing(ItemContagem::quantidadeContada,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        colunaContagemContado.setCellFactory(col -> celula(i -> i.contado()
                ? Formatos.quantidade(i.quantidadeContada(), i.unidadeFracionada()) : Formatos.SEM_VALOR, i -> false));
        colunaContagemPor.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().contadoPorNome() == null ? "" : c.getValue().contadoPorNome()));
        colunaContagemEm.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().contadoEm() == null ? "" : Formatos.dataHora(c.getValue().contadoEm())));
        tabelaContagem.setRowFactory(t -> linhaNaoContada(ItemContagem::contado));
        tabelaContagem.getSelectionModel().selectedItemProperty().addListener((obs, anterior, item) -> escolher(item));
        tabelaContagem.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && itemAtual != null) {
                focarQuantidade();
            }
        });
        tabelaContagem.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && itemAtual != null) {
                e.consume();
                focarQuantidade();
            }
        });

        colunaDivCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        colunaDivProduto.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome()));
        colunaDivUnidade.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().unidade()));
        colunaDivSistema.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        colunaDivSistema.setComparator(porQuantidade(ItemDivergencia::estoqueSistema));
        colunaDivSistema.setCellFactory(col -> celula(i -> quantidadeOuTraco(i, i.estoqueSistema()),
                i -> i.contado() && i.estoqueSistema().signum() < 0));
        colunaDivContado.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        colunaDivContado.setComparator(porQuantidade(ItemDivergencia::quantidadeContada));
        colunaDivContado.setCellFactory(col -> celula(i -> i.contado()
                ? Formatos.quantidade(i.quantidadeContada(), i.unidadeFracionada()) : "não contado", i -> false));
        colunaDivDiferenca.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        colunaDivDiferenca.setComparator(porQuantidade(ItemDivergencia::diferenca));
        colunaDivDiferenca.setCellFactory(col -> celula(InventarioController::diferenca,
                i -> i.contado() && i.diferenca().signum() < 0));
        colunaDivCusto.setCellValueFactory(c -> new ReadOnlyStringWrapper(custo(c.getValue().custoUnitario())));
        colunaDivValor.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().valorDiferenca()));
        colunaDivValor.setComparator(Comparator.nullsFirst(Comparator.naturalOrder()));
        colunaDivValor.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal valor, boolean vazio) {
                super.updateItem(valor, vazio);
                getStyleClass().remove(NEGATIVO);
                setText(vazio ? null : valor == null ? Formatos.SEM_VALOR : Formatos.moeda(valor));
                if (!vazio && valor != null && valor.signum() < 0) {
                    getStyleClass().add(NEGATIVO);
                }
            }
        });
        tabelaDivergencias.setRowFactory(t -> linhaNaoContada(ItemDivergencia::contado));
        abas.getSelectionModel().selectedItemProperty().addListener((obs, anterior, aba) -> {
            if (aba == abaDivergencias) {
                carregarDivergencias();
            }
        });

        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (CTRL_N.match(e)) {
                e.consume();
                novo();
            }
        });

        mostrar(null);
        pesquisar();
        Platform.runLater(() -> tabelaInventarios.requestFocus());
    }

    // ----- Lista -----

    @FXML
    private void pesquisar() {
        Long selecionado = inventario == null ? null : inventario.id();
        tabelaInventarios.getItems().setAll(inventarioService.listar(sessao, filtroEncerrados.isSelected()));
        Optional<InventarioResumo> mesmo = selecionado == null ? Optional.empty()
                : tabelaInventarios.getItems().stream().filter(i -> i.id() == selecionado).findFirst();
        if (mesmo.isPresent()) {
            inventario = null; // força recarregar os detalhes (contados, situação)
            tabelaInventarios.getSelectionModel().select(mesmo.get());
        } else if (!tabelaInventarios.getItems().isEmpty()) {
            tabelaInventarios.getSelectionModel().selectFirst();
        } else {
            tabelaInventarios.getSelectionModel().clearSelection();
            mostrar(null);
        }
    }

    @FXML
    private void novo() {
        DialogosInventario.novo(descricao -> inventarioService.criar(sessao, descricao)).ifPresent(id -> {
            filtroEncerrados.setSelected(false);
            inventario = null;
            tabelaInventarios.getItems().setAll(inventarioService.listar(sessao, false));
            tabelaInventarios.getItems().stream().filter(i -> i.id() == id).findFirst()
                    .ifPresent(i -> tabelaInventarios.getSelectionModel().select(i));
            abas.getSelectionModel().select(abaContagem);
            Mensagem.sucesso(mensagem, "Inventário nº " + id + " criado. Inclua os produtos a contar.");
            Platform.runLater(campoInclusao::requestFocus);
        });
    }

    /** Preenche o cabeçalho e as abas (nulo = sem seleção). */
    private void mostrar(InventarioResumo selecionado) {
        inventario = selecionado;
        Mensagem.limpar(mensagem);
        itemAtual = null;
        campoInclusao.clear();
        campoLeitura.clear();
        campoQuantidade.clear();
        boolean aberto = selecionado != null && selecionado.aberto();
        painelInclusao.setDisable(!aberto);
        painelContagem.setDisable(!aberto);
        botaoAplicar.setDisable(!aberto);
        botaoCancelar.setDisable(!aberto);
        botaoR13Csv.setDisable(selecionado == null);
        botaoR13Pdf.setDisable(selecionado == null);
        orientacao.setVisible(aberto);
        orientacao.setManaged(aberto);
        if (selecionado == null) {
            tituloInventario.setText("Selecione um inventário");
            situacao.setText("");
            tabelaContagem.getItems().clear();
            tabelaDivergencias.getItems().clear();
            semItens.setText("Selecione ou crie um inventário.");
            resumoContagem.setText("");
            limparTotais();
            escolher(null);
            return;
        }
        tituloInventario.setText(selecionado.titulo());
        situacao.setText(switch (selecionado.status()) {
            case ABERTO -> "Aberto — criado por " + selecionado.criadoPorNome() + " em "
                    + Formatos.dataHora(selecionado.criadoEm());
            case APLICADO -> "Aplicado por " + selecionado.aplicadoPorNome() + " em "
                    + Formatos.dataHora(selecionado.aplicadoEm()) + " — somente leitura";
            case CANCELADO -> "Cancelado — somente leitura";
        });
        semItens.setText(aberto ? "Nenhum produto neste inventário. Inclua pelo código, pela busca, por categoria "
                + "ou todos os ativos." : "Nenhum produto neste inventário.");
        carregarContagem(null);
        if (abas.getSelectionModel().getSelectedItem() == abaDivergencias) {
            carregarDivergencias();
        }
    }

    /** Recarrega a linha do inventário na lista (contados) sem trocar a seleção nem os detalhes. */
    private void atualizarLinha() {
        InventarioResumo atual = inventarioService.buscar(sessao, inventario.id());
        int indice = tabelaInventarios.getItems().indexOf(inventario);
        inventario = atual;
        if (indice >= 0) {
            tabelaInventarios.getItems().set(indice, atual);
            tabelaInventarios.getSelectionModel().select(indice);
        }
    }

    // ----- Inclusão -----

    /** Vazio ou texto → busca; só dígitos → código de barras ou código interno. */
    @FXML
    private void incluir() {
        if (inventario == null || !inventario.aberto()) {
            return;
        }
        long id = inventario.id();
        String texto = Textos.normalizarEspacos(campoInclusao.getText());
        if (!texto.isEmpty() && Textos.soDigitos(texto)) {
            executarInclusao(() -> inventarioService.incluirPorCodigo(sessao, id, texto));
            return;
        }
        Optional<ProdutoResumo> escolhido = BuscaProdutoDialogo.buscar("Incluir produto no inventário",
                "Incluir", false, texto, termo -> inventarioService.pesquisarProdutos(sessao, termo));
        escolhido.ifPresent(p -> executarInclusao(() -> inventarioService.incluirProduto(sessao, id, p.id())));
    }

    @FXML
    private void incluirCategoria() {
        if (inventario == null || !inventario.aberto()) {
            return;
        }
        long id = inventario.id();
        DialogosInventario.incluirCategoria(inventarioService.categorias(sessao),
                        categoria -> inventarioService.incluirCategoria(sessao, id, categoria.id()))
                .ifPresent(this::incluido);
    }

    @FXML
    private void incluirTodos() {
        if (inventario == null || !inventario.aberto()) {
            return;
        }
        if (Dialogos.confirmar("Incluir no inventário nº " + inventario.id() + " todos os produtos ativos que "
                + "ainda não estão nele nem em outro inventário aberto?")) {
            long id = inventario.id();
            executarInclusao(() -> inventarioService.incluirTodosAtivos(sessao, id));
        }
    }

    private void executarInclusao(Supplier<InclusaoInventario> inclusao) {
        try {
            incluido(inclusao.get());
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            campoInclusao.selectAll();
            campoInclusao.requestFocus();
        }
    }

    private void incluido(InclusaoInventario resultado) {
        campoInclusao.clear();
        atualizarLinha();
        carregarContagem(null);
        if (resultado.emOutroInventario().isEmpty()) {
            Mensagem.sucesso(mensagem, resultado.mensagem());
        } else {
            Mensagem.erro(mensagem, resultado.mensagem());
        }
        campoInclusao.requestFocus();
    }

    @FXML
    private void removerItem() {
        ItemContagem item = tabelaContagem.getSelectionModel().getSelectedItem();
        if (inventario == null || !inventario.aberto()) {
            return;
        }
        if (item == null) {
            Mensagem.erro(mensagem, "Selecione na lista o item a remover.");
            return;
        }
        String pergunta = "Remover " + item.nome() + " do inventário nº " + inventario.id() + "?"
                + (item.contado() ? " A contagem dele será descartada." : "");
        if (!Dialogos.confirmar(pergunta)) {
            return;
        }
        try {
            inventarioService.removerItem(sessao, inventario.id(), item.itemId());
            atualizarLinha();
            carregarContagem(null);
            Mensagem.sucesso(mensagem, item.nome() + " removido do inventário.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- Contagem (cega) -----

    /** Recarrega a aba Contagem e volta a selecionar o item {@code manterItemId} (se houver). */
    private void carregarContagem(Long manterItemId) {
        tabelaContagem.getItems().setAll(inventarioService.itensContagem(sessao, inventario.id()));
        atualizarResumoContagem();
        Optional<ItemContagem> mesmo = manterItemId == null ? Optional.empty()
                : tabelaContagem.getItems().stream().filter(i -> i.itemId() == manterItemId).findFirst();
        if (mesmo.isPresent()) {
            tabelaContagem.getSelectionModel().select(mesmo.get());
            tabelaContagem.scrollTo(mesmo.get());
        } else {
            tabelaContagem.getSelectionModel().clearSelection();
            escolher(null);
        }
    }

    /** "5.000 itens · 1 contado · 4.999 a contar", pelos itens já na tabela. */
    private void atualizarResumoContagem() {
        long contados = tabelaContagem.getItems().stream().filter(ItemContagem::contado).count();
        int total = tabelaContagem.getItems().size();
        resumoContagem.setText(total == 0 ? "" : (total == 1 ? "1 item" : total + " itens") + " · " + contados
                + (contados == 1 ? " contado" : " contados") + " · " + (total - contados)
                + (inventario.aberto() ? " a contar" : total - contados == 1 ? " não contado" : " não contados"));
    }

    /**
     * Troca só a linha do item gravado (F10.8): com milhares de itens, recarregar a aba inteira a cada contagem
     * pesaria. Se o item não estiver mais na tabela (removido em outro computador), recarrega tudo.
     */
    private void atualizarItemContagem(ItemContagem gravado) {
        List<ItemContagem> itens = tabelaContagem.getItems();
        int indice = -1;
        for (int i = 0; i < itens.size(); i++) {
            if (itens.get(i).itemId() == gravado.itemId()) {
                indice = i;
                break;
            }
        }
        if (indice < 0) {
            carregarContagem(gravado.itemId());
            return;
        }
        itens.set(indice, gravado);
        atualizarResumoContagem();
        tabelaContagem.getSelectionModel().clearAndSelect(indice);
        tabelaContagem.scrollTo(indice);
    }

    /**
     * Leitor: código de barras ou código interno posiciona no item; texto procura o primeiro item cujo nome contém o
     * termo (sem acento nem maiúsculas). Depois o foco vai para a quantidade.
     */
    @FXML
    private void localizar() {
        if (inventario == null || !inventario.aberto()) {
            return;
        }
        String texto = Textos.normalizarEspacos(campoLeitura.getText());
        if (texto.isEmpty()) {
            return;
        }
        Optional<ItemContagem> encontrado;
        String aviso = null;
        if (Textos.soDigitos(texto)) {
            try {
                InventarioService.LeituraContagem leitura = inventarioService.localizarLeitura(sessao, inventario.id(),
                        texto);
                aviso = leitura.aviso(); // F20.3: código de embalagem só posiciona; a contagem é em unidades
                encontrado = tabelaContagem.getItems().stream()
                        .filter(i -> i.itemId() == leitura.item().itemId()).findFirst();
            } catch (NegocioException e) {
                recusarLeitura(e.getMessage());
                return;
            }
        } else {
            String termo = Textos.normalizarBusca(texto);
            encontrado = tabelaContagem.getItems().stream()
                    .filter(i -> Textos.normalizarBusca(i.nome()).contains(termo)).findFirst();
            if (encontrado.isEmpty()) {
                recusarLeitura("Nenhum item deste inventário tem \"" + texto + "\" no nome.");
                return;
            }
        }
        String avisoEmbalagem = aviso;
        encontrado.ifPresent(item -> {
            tabelaContagem.getSelectionModel().select(item);
            tabelaContagem.scrollTo(item);
            if (avisoEmbalagem == null) {
                Mensagem.limpar(mensagem);
            } else {
                Mensagem.informar(mensagem, avisoEmbalagem);
            }
            focarQuantidade();
        });
    }

    private void recusarLeitura(String texto) {
        Mensagem.erro(mensagem, texto);
        campoLeitura.selectAll();
        campoLeitura.requestFocus();
    }

    /** Item escolhido (leitura ou lista) recebe a próxima contagem. */
    private void escolher(ItemContagem item) {
        itemAtual = item;
        botaoRemover.setDisable(item == null);
        botaoGravar.setDisable(item == null);
        campoQuantidade.setDisable(item == null);
        campoQuantidade.clear();
        if (item == null) {
            produtoAtual.setText("Nenhum item escolhido");
            return;
        }
        produtoAtual.setText(item.nome() + " (" + item.unidade() + ")" + (item.contado()
                ? " — já contado: " + Formatos.quantidade(item.quantidadeContada(), item.unidadeFracionada())
                + "; gravar de novo substitui" : ""));
    }

    private void focarQuantidade() {
        campoQuantidade.requestFocus();
        campoQuantidade.selectAll();
    }

    @FXML
    private void gravarContagem() {
        if (inventario == null || itemAtual == null || !inventario.aberto()) {
            return;
        }
        ItemContagem item = itemAtual;
        try {
            ItemContagem gravado = inventarioService.registrarContagem(sessao, inventario.id(), item.itemId(),
                    CampoNumero.ler(campoQuantidade.getText(), "Quantidade contada"));
            atualizarLinha();
            atualizarItemContagem(gravado);
            Mensagem.sucesso(mensagem, "Contagem gravada: " + gravado.nome() + " = "
                    + Formatos.quantidade(gravado.quantidadeContada(), gravado.unidadeFracionada()) + " "
                    + gravado.unidade() + ".");
            campoLeitura.clear();
            campoLeitura.requestFocus();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            focarQuantidade();
        }
    }

    // ----- Divergências (R13) -----

    @FXML
    private void carregarDivergencias() {
        if (inventario == null) {
            tabelaDivergencias.getItems().clear();
            limparTotais();
            return;
        }
        ResultadoInventario resultado = inventarioService.resultado(sessao, inventario.id());
        tabelaDivergencias.getItems().setAll(filtroSoDiferencas.isSelected()
                ? resultado.itens().stream().filter(ItemDivergencia::temDiferenca).toList()
                : resultado.itens());
        mostrarTotais(resultado);
    }

    private void mostrarTotais(ResultadoInventario r) {
        totalSobra.setText(Formatos.moeda(r.valorSobra()));
        totalFalta.setText(Formatos.moeda(r.valorFalta()));
        totalFalta.getStyleClass().remove(NEGATIVO);
        if (r.valorFalta().signum() > 0) {
            totalFalta.getStyleClass().add(NEGATIVO);
        }
        totalResultado.setText(Formatos.moeda(r.valorLiquido()));
        totalResultado.getStyleClass().remove(NEGATIVO);
        if (r.valorLiquido().signum() < 0) {
            totalResultado.getStyleClass().add(NEGATIVO);
        }
        totalNaoContados.setText(String.valueOf(r.itensNaoContados()));
        detalheSobra.setText(itens(r.itensComSobra()) + " com sobra");
        detalheFalta.setText(itens(r.itensComFalta()) + " com falta");
        detalheResultado.setText(r.itensContados() + " de " + itens(r.itens().size()) + " contados");
        detalheNaoContados.setText(r.inventario().aberto() ? "serão ignorados ao aplicar"
                : r.inventario().status() == StatusInventario.APLICADO
                ? "foram ignorados na aplicação" : "");
    }

    @FXML
    private void exportarR13Csv() {
        escolherEExportarR13("CSV", "csv");
    }

    @FXML
    private void exportarR13Pdf() {
        if (inventario != null && RelatoriosController.confirmarPdfGrande(r13())) {
            escolherEExportarR13("PDF", "pdf");
        }
    }

    private void escolherEExportarR13(String formato, String extensao) {
        if (inventario != null) {
            Exportacao.escolher(raiz.getScene().getWindow(), formato, extensao,
                    "R13_inventario_" + inventario.id() + "." + extensao).ifPresent(d -> exportarR13(formato, d));
        }
    }

    /**
     * R13 em CSV ou PDF (7.4), com todos os itens do inventário, mesmo com "Só itens com diferença" marcado: os
     * totais e os não contados valem para o inventário inteiro. O resultado é lido de novo do banco.
     */
    Task<Void> exportarR13(String formato, Path destino) {
        ResultadoRelatorio<ItemDivergencia> r13 = r13();
        return Exportacao.gravar(formato, destino, "CSV".equals(formato) ? d -> ExportadorCsv.salvar(r13, d)
                : d -> relatorioPdf.salvar(r13, d), mensagem, List.of(botaoR13Csv, botaoR13Pdf));
    }

    private ResultadoRelatorio<ItemDivergencia> r13() {
        return RelResultadoInventario.exportacao(inventarioService.resultado(sessao, inventario.id()),
                acessoService.emissao(sessao));
    }

    private void limparTotais() {
        for (Label rotulo : new Label[] {totalSobra, totalFalta, totalResultado, totalNaoContados}) {
            rotulo.setText(Formatos.SEM_VALOR);
            rotulo.getStyleClass().remove(NEGATIVO);
        }
        for (Label rotulo : new Label[] {detalheSobra, detalheFalta, detalheResultado, detalheNaoContados}) {
            rotulo.setText("");
        }
    }

    // ----- Aplicar e cancelar -----

    /** Confirmação com o resumo das divergências; depois de aplicado, mostra o R13. */
    @FXML
    private void aplicar() {
        if (inventario == null || !inventario.aberto()) {
            return;
        }
        ResultadoInventario r = inventarioService.resultado(sessao, inventario.id());
        if (r.itensContados() == 0) {
            Mensagem.erro(mensagem, "Nenhum item foi contado. Conte ao menos um item antes de aplicar, ou cancele "
                    + "o inventário.");
            return;
        }
        StringBuilder resumo = new StringBuilder("Aplicar o inventário nº ").append(inventario.id()).append(" (")
                .append(inventario.descricao()).append(")?\n\n")
                .append(r.itensContados()).append(" de ").append(itens(r.itens().size())).append(" contados; ")
                .append(r.itensComDiferenca() == 0 ? "nenhum com diferença."
                        : itens(r.itensComDiferenca()) + " com diferença: sobra de " + Formatos.moeda(r.valorSobra())
                        + " (" + itens(r.itensComSobra()) + ") e falta de " + Formatos.moeda(r.valorFalta())
                        + " (" + itens(r.itensComFalta()) + ").")
                .append("\n");
        if (r.itensNaoContados() > 0) {
            resumo.append(itens(r.itensNaoContados())).append(r.itensNaoContados() == 1
                    ? " não contado será ignorado (o saldo dele não muda).\n"
                    : " não contados serão ignorados (o saldo deles não muda).\n");
        }
        resumo.append("\nSerá registrado no estoque um movimento de inventário para cada item com diferença, com a "
                + "diferença capturada na contagem. Depois de aplicado, o inventário não pode mais ser alterado.");
        if (!Dialogos.confirmar(resumo.toString())) {
            return;
        }
        try {
            ResultadoInventario aplicado = inventarioService.aplicar(sessao, inventario.id());
            filtroEncerrados.setSelected(true); // o aplicado continua na lista, com o R13 à vista
            pesquisar();
            abas.getSelectionModel().select(abaDivergencias);
            carregarDivergencias();
            Mensagem.sucesso(mensagem, "Inventário nº " + aplicado.inventario().id() + " aplicado: "
                    + itens(aplicado.itensComDiferenca()) + " com diferença "
                    + (aplicado.itensComDiferenca() == 1 ? "teve o estoque ajustado." : "tiveram o estoque ajustado."));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            pesquisar();
        }
    }

    @FXML
    private void cancelar() {
        if (inventario == null || !inventario.aberto()) {
            return;
        }
        long id = inventario.id();
        if (!Dialogos.confirmar("Cancelar o inventário nº " + id + " (" + inventario.descricao() + ")?\n\n"
                + "O estoque não muda, as contagens ficam só para consulta e o inventário não poderá mais ser "
                + "alterado. Os produtos ficam livres para outro inventário.")) {
            return;
        }
        try {
            inventarioService.cancelar(sessao, id);
            filtroEncerrados.setSelected(true);
            pesquisar();
            Mensagem.sucesso(mensagem, "Inventário nº " + id + " cancelado.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            pesquisar();
        }
    }

    // ----- Formatação -----

    private static String itens(long quantidade) {
        return quantidade == 1 ? "1 item" : quantidade + " itens";
    }

    private static String quantidadeOuTraco(ItemDivergencia i, BigDecimal valor) {
        return valor == null ? Formatos.SEM_VALOR : Formatos.quantidade(valor, i.unidadeFracionada());
    }

    /** "+2", "−3" ou "0"; não contado → "—". */
    private static String diferenca(ItemDivergencia i) {
        if (!i.contado()) {
            return Formatos.SEM_VALOR;
        }
        String numero = Formatos.quantidade(i.diferenca().abs(), i.unidadeFracionada());
        return switch (i.diferenca().signum()) {
            case 1 -> "+" + numero;
            case -1 -> "−" + numero;
            default -> numero;
        };
    }

    private static String custo(BigDecimal valor) {
        return valor == null ? Formatos.SEM_VALOR : "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }

    private static String dataOrdenavel(String dataHora) {
        // dd/MM/aaaa HH:mm → aaaaMMdd HH:mm
        return dataHora.length() < 16 ? dataHora
                : dataHora.substring(6, 10) + dataHora.substring(3, 5) + dataHora.substring(0, 2) + dataHora.substring(10);
    }

    private static <T> Comparator<T> porQuantidade(Function<T, BigDecimal> valor) {
        return Comparator.comparing(valor, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /** Célula de texto calculado a partir da linha, em vermelho quando {@code negativo}. */
    private static <T> TableCell<T, T> celula(Function<T, String> texto, Predicate<T> negativo) {
        return new TableCell<>() {
            @Override
            protected void updateItem(T linha, boolean vazio) {
                super.updateItem(linha, vazio);
                getStyleClass().remove(NEGATIVO);
                if (vazio || linha == null) {
                    setText(null);
                    return;
                }
                setText(texto.apply(linha));
                if (negativo.test(linha)) {
                    getStyleClass().add(NEGATIVO);
                }
            }
        };
    }

    private static <T> TableRow<T> linhaNaoContada(Predicate<T> contado) {
        return new TableRow<>() {
            @Override
            protected void updateItem(T linha, boolean vazio) {
                super.updateItem(linha, vazio);
                getStyleClass().remove(NAO_CONTADO);
                if (!vazio && linha != null && !contado.test(linha)) {
                    getStyleClass().add(NAO_CONTADO);
                }
            }
        };
    }
}
