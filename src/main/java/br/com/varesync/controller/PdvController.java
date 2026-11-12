package br.com.varesync.controller;

import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.ComprovanteImpresso;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.report.FechamentoCaixaPdf;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.Carrinho;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.DevolucaoService;
import br.com.varesync.service.EntradaPdv;
import br.com.varesync.service.ItemCarrinho;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.LimiteDescontoException;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Recebimento;
import br.com.varesync.service.RecebimentoService;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.VendaService;
import br.com.varesync.ui.CampoNumero;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.DialogosCliente;
import br.com.varesync.ui.DialogosPdv;
import br.com.varesync.ui.Janelas;
import br.com.varesync.ui.LiberacaoSupervisorDialogo;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.awt.Toolkit;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import br.com.varesync.ui.Atalhos;
import br.com.varesync.ui.Atalhos.Tecla;
import br.com.varesync.ui.PainelAjuda;
import java.util.List;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * PDV (6.6): leitura do produto (código, quantidade*código, texto → busca), carrinho em memória, descontos com limite
 * e liberação, cancelamento de item, abandono, pagamento (F10) e finalização numa única transação, vendas da sessão
 * (F9), o cliente (F11) e o comprovante. F8 e F12 usam as mesmas operações da tela Caixa. Toda regra está no
 * {@link VendaService} e, para achar e cadastrar o cliente, no {@link ClienteService}.
 */
public class PdvController {

    private static final String ITEM_CANCELADO = "item-cancelado";
    private static final String ENTRADA_ERRO = "entrada-erro";

    private final VendaService vendaService;
    private final ClienteService clienteService;
    private final DevolucaoService devolucaoService;
    private final CaixaService caixaService;
    private final AcessoService acessoService;
    private final ComprovantePdf comprovantePdf;
    private final ImpressaoComprovante impressao;
    private final Sessao sessao;
    private final AcoesCaixa acoesCaixa;
    private final AcoesRecebimento acoesRecebimento;
    private Carrinho carrinho = new Carrinho();
    private BigDecimal quantidadeProxima;
    private Stage janela;
    private PainelAjuda painelAjuda;

    @FXML private BorderPane raiz;
    @FXML private Label loja;
    @FXML private Label caixaInfo;
    @FXML private Label terminalInfo;
    @FXML private Label operadorInfo;
    @FXML private Label cliente;
    @FXML private TableView<ItemCarrinho> tabelaItens;
    @FXML private TableColumn<ItemCarrinho, String> colunaSequencia;
    @FXML private TableColumn<ItemCarrinho, String> colunaCodigo;
    @FXML private TableColumn<ItemCarrinho, String> colunaDescricao;
    @FXML private TableColumn<ItemCarrinho, String> colunaQuantidade;
    @FXML private TableColumn<ItemCarrinho, String> colunaUnitario;
    @FXML private TableColumn<ItemCarrinho, String> colunaDesconto;
    @FXML private TableColumn<ItemCarrinho, String> colunaTotal;
    @FXML private Label ultimoDescricao;
    @FXML private Label ultimoDetalhe;
    @FXML private Label quantidadeItens;
    @FXML private Label subtotal;
    @FXML private Label descontos;
    @FXML private Label total;
    @FXML private Label proximaQuantidade;
    @FXML private Label mensagem;
    @FXML private TextField entrada;

    public PdvController(VendaService vendaService, ClienteService clienteService, DevolucaoService devolucaoService,
                         CaixaService caixaService, AcessoService acessoService, ComprovantePdf comprovantePdf,
                         ImpressaoComprovante impressao, FechamentoCaixaPdf fechamentoPdf,
                         RecebimentoService recebimentoService, Sessao sessao) {
        this.acoesRecebimento = new AcoesRecebimento(recebimentoService, caixaService, acessoService, comprovantePdf,
                impressao, sessao);
        this.vendaService = vendaService;
        this.clienteService = clienteService;
        this.devolucaoService = devolucaoService;
        this.caixaService = caixaService;
        this.acessoService = acessoService;
        this.comprovantePdf = comprovantePdf;
        this.impressao = impressao;
        this.sessao = sessao;
        this.acoesCaixa = new AcoesCaixa(caixaService, acessoService, fechamentoPdf, sessao);
    }

