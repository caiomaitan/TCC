package br.com.varesync.controller;

import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.Carrinho;
import br.com.varesync.service.EntradaPdv;
import br.com.varesync.service.ItemCarrinho;
import br.com.varesync.service.Liberacao;
import br.com.varesync.service.LimiteDescontoException;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Recebimento;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.VendaService;
import br.com.varesync.ui.Dialogos;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * PDV (6.6): leitura do produto (código, quantidade*código, texto → busca), carrinho em memória, descontos com limite
 * e liberação, cancelamento de item, abandono, pagamento (F10) e finalização numa única transação, vendas da sessão
 * (F9) e o comprovante. F8 e F12 usam as mesmas operações da tela Caixa. Toda regra está no {@link VendaService}.
 */
public class PdvController {

    private static final String ITEM_CANCELADO = "item-cancelado";
    private static final String ENTRADA_ERRO = "entrada-erro";
    private static final String AJUDA = """
            Leitura do produto (campo Código, sempre focado):
              • leia o código de barras ou digite o código interno e tecle Enter;
              • quantidade*código: 3*7891000315507 ou 0,345*123;
              • texto: abre a busca já filtrada.
            Produto vendido por peso ou volume sem quantidade: o sistema pede a quantidade.

            F1 ajuda · F2 buscar produto · F3 quantidade do próximo item
            F4 desconto no item selecionado · F5 desconto na venda (R$ ou %)
            F6 cancelar o item selecionado · F7 consultar preço (sem vender)
            F8 sangria/suprimento · F9 vendas da sessão (comprovante ou cancelamento)
            F10 pagamento e finalização · F12 fechar o caixa
            ↑/↓ escolhem o item · Esc abandona a venda em andamento (ou sai do PDV, sem venda)""";

    private final VendaService vendaService;
    private final CaixaService caixaService;
    private final AcessoService acessoService;
    private final ComprovantePdf comprovantePdf;
    private final Sessao sessao;
    private final AcoesCaixa acoesCaixa;
    private Carrinho carrinho = new Carrinho();
    private BigDecimal quantidadeProxima;
    private Stage janela;

    @FXML private BorderPane raiz;
    @FXML private Label loja;
    @FXML private Label caixaInfo;
    @FXML private Label terminalInfo;
    @FXML private Label operadorInfo;
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

    public PdvController(VendaService vendaService, CaixaService caixaService, AcessoService acessoService,
                         ComprovantePdf comprovantePdf, Sessao sessao) {
        this.vendaService = vendaService;
        this.caixaService = caixaService;
        this.acessoService = acessoService;
        this.comprovantePdf = comprovantePdf;
        this.sessao = sessao;
        this.acoesCaixa = new AcoesCaixa(caixaService, acessoService, sessao);
    }

    @FXML
    private void initialize() {
        colunaSequencia.setCellValueFactory(c -> texto(String.format("%03d", c.getValue().sequencia())));
        colunaCodigo.setCellValueFactory(c -> texto(c.getValue().produto().codigo()));
        colunaDescricao.setCellValueFactory(c -> texto(c.getValue().produto().nome()
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

        raiz.addEventFilter(KeyEvent.KEY_PRESSED, this::atalho);
        entrada.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
                e.consume();
                moverSelecao(e.getCode() == KeyCode.UP ? -1 : 1);
            }
        });
        atualizarTela();
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

    // ----- Teclado -----

