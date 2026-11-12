package br.com.varesync.controller;

import br.com.varesync.model.ItemPedido;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.StatusPedido;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.RelPedidoCompra;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.service.EntradaService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.PedidoCompraService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.BuscaProdutoDialogo;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosPedido;
import br.com.varesync.ui.Exportacao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import br.com.varesync.ui.Atalhos;
import br.com.varesync.ui.Atalhos.Tecla;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Compras, aba Pedidos (F16.3): lista por fornecedor e situação, dados do pedido, itens do rascunho (por código de
 * barras, código ou busca; quantidade na unidade de venda e custo previsto), enviar, PDF para o fornecedor (com a
 * quantidade também na unidade de compra) e cancelar. Fora do rascunho, fica somente leitura.
 */
public class PedidosController {

    /** Situações do filtro: o rótulo e o que entra. */
    record FiltroSituacao(String rotulo, Set<StatusPedido> situacoes) {
        @Override
        public String toString() {
            return rotulo;
        }
    }

    static final FiltroSituacao EM_ANDAMENTO = new FiltroSituacao("Em andamento",
            EnumSet.of(StatusPedido.RASCUNHO, StatusPedido.ENVIADO, StatusPedido.PARCIAL));
    static final FiltroSituacao TODAS = new FiltroSituacao("Todas", EnumSet.noneOf(StatusPedido.class));
    private static final Opcao TODOS = new Opcao(0, "Todos");

    private final PedidoCompraService pedidoService;
    private final EntradaService entradaService;
    private final RelatorioPdf relatorioPdf;
    private final Sessao sessao;
    /** Pedido mostrado nos detalhes; nulo sem seleção. */
    private PedidoCompra pedido;
    /** Produto escolhido para incluir; nulo quando se altera um item. */
    private ProdutoVenda produto;
    /** Item em alteração; nulo ao incluir. */
    private ItemPedido itemEmEdicao;

    @FXML private VBox raiz;
    @FXML private ComboBox<Opcao> filtroFornecedor;
    @FXML private ComboBox<FiltroSituacao> filtroSituacao;
    @FXML private TableView<PedidoCompra> tabelaPedidos;
    @FXML private Label semPedidos;
    @FXML private TableColumn<PedidoCompra, Long> colunaNumero;
    @FXML private TableColumn<PedidoCompra, String> colunaFornecedor;
    @FXML private TableColumn<PedidoCompra, String> colunaSituacao;
    @FXML private TableColumn<PedidoCompra, String> colunaCriado;
    @FXML private TableColumn<PedidoCompra, String> colunaEnviado;
    @FXML private TableColumn<PedidoCompra, String> colunaPrevisao;
    @FXML private TableColumn<PedidoCompra, Integer> colunaItens;
    @FXML private TableColumn<PedidoCompra, String> colunaValor;
    @FXML private TableColumn<PedidoCompra, String> colunaPendente;
    @FXML private Label tituloPedido;
    @FXML private Label situacao;
    @FXML private Button botaoDados;
    @FXML private Button botaoPdf;
    @FXML private Button botaoCancelar;
    @FXML private Button botaoEnviar;
    @FXML private FlowPane painelItem;
    @FXML private TextField campoProduto;
    @FXML private Label produtoEscolhido;
    @FXML private TextField campoQuantidade;
    @FXML private TextField campoCusto;
    @FXML private Button botaoGravarItem;
    @FXML private Button botaoRemover;
    @FXML private TableView<ItemPedido> tabelaItens;
    @FXML private Label semItens;
    @FXML private TableColumn<ItemPedido, Long> colunaItemCodigo;
    @FXML private TableColumn<ItemPedido, String> colunaItemFornecedor;
    @FXML private TableColumn<ItemPedido, String> colunaItemProduto;
    @FXML private TableColumn<ItemPedido, String> colunaItemQuantidade;
    @FXML private TableColumn<ItemPedido, String> colunaItemCompra;
    @FXML private TableColumn<ItemPedido, String> colunaItemCusto;
    @FXML private TableColumn<ItemPedido, String> colunaItemValor;
    @FXML private TableColumn<ItemPedido, String> colunaItemRecebida;
    @FXML private TableColumn<ItemPedido, String> colunaItemPendente;
    @FXML private Label totalPedido;
    @FXML private Label entradas;
    @FXML private Label mensagem;