    @FXML
    private void initialize() {
        // Vazios, não ocupam espaço no painel (o total continua visível em 1280×720 com a fonte de 130%, F21.2).
        proximaQuantidade.managedProperty().bind(proximaQuantidade.textProperty().isNotEmpty());
        mensagem.managedProperty().bind(mensagem.textProperty().isNotEmpty());
        colunaSequencia.setCellValueFactory(c -> texto(String.format("%03d", c.getValue().sequencia())));
        colunaCodigo.setCellValueFactory(c -> texto(c.getValue().produto().codigo()));
        colunaDescricao.setCellValueFactory(c -> texto(c.getValue().produto().nome() + promocao(c.getValue())
                + (c.getValue().cancelado() ? "  (cancelado)" : "")));
        colunaQuantidade.setCellValueFactory(c -> texto(quantidade(c.getValue())));
        colunaUnitario.setCellValueFactory(c -> texto(Formatos.moeda(c.getValue().precoUnitario())));
        colunaDesconto.setCellValueFactory(c -> texto(c.getValue().desconto().signum() > 0
                ? "-" + Formatos.moeda(c.getValue().desconto()) : ""));
        colunaTotal.setCellValueFactory(c -> texto(Formatos.moeda(c.getValue().cancelado()
                ? c.getValue().valorBruto() : c.getValue().base())));
        tabelaItens.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(ItemCarrinho item, boolean vazio) {
                super.updateItem(item, vazio);
                getStyleClass().remove(ITEM_CANCELADO);
                if (!vazio && item != null && item.cancelado()) {
                    getStyleClass().add(ITEM_CANCELADO);
                }
            }
        });

        loja.setText(acessoService.nomeEmpresa());
        terminalInfo.setText("Terminal " + sessao.terminal());
        operadorInfo.setText("Operador: " + sessao.usuario().nome());
        atualizarCaixa();

        registrarAtalhos();
        atualizarTela();
    }

    // ----- Teclado (6.6): um registro só, que trata a tecla e dá a lista da ajuda (F21.3) -----

    private void registrarAtalhos() {
        Atalhos.de(raiz)
                .em(Tecla.de(KeyCode.F1), "Ajuda desta tela (abre e fecha)", () -> executar(this::alternarAjuda))
                .curto("ajuda")
                .em(Tecla.de(KeyCode.F2), "Buscar o produto pelo nome", () -> executar(this::buscar))
                .curto("buscar")
                .em(Tecla.de(KeyCode.F3), "Quantidade do próximo item", () -> executar(this::quantidadeDoProximo))
                .curto("quantidade")
                .em(Tecla.de(KeyCode.F4), "Desconto no item selecionado (R$ ou %)",
                        () -> executar(this::descontoNoItem))
                .curto("desconto no item")
                .em(Tecla.de(KeyCode.F5), "Desconto na venda (R$ ou %)", () -> executar(this::descontoNaVenda))
                .curto("desconto na venda")
                .em(Tecla.de(KeyCode.F6), "Cancelar o item selecionado", () -> executar(this::cancelarItem))
                .curto("cancelar item")
                .em(Tecla.de(KeyCode.F7), "Consultar o preço (sem vender)", () -> executar(this::consultarPreco))
                .curto("consultar preço")
                .em(Tecla.de(KeyCode.F8), "Sangria ou suprimento", () -> executar(this::sangriaSuprimento))
                .curto("sangria/suprimento")
                .em(Tecla.de(KeyCode.F9), "Vendas da sessão (comprovante, cancelamento ou devolução)",
                        () -> executar(this::vendasDaSessao))
                .curto("vendas da sessão")
                .em(Tecla.de(KeyCode.F10), "Pagamento e finalização da venda", () -> executar(this::pagar))
                .curto("pagamento")
                .em(Tecla.de(KeyCode.F11), "Identificar o cliente (CPF/CNPJ ou nome)",
                        () -> executar(this::identificarCliente))
                .curto("cliente")
                .em(Tecla.de(KeyCode.F12), "Fechar o caixa", () -> executar(this::fecharCaixa))
                .curto("fechar caixa")
                .em(Tecla.ctrl(KeyCode.R), "Receber o crediário", () -> executar(this::receberCrediario))
                .curto("receber crediário")
                .em(Tecla.de(KeyCode.ESCAPE), "Fechar a ajuda; sem ela, abandonar a venda em andamento (ou sair do "
                        + "PDV, sem venda)", () -> executar(() -> {
                            if (ajudaAberta()) {
                                fecharAjuda();
                            } else {
                                escape();
                            }
                        }))
                .curto("abandonar venda / sair");
        Atalhos.preencherDicas(raiz); // a barra de atalhos sai do registro (F21.5)
        Atalhos.de(entrada)
                .em(Tecla.de(KeyCode.UP), "Selecionar o item de cima (no campo Código)", () -> moverSelecao(-1))
                .em(Tecla.de(KeyCode.DOWN), "Selecionar o item de baixo (no campo Código)", () -> moverSelecao(1));
    }

    /** F1: a ajuda do PDV no painel lateral (F21.3), sem tirar o foco do campo de leitura. */
    private void alternarAjuda() {
        if (ajudaAberta()) {
            fecharAjuda();
            return;
        }
        if (painelAjuda == null) {
            painelAjuda = new PainelAjuda(this::fecharAjuda);
        }
        painelAjuda.mostrar(ItemMenu.PDV.ajuda(), Atalhos.daTela(raiz), List.of());
        raiz.setRight(painelAjuda);
    }

    private boolean ajudaAberta() {
        return painelAjuda != null && raiz.getRight() == painelAjuda;
    }

    private void fecharAjuda() {
        raiz.setRight(null);
        Platform.runLater(entrada::requestFocus);
    }

    /** Chamado pela janela principal antes de mostrar: fechar a janela com venda em andamento pede confirmação. */
    public void preparar(Stage janelaPdv) {
        this.janela = janelaPdv;
        janelaPdv.setOnCloseRequest(e -> {
            if (!podeSair()) {
                e.consume();
            }
        });
        Platform.runLater(entrada::requestFocus);
    }

    /** Executa a ação; erro de regra vira mensagem no painel, o resto vai para o tratador global. O foco volta ao campo. */
    private void executar(Runnable acao) {
        try {
            acao.run();
        } catch (NegocioException e) {
            falhar(e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
        Platform.runLater(entrada::requestFocus);
    }

    // ----- Leitura -----

    @FXML
    private void registrar() {
        String texto = entrada.getText();
        if (Textos.vazio(texto)) {
            return;
        }
        executar(() -> {
            EntradaPdv lida = EntradaPdv.interpretar(texto);
            BigDecimal quantidade = lida.quantidade() != null ? lida.quantidade() : quantidadeProxima;
            if (lida.busca()) {
                Optional<ProdutoResumo> escolhido = DialogosPdv.buscarProduto(lida.texto(),
                        termo -> vendaService.pesquisarProdutos(sessao, termo));
                entrada.clear();
                escolhido.ifPresent(p -> incluir(vendaService.produtoParaVenda(sessao, p.id()), quantidade));
                return;
            }
            VendaService.LeituraProduto leitura = vendaService.lerProduto(sessao, lida.codigo());
            if (leitura.embalagem() != null) {
                // Caixa ou fardo (F20.2): a quantidade informada é o nº de embalagens.
                mostrarInclusao(vendaService.adicionarEmbalagem(sessao, carrinho, leitura, quantidade));
                return;
            }
            if (leitura.etiqueta() == null) {
                incluir(leitura.produto(), quantidade);
                return;
            }
            if (quantidade != null) {
                entrada.clear();
                quantidadeProxima = null;
                atualizarTela();
                throw new NegocioException("A etiqueta de balança já traz a quantidade: leia a etiqueta sem informar "
                        + "a quantidade (F3 ou *).");
            }
            mostrarInclusao(vendaService.adicionarEtiqueta(sessao, carrinho, leitura));
        });
    }

    private void buscar() {
        String texto = entrada.getText();
        Optional<ProdutoResumo> escolhido = DialogosPdv.buscarProduto(texto,
                termo -> vendaService.pesquisarProdutos(sessao, termo));
        entrada.clear();
        escolhido.ifPresent(p -> incluir(vendaService.produtoParaVenda(sessao, p.id()), quantidadeProxima));
    }

    /** Inclui o produto: sem quantidade, 1 para unidade inteira; para unidade fracionada, pede a quantidade (6.6). */
    private void incluir(ProdutoVenda produto, BigDecimal quantidade) {
        Optional<VendaService.Inclusao> inclusao;
        if (quantidade == null && produto.fracionada()) {
            inclusao = DialogosPdv.quantidade("Quantidade — " + produto.nome(),
                    "Informe a quantidade em " + produto.unidade() + " (até 3 casas). Preço: "
                            + Formatos.moeda(produto.precoVenda()) + "/" + produto.unidade() + ".",
                    texto -> vendaService.adicionarItem(sessao, carrinho, produto,
                            CampoNumero.ler(texto, "Quantidade")));
        } else {
            inclusao = Optional.of(vendaService.adicionarItem(sessao, carrinho, produto,
                    quantidade == null ? BigDecimal.ONE : quantidade));
        }
        inclusao.ifPresentOrElse(this::mostrarInclusao, entrada::clear);
    }

    private void mostrarInclusao(VendaService.Inclusao inclusao) {
        entrada.clear();
        quantidadeProxima = null;
        atualizarTela();
        tabelaItens.getSelectionModel().selectLast();
        tabelaItens.scrollTo(tabelaItens.getItems().size() - 1);
        if (inclusao.aviso() != null) {
            Mensagem.erro(mensagem, inclusao.aviso());
        } else {
            Mensagem.limpar(mensagem);
        }
    }

    private void quantidadeDoProximo() {
        DialogosPdv.quantidade("Quantidade do próximo item (F3)",
                "Vale para o próximo produto lido. Também é possível digitar quantidade*código (ex.: 3*789…).",
                texto -> {
                    BigDecimal valor = CampoNumero.ler(texto, "Quantidade");
                    if (valor == null || valor.signum() <= 0) {
                        throw new NegocioException("A quantidade deve ser maior que zero.");
                    }
                    return valor;
                }).ifPresent(q -> {
                    quantidadeProxima = q;
                    atualizarTela();
                });
    }

    private void consultarPreco() {
        // F20.3: o texto traz também as faixas de preço por quantidade e as embalagens com preço próprio.
        DialogosPdv.consultarPreco(codigo -> vendaService.consultarPreco(sessao, codigo).texto());
    }

    /** Marca o item vendido pelo preço de uma promoção (6.8) ou de uma faixa de atacado (F20.2). */
    private static String promocao(ItemCarrinho item) {
        if (item.faixa() != null) {
            return "  (atacado)"; // curto como "(promoção)": texto longo alargava o painel do último item
        }
        return item.promocaoId() == null ? "" : "  (promoção)";
    }

    // ----- Descontos e cancelamentos -----

    private void descontoNoItem() {
        ItemCarrinho item = itemSelecionado();
        DialogosPdv.desconto("Desconto no item " + item.sequencia() + " — " + item.produto().nome(),
                "Valor do item: " + Formatos.moeda(item.valorBruto()) + ". Desconto atual: "
                        + Formatos.moeda(item.desconto()) + ". Informe 0 para retirar.",
                (tipo, texto) -> comLiberacaoDeDesconto(liberacao -> vendaService.aplicarDescontoItem(sessao,
                        carrinho, item.sequencia(), tipo, CampoNumero.ler(texto, "Desconto"), liberacao)))
                .ifPresent(ok -> {
                    atualizarTela();
                    Mensagem.sucessoNoRotulo(mensagem, "Desconto aplicado no item " + item.sequencia() + ".");
                });
    }

    private void descontoNaVenda() {
        if (!carrinho.temItensAtivos()) {
            throw new NegocioException("Registre ao menos um item antes de dar desconto.");
        }
        DialogosPdv.desconto("Desconto na venda",
                "Total antes do desconto da venda: " + Formatos.moeda(carrinho.baseDescontoVenda())
                        + ". Desconto atual: " + Formatos.moeda(carrinho.descontoVenda())
                        + ". O desconto é rateado entre os itens. Informe 0 para retirar.",
                (tipo, texto) -> comLiberacaoDeDesconto(liberacao -> vendaService.aplicarDescontoVenda(sessao,
                        carrinho, tipo, CampoNumero.ler(texto, "Desconto"), liberacao)))
                .ifPresent(ok -> {
                    atualizarTela();
                    Mensagem.sucessoNoRotulo(mensagem, "Desconto na venda: " + Formatos.moeda(carrinho.descontoVenda()) + ".");
                });
    }

    /** Aplica o desconto; acima do limite, pede a liberação e tenta de novo com ela (6.6). */
    private Boolean comLiberacaoDeDesconto(Consumer<Liberacao> aplicar) {
        try {
            aplicar.accept(null);
        } catch (LimiteDescontoException e) {
            Optional<Liberacao> liberacao = liberar("Desconto acima do limite", Permissao.DESCONTO_ACIMA_LIMITE);
            if (liberacao.isEmpty()) {
                throw e;
            }
            aplicar.accept(liberacao.get());
        }
        return Boolean.TRUE;
    }

    private void cancelarItem() {
        ItemCarrinho item = itemSelecionado();
        if (!Dialogos.confirmar("Cancelar o item " + item.sequencia() + " — " + item.produto().nome() + " ("
                + quantidade(item) + ", " + Formatos.moeda(item.base()) + ")?")) {
            return;
        }
        Liberacao liberacao = null;
        if (vendaService.cancelarItemPrecisaLiberacao(sessao)) {
            Optional<Liberacao> concedida = liberar("Cancelar item", Permissao.CANCELAR_VENDA);
            if (concedida.isEmpty()) {
                return;
            }
            liberacao = concedida.get();
        }
        String aviso = vendaService.cancelarItem(sessao, carrinho, item.sequencia(), liberacao);
        atualizarTela();
        if (aviso != null) {
            Mensagem.erro(mensagem, aviso);
        } else {
            Mensagem.sucessoNoRotulo(mensagem, "Item " + item.sequencia() + " cancelado.");
        }
    }

    private void escape() {
        if (carrinho.vazio()) {
            if (quantidadeProxima != null) {
                quantidadeProxima = null;
                atualizarTela();
                return;
            }
            janela.close();
            return;
        }
        if (Dialogos.confirmar("Abandonar a venda em andamento (" + resumoDaVenda() + ")?")) {
            abandonar();
            Mensagem.informar(mensagem, "Venda abandonada.");
        }
    }

    private void abandonar() {
        vendaService.abandonar(sessao, carrinho);
        carrinho = new Carrinho();
        quantidadeProxima = null;
        atualizarTela();
    }

    /**
     * Sessão encerrada pelo supervisor (F10.1): a venda em andamento é abandonada sem perguntar, com
     * {@code VENDA_ABANDONADA}, e o PDV fecha.
     */
    void encerrarSessao() {
        try {
            vendaService.abandonarNoEncerramento(sessao, carrinho, "sessão encerrada: acesso alterado pelo supervisor");
        } catch (RuntimeException e) {
            TratadorExcecoes.mensagem(e); // fica no log; o log off continua
        }
        carrinho = new Carrinho();
        janela.close();
    }

    /**
     * "Trocar usuário" na tela bloqueada (F10.2): com venda em andamento, pergunta (por cima da tela bloqueada) se
     * abandona; se sim, abandona com {@code VENDA_ABANDONADA} e fecha o PDV.
     *
     * @return false se a pessoa preferiu não abandonar a venda
     */
    boolean liberarParaTrocaDeUsuario(Window dono) {
        if (!carrinho.vazio()) {
            if (!Dialogos.confirmar(dono, "Há uma venda em andamento (" + resumoDaVenda() + "). Abandonar a venda e "
                    + "trocar de usuário?")) {
                return false;
            }
            try {
                vendaService.abandonarNoEncerramento(sessao, carrinho, "troca de usuário na tela bloqueada");
            } catch (RuntimeException e) {
                TratadorExcecoes.mostrar(e);
                return false;
            }
            carrinho = new Carrinho();
        }
        janela.close();
        return true;
    }

    /** Fechar a janela: sem venda, sai; com venda, pergunta se abandona. */
    private boolean podeSair() {
        if (carrinho.vazio()) {
            return true;
        }
        if (!Dialogos.confirmar("Há uma venda em andamento (" + resumoDaVenda() + "). Abandonar a venda e sair "
                + "do PDV?")) {
            return false;
        }
        try {
            abandonar();
            return true;
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
            return false;
        }
    }

    // ----- Pagamento e finalização -----

    private void pagar() {
        if (!carrinho.temItensAtivos()) {
            throw new NegocioException("Registre ao menos um item antes do pagamento.");
        }
        if (vendaService.descontoPrecisaLiberacao(sessao, carrinho)) {
            Optional<Liberacao> liberacao = liberar("Desconto acima do limite", Permissao.DESCONTO_ACIMA_LIMITE);
            if (liberacao.isEmpty()) {
                return;
            }
            vendaService.liberarDesconto(sessao, carrinho, liberacao.get());
        }
        Optional<VendaService.VendaFinalizada> finalizada;
        if (carrinho.total().signum() == 0) {
            if (!Dialogos.confirmar("O total é R$ 0,00. Finalizar a venda sem pagamento?")) {
                return;
            }
            finalizada = Optional.of(vendaService.finalizar(sessao, carrinho, new Recebimento(carrinho.total())));
        } else {
            PagamentoController pagamento = Janelas.abrirModal(janela, "/fxml/pagamento.fxml", "Pagamento",
                    tipo -> new PagamentoController(vendaService, acessoService, sessao, carrinho),
                    (PagamentoController c) -> { });
            finalizada = pagamento.resultado();
        }
        finalizada.ifPresent(this::concluir);
    }

    /** Depois do commit: troco em destaque, oferta do comprovante e tela limpa para a próxima venda (6.6). */
    private void concluir(VendaService.VendaFinalizada venda) {
        carrinho = new Carrinho();
        quantidadeProxima = null;
        atualizarTela();
        Mensagem.sucessoNoRotulo(mensagem, "Venda nº " + venda.vendaId() + " finalizada. Troco: "
                + Formatos.moeda(venda.troco()) + "." + (venda.cashback().signum() > 0
                ? " Cashback para o cliente: " + Formatos.moeda(venda.cashback()) + "." : ""));
        if (DialogosPdv.vendaFinalizada(venda.vendaId(), venda.troco())) {
            mostrarComprovante(venda.comprovante());
        }
    }

    private void mostrarComprovante(ComprovanteImpresso comprovante) {
        ComprovanteController.abrir(janela, comprovante, comprovantePdf, impressao);
    }

    private void vendasDaSessao() {
        Janelas.abrirModal(janela, "/fxml/vendas-sessao.fxml", "Vendas da sessão",
                tipo -> new VendasSessaoController(vendaService, acessoService, sessao, this::mostrarComprovante,
                        this::devolverItens),
                (VendasSessaoController c) -> { });
    }

    /**
     * "Devolver itens" do F9 (F13.4): a mesma devolução da tela Devoluções, aberta sobre a janela das vendas da
     * sessão.
     *
     * @return a devolução registrada, ou vazio
     */
    private Optional<DevolucaoService.DevolucaoRegistrada> devolverItens(Window dona, long vendaId) {
        Optional<DevolucaoService.DevolucaoRegistrada> feita = DevolucaoController.abrir(dona, devolucaoService,
                clienteService, acessoService, sessao, vendaId);
        feita.ifPresent(d -> {
            atualizarTela();
            if (Dialogos.confirmar(dona, "Devolução nº " + d.id() + " registrada: " + Formatos.moeda(d.valor())
                    + " em " + d.comprovante().forma().rotulo().toLowerCase() + ". Mostrar o comprovante?")) {
                ComprovanteController.abrir(dona, d.comprovante(), comprovantePdf, impressao);
            }
        });
        return feita;
    }

    // ----- Cliente (F11) -----

    /** Identifica, troca ou remove o cliente da venda em andamento (F12.2), com cadastro rápido sem sair do PDV. */
    private void identificarCliente() {
        DialogosCliente.identificar(carrinho.cliente().orElse(null),
                texto -> clienteService.pesquisarParaVenda(sessao, texto),
                (nome, documento) -> clienteService.cadastrarRapido(sessao, nome, documento)).ifPresent(escolha -> {
                    if (escolha.cliente() == null) {
                        vendaService.removerCliente(sessao, carrinho);
                        Mensagem.informar(mensagem, "Cliente removido da venda.");
                    } else {
                        ClienteVenda identificado = vendaService.identificarCliente(sessao, carrinho,
                                escolha.cliente().id());
                        Mensagem.sucessoNoRotulo(mensagem, "Cliente identificado: " + identificado.nome()
                                + ". Saldo de crédito: " + Formatos.moeda(identificado.saldoCredito()) + ".");
                    }
                    atualizarTela();
                });
    }

    // ----- Caixa (F8, F12) -----

    /** Ctrl+R (F14.3): não mexe na venda em andamento; o carrinho continua como está. */
    private void receberCrediario() {
        if (acoesRecebimento.receber(janela != null ? janela : raiz.getScene().getWindow())) {
            Mensagem.sucessoNoRotulo(mensagem, "Recebimento registrado.");
        }
    }

    private void sangriaSuprimento() {
        acoesCaixa.movimentar().ifPresent(texto -> Mensagem.sucessoNoRotulo(mensagem, texto));
    }

    private void fecharCaixa() {
        if (!carrinho.vazio()) {
            throw new NegocioException("Finalize ou abandone a venda em andamento antes de fechar o caixa.");
        }
        Optional<RelatorioFechamento> fechamento = acoesCaixa.fechar();
        if (fechamento.isPresent()) {
            acoesCaixa.mostrarRelatorio(janela, fechamento.get());
            janela.close(); // sem caixa aberto não há venda; para vender de novo, abre-se o PDV e o caixa
        }
    }

    private void atualizarCaixa() {
        Optional<CaixaSessao> caixa = caixaService.caixaAberto(sessao);
        caixaInfo.setText(caixa.map(c -> "Caixa nº " + c.id()).orElse("Caixa fechado"));
    }

    // ----- Tela -----

    private void atualizarTela() {
        cliente.setText(carrinho.cliente().map(c -> "Cliente: " + c.descricao() + " · crédito "
                + Formatos.moeda(c.saldoCredito())).orElse("Sem cliente (F11)"));
        cliente.getStyleClass().remove("negativo");
        if (carrinho.cliente().map(c -> c.saldoCredito().signum() < 0).orElse(false)) {
            cliente.getStyleClass().add("negativo");
        }
        tabelaItens.getItems().setAll(carrinho.itens());
        tabelaItens.refresh();
        Optional<ItemCarrinho> ultimo = carrinho.ultimoItem();
        ultimoDescricao.setText(ultimo.map(i -> i.produto().nome() + promocao(i) + (i.cancelado() ? " (cancelado)" : ""))
                .orElse("Caixa livre"));
        ultimoDetalhe.setText(ultimo.map(i -> quantidade(i) + " x " + Formatos.moeda(i.precoUnitario()) + " = "
                + Formatos.moeda(i.valorBruto())).orElse(""));
        quantidadeItens.setText(String.valueOf(carrinho.quantidadeItensAtivos()));
        subtotal.setText(Formatos.moeda(carrinho.valorBruto()));
        descontos.setText(carrinho.descontos().signum() > 0 ? "-" + Formatos.moeda(carrinho.descontos()) + " ("
                + Formatos.percentual(carrinho.percentualDesconto()) + ")" : Formatos.moeda(Dinheiro.ZERO));
        total.setText(Formatos.moeda(carrinho.total()));
        proximaQuantidade.setText(quantidadeProxima == null ? ""
                : "Quantidade do próximo item: " + Formatos.numero(quantidadeProxima, casas(quantidadeProxima))
                + " (Esc limpa)");
    }

    private ItemCarrinho itemSelecionado() {
        ItemCarrinho item = Optional.ofNullable(tabelaItens.getSelectionModel().getSelectedItem())
                .or(carrinho::ultimoItem)
                .orElseThrow(() -> new NegocioException("Nenhum item na venda."));
        if (item.cancelado()) {
            throw new NegocioException("O item " + item.sequencia() + " já foi cancelado. Escolha outro com ↑/↓.");
        }
        return item;
    }

    private void moverSelecao(int passo) {
        int linhas = tabelaItens.getItems().size();
        if (linhas == 0) {
            return;
        }
        int atual = tabelaItens.getSelectionModel().getSelectedIndex();
        int novo = atual < 0 ? linhas - 1 : Math.max(0, Math.min(linhas - 1, atual + passo));
        tabelaItens.getSelectionModel().select(novo);
        tabelaItens.scrollTo(novo);
    }

    /** Recusa da leitura: alerta visual e sonoro, campo limpo, foco mantido (6.6). */
    private void falhar(String texto) {
        Mensagem.erro(mensagem, texto);
        entrada.clear();
        if (!entrada.getStyleClass().contains(ENTRADA_ERRO)) {
            entrada.getStyleClass().add(ENTRADA_ERRO);
        }
        PauseTransition pausa = new PauseTransition(Duration.millis(900));
        pausa.setOnFinished(e -> entrada.getStyleClass().remove(ENTRADA_ERRO));
        pausa.play();
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (RuntimeException | Error semSom) {
            // sem dispositivo de som: o alerta visual basta
        }
    }

    private Optional<Liberacao> liberar(String acao, Permissao permissao) {
        return LiberacaoSupervisorDialogo.solicitar(acao,
                (login, senha) -> acessoService.liberar(login, senha, permissao));
    }

    private String resumoDaVenda() {
        long itens = carrinho.quantidadeItensAtivos();
        return itens + (itens == 1 ? " item" : " itens") + ", " + Formatos.moeda(carrinho.total());
    }

    private static String quantidade(ItemCarrinho item) {
        return Formatos.quantidade(item.quantidade(), item.produto().fracionada()) + " " + item.produto().unidade();
    }

    private static ReadOnlyStringWrapper texto(String valor) {
        return new ReadOnlyStringWrapper(valor);
    }

    /** Casas para exibir a quantidade do próximo item como foi digitada (3 ou 0,345). */
    private static int casas(BigDecimal valor) {
        return Math.max(0, valor.stripTrailingZeros().scale());
    }
}