    private void atalho(KeyEvent e) {
        Runnable acao = switch (e.getCode()) {
            case F1 -> () -> Dialogos.alerta(Alert.AlertType.INFORMATION, AJUDA);
            case F2 -> this::buscar;
            case F3 -> this::quantidadeDoProximo;
            case F4 -> this::descontoNoItem;
            case F5 -> this::descontoNaVenda;
            case F6 -> this::cancelarItem;
            case F7 -> this::consultarPreco;
            case F8 -> this::sangriaSuprimento;
            case F9 -> this::vendasDaSessao;
            case F10 -> this::pagar;
            case F12 -> this::fecharCaixa;
            case ESCAPE -> this::escape;
            default -> null;
        };
        if (acao == null) {
            return;
        }
        e.consume();
        executar(acao);
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
            incluir(vendaService.lerProduto(sessao, lida.codigo()), quantidade);
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
                    texto -> vendaService.adicionarItem(sessao, carrinho, produto, lerNumero(texto, "a quantidade")));
        } else {
            inclusao = Optional.of(vendaService.adicionarItem(sessao, carrinho, produto,
                    quantidade == null ? BigDecimal.ONE : quantidade));
        }
        entrada.clear();
        if (inclusao.isEmpty()) {
            return;
        }
        quantidadeProxima = null;
        atualizarTela();
        tabelaItens.getSelectionModel().selectLast();
        tabelaItens.scrollTo(tabelaItens.getItems().size() - 1);
        if (inclusao.get().aviso() != null) {
            Mensagem.erro(mensagem, inclusao.get().aviso());
        } else {
            Mensagem.limpar(mensagem);
        }
    }

    private void quantidadeDoProximo() {
        DialogosPdv.quantidade("Quantidade do próximo item (F3)",
                "Vale para o próximo produto lido. Também é possível digitar quantidade*código (ex.: 3*789…).",
                texto -> {
                    BigDecimal valor = lerNumero(texto, "a quantidade");
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
        DialogosPdv.consultarPreco(codigo -> {
            ProdutoVenda p = vendaService.consultarPreco(sessao, codigo);
            return p.nome() + "\n" + Formatos.moeda(p.precoVenda()) + " / " + p.unidade() + "\nEstoque: "
                    + Formatos.quantidade(p.saldo(), p.fracionada()) + " " + p.unidade()
                    + (p.ativo() ? "" : "\nProduto inativo: não pode ser vendido.");
        });
    }

    // ----- Descontos e cancelamentos -----

    private void descontoNoItem() {
        ItemCarrinho item = itemSelecionado();
        DialogosPdv.desconto("Desconto no item " + item.sequencia() + " — " + item.produto().nome(),
                "Valor do item: " + Formatos.moeda(item.valorBruto()) + ". Desconto atual: "
                        + Formatos.moeda(item.desconto()) + ". Informe 0 para retirar.",
                (tipo, texto) -> comLiberacaoDeDesconto(liberacao -> vendaService.aplicarDescontoItem(sessao,
                        carrinho, item.sequencia(), tipo, lerNumero(texto, "o desconto"), liberacao)))
                .ifPresent(ok -> {
                    atualizarTela();
                    Mensagem.sucesso(mensagem, "Desconto aplicado no item " + item.sequencia() + ".");
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
                        carrinho, tipo, lerNumero(texto, "o desconto"), liberacao)))
                .ifPresent(ok -> {
                    atualizarTela();
                    Mensagem.sucesso(mensagem, "Desconto na venda: " + Formatos.moeda(carrinho.descontoVenda()) + ".");
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
            Mensagem.sucesso(mensagem, "Item " + item.sequencia() + " cancelado.");
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
                    tipo -> new PagamentoController(vendaService, sessao, carrinho), (PagamentoController c) -> { });
            finalizada = pagamento.resultado();
        }
        finalizada.ifPresent(this::concluir);
    }

    /** Depois do commit: troco em destaque, oferta do comprovante e tela limpa para a próxima venda (6.6). */
    private void concluir(VendaService.VendaFinalizada venda) {
        carrinho = new Carrinho();
        quantidadeProxima = null;
        atualizarTela();
        Mensagem.sucesso(mensagem, "Venda nº " + venda.vendaId() + " finalizada. Troco: "
                + Formatos.moeda(venda.troco()) + ".");
        if (DialogosPdv.vendaFinalizada(venda.vendaId(), venda.troco())) {
            mostrarComprovante(venda.comprovante());
        }
    }

    private void mostrarComprovante(Comprovante comprovante) {
        Janelas.abrirModal(janela, "/fxml/comprovante.fxml", "Comprovante da venda nº " + comprovante.vendaId(),
                tipo -> new ComprovanteController(comprovantePdf), (ComprovanteController c) -> c.mostrar(comprovante));
    }

    private void vendasDaSessao() {
        Janelas.abrirModal(janela, "/fxml/vendas-sessao.fxml", "Vendas da sessão",
                tipo -> new VendasSessaoController(vendaService, acessoService, sessao, this::mostrarComprovante),
                (VendasSessaoController c) -> { });
    }

    // ----- Caixa (F8, F12) -----

    private void sangriaSuprimento() {
        acoesCaixa.movimentar().ifPresent(texto -> Mensagem.sucesso(mensagem, texto));
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
        tabelaItens.getItems().setAll(carrinho.itens());
        tabelaItens.refresh();
        Optional<ItemCarrinho> ultimo = carrinho.ultimoItem();
        ultimoDescricao.setText(ultimo.map(i -> i.produto().nome() + (i.cancelado() ? " (cancelado)" : ""))
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

    /** Número digitado; vazio → nulo (o service pede o valor). */
    static BigDecimal lerNumero(String texto, String rotulo) {
        if (Textos.vazio(texto)) {
            return null;
        }
        try {
            return Dinheiro.ler(texto);
        } catch (IllegalArgumentException e) {
            throw new NegocioException("Valor inválido para " + rotulo + " (\"" + texto.strip() + "\").");
        }
    }

    private static ReadOnlyStringWrapper texto(String valor) {
        return new ReadOnlyStringWrapper(valor);
    }

    /** Casas para exibir a quantidade do próximo item como foi digitada (3 ou 0,345). */
    private static int casas(BigDecimal valor) {
        return Math.max(0, valor.stripTrailingZeros().scale());
    }
}
