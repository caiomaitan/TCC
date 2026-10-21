package br.com.varesync.controller;

import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.ResultadoEntrada;
import br.com.varesync.service.EntradaService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.BuscaProdutoDialogo;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCompras;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

/**
 * Compras — entrada de mercadoria (6.10): lista de entradas, dados da nota, itens (por código de barras, código ou
 * busca; custo unitário ou valor total da linha), confirmar (com o aviso de divergência do total e, depois, a
 * sugestão de reajuste) e excluir a entrada aberta. Confirmada, fica somente leitura.
 */
public class ComprasController {

    static final String CUSTO_UNITARIO = "Custo unitário";
    static final String VALOR_DA_LINHA = "Valor total da linha";
    private static final String NEGATIVO = "negativo";
    private static final KeyCombination CTRL_N = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);

    private final EntradaService entradaService;
    private final Sessao sessao;
    private final Callback<Class<?>, Object> fabricaControllers;
    /** Entrada mostrada nos detalhes; nula sem seleção. */
    private EntradaResumo entrada;
    /** Produto escolhido para incluir; nulo quando se altera um item ou nada foi escolhido. */
    private ProdutoVenda produto;
    /** Item em alteração; nulo ao incluir. */
    private ItemEntrada itemEmEdicao;

    @FXML private VBox raiz;
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

    public ComprasController(EntradaService entradaService, Sessao sessao,
                             Callback<Class<?>, Object> fabricaControllers) {
        this.entradaService = entradaService;
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

        modoValor.getItems().setAll(CUSTO_UNITARIO, VALOR_DA_LINHA);
        modoValor.setValue(CUSTO_UNITARIO);
        modoValor.valueProperty().addListener((obs, antes, modo) -> campoValor.setPromptText(
                VALOR_DA_LINHA.equals(modo) ? "0,00" : "0,0000"));
        campoValor.setPromptText("0,0000");
        campoQuantidade.setPromptText("0");

        raiz.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (CTRL_N.match(e)) {
                e.consume();
                nova();
            }
        });
        mostrar(null);
        pesquisar();
        Platform.runLater(() -> tabelaEntradas.requestFocus());
    }

    private static <T> void texto(TableColumn<T, String> coluna, Function<T, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
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
        for (Button botao : List.of(botaoEditarNota, botaoExcluir, botaoConfirmar)) {
            botao.setVisible(aberta);
            botao.setManaged(aberta);
        }
        botaoRemover.setDisable(true);
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
        tituloEntrada.setText(selecionada.titulo());
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
            BigDecimal quantidade = DialogosCompras.ler(campoQuantidade.getText(), "Quantidade");
            BigDecimal valor = DialogosCompras.ler(campoValor.getText(), modoValor.getValue());
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
        StringBuilder texto = new StringBuilder("Confirmar a entrada nº ").append(e.id()).append(" (")
                .append(e.fornecedorNome()).append(e.numeroDocumento() == null ? "" : ", NF " + e.numeroDocumento())
                .append(")?\n\n").append(itens(e.totalItens())).append(", ").append(Formatos.moeda(e.somaItens()))
                .append(".\n");
        e.avisoTotal().ifPresent(aviso -> texto.append("\nATENÇÃO: ").append(aviso).append('\n'));
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