    public PedidosController(PedidoCompraService pedidoService, EntradaService entradaService,
                             RelatorioPdf relatorioPdf, Sessao sessao) {
        this.pedidoService = pedidoService;
        this.entradaService = entradaService;
        this.relatorioPdf = relatorioPdf;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        colunaNumero.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().id()));
        texto(colunaFornecedor, p -> p.fornecedorNome() + (p.fornecedorAtivo() ? "" : " (inativo)"));
        texto(colunaSituacao, p -> p.status().rotulo());
        texto(colunaCriado, p -> Formatos.dataHora(p.criadoEm()));
        texto(colunaEnviado, p -> p.enviadoEm() == null ? "" : Formatos.dataHora(p.enviadoEm()));
        texto(colunaPrevisao, p -> p.previsaoEntrega() == null ? "" : Formatos.data(p.previsaoEntrega()));
        colunaItens.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().totalItens()));
        texto(colunaValor, p -> Formatos.moeda(p.valorPrevisto()));
        texto(colunaPendente, p -> p.status().emAberto() ? Formatos.quantidade(p.quantidadePendente(),
                p.quantidadePendente().stripTrailingZeros().scale() > 0) : "");
        tabelaPedidos.getSelectionModel().selectedItemProperty().addListener((obs, anterior, selecionado) -> {
            if (selecionado == null || pedido == null || selecionado.id() != pedido.id()) {
                mostrar(selecionado);
            }
        });

        colunaItemCodigo.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().produtoId()));
        texto(colunaItemFornecedor, i -> i.codigoNoFornecedor() == null ? "" : i.codigoNoFornecedor());
        texto(colunaItemProduto, ItemPedido::produtoNome);
        texto(colunaItemQuantidade, i -> Formatos.quantidade(i.quantidade(), i.fracionada()) + " " + i.unidade());
        texto(colunaItemCompra, RelPedidoCompra::naUnidadeDeCompra);
        texto(colunaItemCusto, i -> custo(i.custoPrevisto()));
        texto(colunaItemValor, i -> Formatos.moeda(i.valor()));
        texto(colunaItemRecebida, i -> Formatos.quantidade(i.quantidadeRecebida(), i.fracionada()));
        texto(colunaItemPendente, i -> pedido != null && pedido.status().emAberto()
                ? Formatos.quantidade(i.pendente(), i.fracionada()) : "");
        tabelaItens.getSelectionModel().selectedItemProperty().addListener((obs, anterior, item) -> {
            if (item != null && pedido != null && pedido.rascunho()) {
                editarItem(item);
            }
            botaoRemover.setDisable(item == null || pedido == null || !pedido.rascunho());
        });
        campoQuantidade.setPromptText("0");
        campoCusto.setPromptText("0,0000");

        filtroFornecedor.getItems().add(TODOS);
        filtroFornecedor.getItems().addAll(pedidoService.fornecedores(sessao, null).stream()
                .map(f -> new Opcao(f.id(), f.razaoSocial())).toList());
        filtroFornecedor.setValue(TODOS);
        filtroSituacao.getItems().add(EM_ANDAMENTO);
        for (StatusPedido s : StatusPedido.values()) {
            filtroSituacao.getItems().add(new FiltroSituacao(s.rotulo(), EnumSet.of(s)));
        }
        filtroSituacao.getItems().add(TODAS);
        filtroSituacao.setValue(EM_ANDAMENTO);

        Atalhos.de(raiz).em(Tecla.ctrl(KeyCode.N), "Novo pedido de compra (aba Pedidos)", this::novo)
                .curto("novo pedido");
        mostrar(null);
        pesquisar();
    }

    private static <T> void texto(TableColumn<T, String> coluna, Function<T, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    // ----- Lista -----

    @FXML
    void pesquisar() {
        Long selecionado = pedido == null ? null : pedido.id();
        recarregar(selecionado);
    }

    /** Recarrega a lista e seleciona o pedido {@code id} (ou o primeiro). */
    private void recarregar(Long id) {
        Opcao fornecedor = filtroFornecedor.getValue();
        FiltroSituacao filtro = filtroSituacao.getValue() == null ? EM_ANDAMENTO : filtroSituacao.getValue();
        tabelaPedidos.getItems().setAll(pedidoService.listar(sessao, filtro.situacoes(),
                fornecedor == null || fornecedor.id() == 0 ? null : fornecedor.id()));
        Optional<PedidoCompra> mesmo = id == null ? Optional.empty()
                : tabelaPedidos.getItems().stream().filter(p -> p.id() == id).findFirst();
        pedido = null; // força recarregar os detalhes
        if (mesmo.isPresent()) {
            tabelaPedidos.getSelectionModel().select(mesmo.get());
            tabelaPedidos.scrollTo(mesmo.get());
        } else if (!tabelaPedidos.getItems().isEmpty()) {
            tabelaPedidos.getSelectionModel().selectFirst();
        } else {
            tabelaPedidos.getSelectionModel().clearSelection();
            mostrar(null);
        }
    }

    /**
     * Mostra os pedidos recém-criados pela Sugestão de compra: filtro "Em andamento", todos os fornecedores, e o
     * primeiro deles selecionado.
     */
    public void mostrarCriados(List<PedidoCompra> criados, String aviso) {
        filtroFornecedor.setValue(TODOS);
        filtroSituacao.setValue(EM_ANDAMENTO);
        recarregar(criados.isEmpty() ? null : criados.get(0).id());
        String numeros = criados.stream().map(p -> "nº " + p.id()).collect(Collectors.joining(", "));
        String texto = (criados.size() == 1 ? "Pedido " + numeros + " criado" : "Pedidos " + numeros + " criados")
                + " como rascunho pela sugestão de compra. Confira os itens e envie.";
        if (aviso == null || aviso.isBlank()) {
            Mensagem.sucesso(mensagem, texto);
        } else {
            Mensagem.erro(mensagem, texto + " " + aviso);
        }
    }

    @FXML
    private void novo() {
        DialogosPedido.dados(pedidoService.fornecedores(sessao, null), null,
                dados -> pedidoService.criar(sessao, dados)).ifPresent(id -> {
                    filtroSituacao.setValue(EM_ANDAMENTO);
                    recarregar(id);
                    Mensagem.sucesso(mensagem, "Pedido nº " + id + " criado como rascunho. Inclua os itens.");
                    campoProduto.requestFocus();
                });
    }

    @FXML
    private void editarDados() {
        if (pedido == null || !pedido.rascunho()) {
            return;
        }
        long id = pedido.id();
        DialogosPedido.dados(pedidoService.fornecedores(sessao, pedido.fornecedorId()), pedido, dados -> {
            pedidoService.alterarDados(sessao, id, dados);
            return id;
        }).ifPresent(gravado -> {
            recarregar(id);
            Mensagem.sucesso(mensagem, "Dados do pedido salvos.");
        });
    }

    @FXML
    private void enviar() {
        if (pedido == null || !pedido.rascunho()) {
            return;
        }
        PedidoCompra p = pedidoService.buscar(sessao, pedido.id());
        if (p.totalItens() == 0) {
            Mensagem.erro(mensagem, "O pedido não tem itens. Inclua os produtos antes de enviá-lo.");
            return;
        }
        if (!Dialogos.confirmar("Enviar o pedido nº " + p.id() + " (" + p.fornecedorNome() + ") com "
                + itens(p.totalItens()) + ", " + Formatos.moeda(p.valorPrevisto()) + "?\n\nDepois de enviado, o "
                + "pedido não muda mais: o que falta receber passa a contar na Sugestão de compra, e as entradas da "
                + "nota podem ser vinculadas a ele. Gere o PDF para mandar ao fornecedor.")) {
            return;
        }
        try {
            pedidoService.enviar(sessao, p.id());
            recarregar(p.id());
            Mensagem.sucesso(mensagem, "Pedido nº " + p.id() + " enviado. Use \"PDF do pedido…\" para mandá-lo ao "
                    + "fornecedor.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            recarregar(p.id());
        }
    }

    @FXML
    private void cancelar() {
        if (pedido == null || !StatusPedido.CANCELAVEIS.contains(pedido.status())) {
            return;
        }
        PedidoCompra p = pedido;
        String detalhe = p.status() == StatusPedido.PARCIAL
                ? " O que falta receber (" + Formatos.quantidade(p.quantidadePendente(), true) + ") deixa de contar "
                + "como pendente na Sugestão de compra."
                : p.status() == StatusPedido.ENVIADO ? " Ele deixa de contar como pendente na Sugestão de compra." : "";
        if (!Dialogos.confirmar("Cancelar o pedido nº " + p.id() + " (" + p.fornecedorNome() + ")?" + detalhe)) {
            return;
        }
        try {
            pedidoService.cancelar(sessao, p.id());
            recarregar(p.id());
            Mensagem.sucesso(mensagem, "Pedido nº " + p.id() + " cancelado.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            recarregar(p.id());
        }
    }

    @FXML
    private void exportarPdf() {
        if (pedido != null) {
            Exportacao.escolher(raiz.getScene().getWindow(), "PDF", "pdf", "pedido_compra_" + pedido.id() + ".pdf")
                    .ifPresent(this::exportarPdf);
        }
    }

    /** Lê o pedido e grava o PDF em {@link Task}. */
    Task<Void> exportarPdf(Path destino) {
        try {
            ResultadoRelatorio<ItemPedido> dados = pedidoService.exportar(sessao, pedido.id());
            return Exportacao.gravar("PDF", destino, d -> relatorioPdf.salvar(dados, d), mensagem,
                    List.of(botaoPdf));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
            return null;
        }
    }

    /** Preenche o cabeçalho, os itens e o total (nulo = sem seleção). */
    private void mostrar(PedidoCompra selecionado) {
        pedido = selecionado;
        Mensagem.limpar(mensagem);
        limparItem();
        boolean rascunho = selecionado != null && selecionado.rascunho();
        painelItem.setDisable(!rascunho);
        mostrarBotao(botaoDados, rascunho);
        mostrarBotao(botaoEnviar, rascunho);
        mostrarBotao(botaoPdf, selecionado != null);
        mostrarBotao(botaoCancelar, selecionado != null && StatusPedido.CANCELAVEIS.contains(selecionado.status()));
        botaoRemover.setDisable(true);
        if (selecionado == null) {
            tituloPedido.setText("Selecione um pedido");
            situacao.setText("");
            tabelaItens.getItems().clear();
            semItens.setText("Selecione ou crie um pedido.");
            totalPedido.setText("");
            entradas.setText("");
            return;
        }
        tituloPedido.setText(selecionado.titulo());
        situacao.setText(descreverSituacao(selecionado));
        semItens.setText(rascunho ? "Nenhum item. Leia o código de barras ou digite o código ou o nome do produto."
                : "Nenhum item neste pedido.");
        tabelaItens.getItems().setAll(pedidoService.itens(sessao, selecionado.id()));
        mostrarTotal(selecionado);
    }

    private void mostrarTotal(PedidoCompra p) {
        totalPedido.setText("Valor previsto: " + Formatos.moeda(p.valorPrevisto()) + " (" + itens(p.totalItens())
                + ")");
        List<Long> ligadas = pedidoService.entradas(sessao, p.id());
        entradas.setText(ligadas.isEmpty() ? (p.status().emAberto() ? "Nenhuma entrada vinculada ainda" : "")
                : "Entradas vinculadas: " + ligadas.stream().map(id -> "nº " + id).collect(Collectors.joining(", ")));
    }

    private static String descreverSituacao(PedidoCompra p) {
        String texto = p.status().rotulo() + " — criado por " + p.criadoPorNome() + " em "
                + Formatos.dataHora(p.criadoEm());
        if (p.enviadoEm() != null) {
            texto += " · enviado por " + p.enviadoPorNome() + " em " + Formatos.dataHora(p.enviadoEm());
        }
        if (p.canceladoEm() != null) {
            texto += " · cancelado por " + p.canceladoPorNome() + " em " + Formatos.dataHora(p.canceladoEm());
        }
        if (p.previsaoEntrega() != null) {
            boolean atrasado = p.status().emAberto() && p.previsaoEntrega().isBefore(
                    java.time.LocalDate.now(ZoneId.systemDefault()));
            texto += " · previsão " + Formatos.data(p.previsaoEntrega()) + (atrasado ? " (atrasado)" : "");
        }
        if (!p.fornecedorAtivo()) {
            texto += " · fornecedor inativo";
        }
        return p.rascunho() ? texto : texto + " — somente leitura";
    }

    private static void mostrarBotao(Button botao, boolean visivel) {
        botao.setVisible(visivel);
        botao.setManaged(visivel);
    }

    /** Recarrega a linha do pedido (itens, total) sem trocar a seleção. */
    private void atualizarPedido() {
        PedidoCompra atual = pedidoService.buscar(sessao, pedido.id());
        int indice = tabelaPedidos.getItems().indexOf(pedido);
        pedido = atual;
        if (indice >= 0) {
            tabelaPedidos.getItems().set(indice, atual);
            tabelaPedidos.getSelectionModel().select(indice);
        }
        tabelaItens.getItems().setAll(pedidoService.itens(sessao, atual.id()));
        mostrarTotal(atual);
    }

    // ----- Itens do rascunho -----

    /** Só dígitos → código de barras ou código interno; texto (ou vazio) → busca de produto. */
    @FXML
    private void escolherProduto() {
        if (pedido == null || !pedido.rascunho()) {
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
        produtoEscolhido.setText(escolhido.nome() + " (" + escolhido.unidade() + ")");
        campoCusto.setText(Formatos.numero(pedidoService.custoProposto(sessao, pedido.id(), escolhido.id()),
                Dinheiro.ESCALA_CUSTO));
        botaoGravarItem.setText("Incluir");
        Mensagem.limpar(mensagem);
        campoQuantidade.requestFocus();
    }

    private void editarItem(ItemPedido item) {
        itemEmEdicao = item;
        produto = null;
        campoProduto.setText(String.valueOf(item.produtoId()));
        campoProduto.setDisable(true);
        produtoEscolhido.setText(item.produtoNome() + " (" + item.unidade() + ") — alterando o item");
        // sem separador de milhar: "1.234" seria relido como 1,234 (decisão da Fase 2)
        campoQuantidade.setText(item.quantidade().stripTrailingZeros().toPlainString().replace('.', ','));
        campoCusto.setText(Formatos.numero(item.custoPrevisto(), Dinheiro.ESCALA_CUSTO));
        botaoGravarItem.setText("Salvar item");
    }

    @FXML
    private void focarCusto() {
        campoCusto.requestFocus();
        campoCusto.selectAll();
    }

    @FXML
    private void gravarItem() {
        if (pedido == null || !pedido.rascunho()) {
            return;
        }
        try {
            BigDecimal quantidade = CampoNumero.ler(campoQuantidade.getText(), "Quantidade");
            BigDecimal custo = CampoNumero.ler(campoCusto.getText(), "Custo previsto");
            if (custo == null) {
                throw new NegocioException("Informe o custo previsto.");
            }
            String nome;
            if (itemEmEdicao != null) {
                nome = itemEmEdicao.produtoNome();
                pedidoService.alterarItem(sessao, pedido.id(), itemEmEdicao.id(), quantidade, custo);
            } else if (produto != null) {
                nome = produto.nome();
                pedidoService.adicionarItem(sessao, pedido.id(), produto.id(), quantidade, custo);
            } else {
                throw new NegocioException("Escolha o produto: leia o código de barras ou digite o código ou o nome.");
            }
            String feito = nome + (itemEmEdicao == null ? " incluído." : " alterado.");
            atualizarPedido();
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
        campoCusto.clear();
        produtoEscolhido.setText("Nenhum produto");
        botaoGravarItem.setText("Incluir");
        tabelaItens.getSelectionModel().clearSelection();
    }

    @FXML
    private void removerItem() {
        ItemPedido item = tabelaItens.getSelectionModel().getSelectedItem();
        if (pedido == null || !pedido.rascunho() || item == null) {
            return;
        }
        if (!Dialogos.confirmar("Remover " + item.produtoNome() + " do pedido nº " + pedido.id() + "?")) {
            return;
        }
        try {
            pedidoService.removerItem(sessao, pedido.id(), item.id());
            limparItem();
            atualizarPedido();
            Mensagem.sucesso(mensagem, item.produtoNome() + " removido.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- Formatação -----

    private static String itens(long quantidade) {
        return quantidade == 1 ? "1 item" : quantidade + " itens";
    }

    private static String custo(BigDecimal valor) {
        return valor == null ? Formatos.SEM_VALOR : "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }
}
