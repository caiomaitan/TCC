package br.com.varesync.controller;

import br.com.varesync.model.CategoriaDespesa;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.MovimentoConta;
import br.com.varesync.model.PagamentoTitulo;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Recebivel;
import br.com.varesync.model.StatusRecebivel;
import br.com.varesync.model.StatusTituloPagar;
import br.com.varesync.model.TituloPagar;
import br.com.varesync.report.Agrupamento;
import br.com.varesync.report.FluxoCaixa;
import br.com.varesync.service.ContaFinanceiraService;
import br.com.varesync.service.ContaPagarService;
import br.com.varesync.service.FluxoCaixaService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RecebivelService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.DialogosFinanceiro;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;

/**
 * Financeiro (F15): contas com saldo e extrato (saldo inicial, ajuste e transferência), conciliação dos recebíveis,
 * contas a pagar (despesa com repetição, pagamento, estorno e cancelamento) e fluxo de caixa (F15.4: realizado e
 * previsto por dia, semana ou mês, com gráfico, calculado em {@link Task}). A verificação da permissão FINANCEIRO é a
 * dos services.
 */
public class FinanceiroController {

    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/uuuu");
    private static final DateTimeFormatter DIA_GRAFICO = DateTimeFormatter.ofPattern("dd/MM");

    private final ContaFinanceiraService contaService;
    private final RecebivelService recebivelService;
    private final ContaPagarService contaPagarService;
    private final FluxoCaixaService fluxoService;
    private final Sessao sessao;
    /** Líquidos corrigidos à mão, ainda não conciliados. */
    private final Map<Long, BigDecimal> liquidosCorrigidos = new HashMap<>();

    @FXML private VBox raiz;
    @FXML private Button botaoEditarConta;
    @FXML private Button botaoSaldoInicial;
    @FXML private Button botaoAjuste;
    @FXML private TableView<ContaFinanceira> tabelaContas;
    @FXML private TableColumn<ContaFinanceira, String> colunaContaNome;
    @FXML private TableColumn<ContaFinanceira, String> colunaContaTipo;
    @FXML private TableColumn<ContaFinanceira, String> colunaContaSituacao;
    @FXML private TableColumn<ContaFinanceira, String> colunaContaSaldo;
    @FXML private DatePicker extratoDe;
    @FXML private DatePicker extratoAte;
    @FXML private TableView<MovimentoConta> tabelaExtrato;
    @FXML private TableColumn<MovimentoConta, String> colunaExtratoData;
    @FXML private TableColumn<MovimentoConta, String> colunaExtratoTipo;
    @FXML private TableColumn<MovimentoConta, String> colunaExtratoDescricao;
    @FXML private TableColumn<MovimentoConta, String> colunaExtratoValor;
    @FXML private TableColumn<MovimentoConta, String> colunaExtratoSaldo;
    @FXML private TableColumn<MovimentoConta, String> colunaExtratoUsuario;
    @FXML private ComboBox<FormaPagamentoCadastroOpcao> filtroForma;
    @FXML private ComboBox<StatusRecebivel> filtroStatusRecebivel;
    @FXML private DatePicker recebivelDe;
    @FXML private DatePicker recebivelAte;
    @FXML private TableView<Recebivel> tabelaRecebiveis;
    @FXML private TableColumn<Recebivel, String> colunaRecOrigem;
    @FXML private TableColumn<Recebivel, String> colunaRecForma;
    @FXML private TableColumn<Recebivel, String> colunaRecConta;
    @FXML private TableColumn<Recebivel, String> colunaRecBruto;
    @FXML private TableColumn<Recebivel, String> colunaRecTaxa;
    @FXML private TableColumn<Recebivel, String> colunaRecLiquido;
    @FXML private TableColumn<Recebivel, String> colunaRecPrevisto;
    @FXML private TableColumn<Recebivel, String> colunaRecSituacao;
    @FXML private TableColumn<Recebivel, String> colunaRecRecebido;
    @FXML private Label totalRecebiveis;
    @FXML private Button botaoLiquido;
    @FXML private Button botaoConciliar;
    @FXML private ComboBox<StatusTituloPagar> filtroStatusTitulo;
    @FXML private ComboBox<CategoriaDespesa> filtroCategoria;
    @FXML private DatePicker tituloDe;
    @FXML private DatePicker tituloAte;
    @FXML private TableView<TituloPagar> tabelaTitulos;
    @FXML private TableColumn<TituloPagar, String> colunaTitVencimento;
    @FXML private TableColumn<TituloPagar, String> colunaTitDescricao;
    @FXML private TableColumn<TituloPagar, String> colunaTitCategoria;
    @FXML private TableColumn<TituloPagar, String> colunaTitCompetencia;
    @FXML private TableColumn<TituloPagar, String> colunaTitValor;
    @FXML private TableColumn<TituloPagar, String> colunaTitAberto;
    @FXML private TableColumn<TituloPagar, String> colunaTitSituacao;
    @FXML private TableView<PagamentoTitulo> tabelaPagamentos;
    @FXML private TableColumn<PagamentoTitulo, String> colunaPagData;
    @FXML private TableColumn<PagamentoTitulo, String> colunaPagConta;
    @FXML private TableColumn<PagamentoTitulo, String> colunaPagValor;
    @FXML private TableColumn<PagamentoTitulo, String> colunaPagJuros;
    @FXML private TableColumn<PagamentoTitulo, String> colunaPagDesconto;
    @FXML private TableColumn<PagamentoTitulo, String> colunaPagUsuario;
    @FXML private TableColumn<PagamentoTitulo, String> colunaPagSituacao;
    @FXML private Button botaoCancelarTitulo;
    @FXML private Button botaoEstornar;
    @FXML private Button botaoPagar;
    @FXML private TabPane abas;
    @FXML private Tab abaContas;
    @FXML private Tab abaContasPagar;
    @FXML private Tab abaFluxo;
    @FXML private DatePicker fluxoDe;
    @FXML private DatePicker fluxoAte;
    @FXML private ComboBox<Agrupamento> fluxoAgrupamento;
    @FXML private Button botaoFluxo;
    @FXML private ProgressIndicator progressoFluxo;
    @FXML private Label resumoFluxo;
    @FXML private LineChart<String, Number> graficoFluxo;
    @FXML private TableView<FluxoCaixa.Grupo> tabelaFluxo;
    @FXML private TableColumn<FluxoCaixa.Grupo, String> colunaFluxoPeriodo;
    @FXML private TableColumn<FluxoCaixa.Grupo, String> colunaFluxoEntradasReal;
    @FXML private TableColumn<FluxoCaixa.Grupo, String> colunaFluxoSaidasReal;
    @FXML private TableColumn<FluxoCaixa.Grupo, String> colunaFluxoEntradasPrev;
    @FXML private TableColumn<FluxoCaixa.Grupo, String> colunaFluxoSaidasPrev;
    @FXML private TableColumn<FluxoCaixa.Grupo, String> colunaFluxoSaldo;
    @FXML private TableView<FluxoCaixa.Lancamento> tabelaFluxoDetalhe;
    @FXML private TableColumn<FluxoCaixa.Lancamento, String> colunaDetalheParte;
    @FXML private TableColumn<FluxoCaixa.Lancamento, String> colunaDetalheLancamento;
    @FXML private TableColumn<FluxoCaixa.Lancamento, String> colunaDetalheEntradas;
    @FXML private TableColumn<FluxoCaixa.Lancamento, String> colunaDetalheSaidas;
    @FXML private Label mensagem;
    private FluxoCaixa fluxo;

    /** Opção do filtro de forma: nula em {@code forma} = todas. */
    public record FormaPagamentoCadastroOpcao(FormaPagamentoCadastro forma) {
        @Override
        public String toString() {
            return forma == null ? "Todas" : forma.descricao();
        }
    }

    public FinanceiroController(ContaFinanceiraService contaService, RecebivelService recebivelService,
                                ContaPagarService contaPagarService, FluxoCaixaService fluxoService, Sessao sessao) {
        this.contaService = contaService;
        this.recebivelService = recebivelService;
        this.contaPagarService = contaPagarService;
        this.fluxoService = fluxoService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        for (DatePicker data : List.of(extratoDe, extratoAte, recebivelDe, recebivelAte, tituloDe, tituloAte, fluxoDe,
                fluxoAte)) {
            data.setConverter(new ConversorData());
        }
        prepararContas();
        prepararRecebiveis();
        prepararTitulos();
        prepararFluxo();
        carregarContas(null);
        carregarRecebiveis();
        carregarTitulos();
    }

    /** Alerta do painel (F15.6): contas a pagar em aberto com vencimento de {@code de} até {@code ate}. */
    public void mostrarContasAPagar(LocalDate de, LocalDate ate) {
        abas.getSelectionModel().select(abaContasPagar);
        filtroStatusTitulo.setValue(StatusTituloPagar.ABERTO);
        filtroCategoria.setValue(null);
        tituloDe.setValue(de);
        tituloAte.setValue(ate);
        carregarTitulos();
    }

    /** Alerta do painel (F15.6): conta com saldo negativo. */
    public void mostrarContas() {
        abas.getSelectionModel().select(abaContas);
        tabelaContas.getItems().stream().filter(c -> c.saldo().signum() < 0).findFirst()
                .ifPresent(c -> tabelaContas.getSelectionModel().select(c));
    }

    private static <T> void texto(TableColumn<T, String> coluna, Function<T, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    private static String data(LocalDate data) {
        return data == null ? "" : Formatos.data(data);
    }

    /** Executa a ação; a regra recusada vira mensagem na tela. */
    private void executar(Runnable acao) {
        try {
            acao.run();
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    // ----- Contas -----

    private void prepararContas() {
        texto(colunaContaNome, ContaFinanceira::nome);
        texto(colunaContaTipo, c -> c.tipo().rotulo());
        texto(colunaContaSituacao, c -> (c.ativa() ? "Ativa" : "Inativa") + (c.padraoCaixa() ? " · padrão do caixa" : ""));
        // Saldo negativo em vermelho (F15.1), na célula, como o estoque negativo.
        colunaContaSaldo.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().saldo())));
        colunaContaSaldo.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String texto, boolean vazio) {
                super.updateItem(texto, vazio);
                getStyleClass().remove("negativo");
                ContaFinanceira c = vazio ? null : getTableRow().getItem();
                setText(vazio ? null : texto);
                if (c != null && c.saldo().signum() < 0) {
                    getStyleClass().add("negativo");
                }
            }
        });
        texto(colunaExtratoData, m -> Formatos.dataHora(m.dataHora()));
        texto(colunaExtratoTipo, m -> m.tipo().rotulo());
        texto(colunaExtratoDescricao, MovimentoConta::descricao);
        texto(colunaExtratoValor, m -> Formatos.moeda(m.valor()));
        texto(colunaExtratoSaldo, m -> Formatos.moeda(m.saldoPosterior()));
        texto(colunaExtratoUsuario, MovimentoConta::usuario);
        Periodo padrao = contaService.periodoPadrao();
        extratoDe.setValue(padrao.inicio());
        extratoAte.setValue(padrao.fim());
        tabelaContas.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> {
            for (Button b : List.of(botaoEditarConta, botaoSaldoInicial, botaoAjuste)) {
                b.setDisable(c == null);
            }
            carregarExtrato();
        });
    }

    private ContaFinanceira contaSelecionada() {
        return tabelaContas.getSelectionModel().getSelectedItem();
    }

    private void carregarContas(Long selecionar) {
        List<ContaFinanceira> contas = contaService.listar(sessao);
        tabelaContas.getItems().setAll(contas);
        contas.stream().filter(c -> selecionar == null || c.id() == selecionar).findFirst()
                .ifPresent(c -> tabelaContas.getSelectionModel().select(c));
    }

    @FXML
    private void carregarExtrato() {
        ContaFinanceira conta = contaSelecionada();
        if (conta == null || extratoDe.getValue() == null || extratoAte.getValue() == null) {
            tabelaExtrato.getItems().clear();
            return;
        }
        tabelaExtrato.getItems().setAll(contaService.extrato(sessao, conta.id(),
                new Periodo(extratoDe.getValue(), extratoAte.getValue())));
    }

    @FXML
    private void novaConta() {
        if (DialogosFinanceiro.conta(null, d -> contaService.criar(sessao, d.nome(), d.tipo(), d.padrao()))) {
            carregarContas(null);
            Mensagem.sucesso(mensagem, "Conta criada.");
        }
    }

    @FXML
    private void editarConta() {
        ContaFinanceira conta = contaSelecionada();
        if (conta != null && DialogosFinanceiro.conta(conta, d -> contaService.alterar(sessao, conta.id(), d.nome(),
                d.tipo(), d.ativa(), d.padrao()))) {
            carregarContas(conta.id());
            Mensagem.sucesso(mensagem, "Conta salva.");
        }
    }

    @FXML
    private void saldoInicial() {
        ContaFinanceira conta = contaSelecionada();
        if (conta != null && DialogosFinanceiro.lancamento("Saldo inicial — " + conta.nome(), false,
                d -> contaService.lancarSaldoInicial(sessao, conta.id(), d.valor()))) {
            carregarContas(conta.id());
            Mensagem.sucesso(mensagem, "Saldo inicial lançado.");
        }
    }

    @FXML
    private void ajuste() {
        ContaFinanceira conta = contaSelecionada();
        if (conta != null && DialogosFinanceiro.lancamento("Ajuste — " + conta.nome(), true,
                d -> contaService.ajustar(sessao, conta.id(), d.valor(), d.motivo()))) {
            carregarContas(conta.id());
            Mensagem.sucesso(mensagem, "Ajuste lançado.");
        }
    }

    @FXML
    private void transferir() {
        if (DialogosFinanceiro.transferencia(contaPagarService.contasAtivas(sessao),
                d -> contaService.transferir(sessao, d.origem(), d.destino(), d.valor(), d.descricao()))) {
            ContaFinanceira conta = contaSelecionada();
            carregarContas(conta == null ? null : conta.id());
            Mensagem.sucesso(mensagem, "Transferência registrada.");
        }
    }

    // ----- Recebíveis -----

    private void prepararRecebiveis() {
        texto(colunaRecOrigem, Recebivel::origem);
        texto(colunaRecForma, Recebivel::forma);
        texto(colunaRecConta, Recebivel::conta);
        texto(colunaRecBruto, r -> Formatos.moeda(r.valorBruto()));
        texto(colunaRecTaxa, r -> Formatos.moeda(r.valorBruto().subtract(liquido(r))));
        texto(colunaRecLiquido, r -> Formatos.moeda(liquido(r)));
        texto(colunaRecPrevisto, r -> data(r.previstoPara()));
        texto(colunaRecSituacao, r -> r.status().rotulo());
        texto(colunaRecRecebido, r -> data(r.recebidoEm()));
        filtroForma.getItems().add(new FormaPagamentoCadastroOpcao(null));
        recebivelService.formas(sessao).forEach(f -> filtroForma.getItems().add(new FormaPagamentoCadastroOpcao(f)));
        filtroForma.getSelectionModel().selectFirst();
        filtroStatusRecebivel.getItems().setAll(StatusRecebivel.values());
        filtroStatusRecebivel.setValue(StatusRecebivel.PREVISTO);
        tabelaRecebiveis.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tabelaRecebiveis.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<Recebivel>) c -> atualizarSelecaoRecebiveis());
    }

    private BigDecimal liquido(Recebivel r) {
        return liquidosCorrigidos.getOrDefault(r.id(), r.valorLiquido());
    }

    private void atualizarSelecaoRecebiveis() {
        List<Recebivel> selecionados = tabelaRecebiveis.getSelectionModel().getSelectedItems();
        boolean previstos = !selecionados.isEmpty()
                && selecionados.stream().allMatch(r -> r.status() == StatusRecebivel.PREVISTO);
        botaoConciliar.setDisable(!previstos);
        botaoLiquido.setDisable(!previstos || selecionados.size() != 1);
        BigDecimal base = selecionados.stream().map(this::liquido).reduce(Dinheiro.ZERO, BigDecimal::add);
        totalRecebiveis.setText(selecionados.isEmpty() ? "Selecione os recebíveis a conciliar"
                : selecionados.size() + " selecionado(s): " + Formatos.moeda(base) + " líquido");
    }

    @FXML
    private void carregarRecebiveis() {
        FormaPagamentoCadastroOpcao forma = filtroForma.getValue();
        tabelaRecebiveis.getItems().setAll(recebivelService.listar(sessao,
                forma == null || forma.forma() == null ? null : forma.forma().id(), filtroStatusRecebivel.getValue(),
                recebivelDe.getValue(), recebivelAte.getValue()));
        atualizarSelecaoRecebiveis();
    }

    @FXML
    private void corrigirLiquido() {
        Recebivel r = tabelaRecebiveis.getSelectionModel().getSelectedItem();
        if (r == null) {
            return;
        }
        DialogosFinanceiro.liquido(liquido(r), lido -> {
            if (lido == null || lido.signum() <= 0 || lido.compareTo(r.valorBruto()) > 0) {
                throw new NegocioException("O líquido deve ser maior que zero e até o valor bruto ("
                        + Formatos.moeda(r.valorBruto()) + ").");
            }
            return lido;
        }).ifPresent(lido -> {
            liquidosCorrigidos.put(r.id(), lido);
            tabelaRecebiveis.refresh();
            atualizarSelecaoRecebiveis();
        });
    }

    @FXML
    private void conciliar() {
        List<Recebivel> selecionados = List.copyOf(tabelaRecebiveis.getSelectionModel().getSelectedItems());
        if (selecionados.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> liquidos = new HashMap<>();
        selecionados.forEach(r -> liquidos.put(r.id(), liquido(r)));
        BigDecimal total = liquidos.values().stream().reduce(Dinheiro.ZERO, BigDecimal::add);
        if (DialogosFinanceiro.conciliacao(selecionados.size(), total, LocalDate.now(),
                d -> recebivelService.conciliar(sessao, liquidos, d.data(), d.total()))) {
            liquidos.keySet().forEach(liquidosCorrigidos::remove);
            carregarRecebiveis();
            ContaFinanceira conta = contaSelecionada();
            carregarContas(conta == null ? null : conta.id());
            Mensagem.sucesso(mensagem, selecionados.size() + " recebível(is) conciliado(s): " + Formatos.moeda(total)
                    + " na conta.");
        }
    }

    // ----- Contas a pagar -----

    private void prepararTitulos() {
        texto(colunaTitVencimento, t -> data(t.vencimento()));
        texto(colunaTitDescricao, TituloPagar::descricao);
        texto(colunaTitCategoria, TituloPagar::categoria);
        texto(colunaTitCompetencia, t -> t.competencia().format(MES));
        texto(colunaTitValor, t -> Formatos.moeda(t.valor()));
        texto(colunaTitAberto, t -> Formatos.moeda(t.emAberto()));
        texto(colunaTitSituacao, t -> t.status().rotulo());
        texto(colunaPagData, p -> data(p.data()));
        texto(colunaPagConta, PagamentoTitulo::conta);
        texto(colunaPagValor, p -> Formatos.moeda(p.valor()));
        texto(colunaPagJuros, p -> Formatos.moeda(p.juros()));
        texto(colunaPagDesconto, p -> Formatos.moeda(p.desconto()));
        texto(colunaPagUsuario, PagamentoTitulo::usuario);
        texto(colunaPagSituacao, p -> p.estornado() ? "Estornado: " + p.motivoEstorno() : "Pago");
        filtroStatusTitulo.getItems().setAll(StatusTituloPagar.values());
        filtroStatusTitulo.setValue(StatusTituloPagar.ABERTO);
        carregarCategorias();
        tabelaTitulos.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            botaoPagar.setDisable(t == null || t.status() != StatusTituloPagar.ABERTO);
            botaoCancelarTitulo.setDisable(t == null || t.status() == StatusTituloPagar.CANCELADO
                    || t.valorPago().signum() > 0);
            carregarPagamentos(t);
        });
        tabelaPagamentos.getSelectionModel().selectedItemProperty().addListener((o, a, p) ->
                botaoEstornar.setDisable(p == null || p.estornado()));
        botaoPagar.setDisable(true);
        botaoCancelarTitulo.setDisable(true);
        botaoEstornar.setDisable(true);
    }

    private void carregarCategorias() {
        filtroCategoria.getItems().setAll(contaPagarService.categorias(sessao));
        filtroCategoria.setValue(null);
        filtroCategoria.setPromptText("Todas");
    }

    private void carregarPagamentos(TituloPagar t) {
        if (t == null) {
            tabelaPagamentos.getItems().clear();
        } else {
            tabelaPagamentos.getItems().setAll(contaPagarService.pagamentos(sessao, t.id()));
        }
    }

    @FXML
    private void carregarTitulos() {
        CategoriaDespesa categoria = filtroCategoria.getValue();
        tabelaTitulos.getItems().setAll(contaPagarService.listar(sessao, filtroStatusTitulo.getValue(),
                categoria == null ? null : categoria.id(), tituloDe.getValue(), tituloAte.getValue()));
    }

    private void depoisDoPagamento(String texto) {
        carregarTitulos();
        tabelaPagamentos.getItems().clear();
        ContaFinanceira conta = contaSelecionada();
        carregarContas(conta == null ? null : conta.id());
        Mensagem.sucesso(mensagem, texto);
    }

    @FXML
    private void novaDespesa() {
        if (DialogosFinanceiro.despesa(contaPagarService.categorias(sessao), contaPagarService.fornecedores(sessao),
                LocalDate.now(), (dados, meses) -> contaPagarService.criar(sessao, dados, meses))) {
            carregarTitulos();
            Mensagem.sucesso(mensagem, "Despesa lançada.");
        }
    }

    @FXML
    private void categorias() {
        if (DialogosFinanceiro.categoria(contaPagarService.categorias(sessao),
                (nome, grupo) -> contaPagarService.criarCategoria(sessao, nome, grupo))) {
            carregarCategorias();
            Mensagem.sucesso(mensagem, "Categoria criada.");
        }
    }

    @FXML
    private void pagar() {
        TituloPagar t = tabelaTitulos.getSelectionModel().getSelectedItem();
        if (t != null && DialogosFinanceiro.pagamento(t, contaPagarService.contasAtivas(sessao), LocalDate.now(),
                d -> contaPagarService.pagar(sessao, t.id(), d.contaId(), d.data(), d.valor(), d.juros(),
                        d.desconto()))) {
            depoisDoPagamento("Pagamento registrado.");
        }
    }

    @FXML
    private void estornarPagamento() {
        PagamentoTitulo p = tabelaPagamentos.getSelectionModel().getSelectedItem();
        if (p != null && DialogosFinanceiro.motivo("Estornar o pagamento de " + Formatos.moeda(p.valor()),
                motivo -> contaPagarService.estornar(sessao, p.id(), motivo))) {
            depoisDoPagamento("Pagamento estornado: a conta a pagar voltou a ficar em aberto.");
        }
    }

    @FXML
    private void cancelarTitulo() {
        TituloPagar t = tabelaTitulos.getSelectionModel().getSelectedItem();
        if (t != null && DialogosFinanceiro.motivo("Cancelar: " + t.descricao(),
                motivo -> contaPagarService.cancelar(sessao, t.id(), motivo))) {
            carregarTitulos();
            Mensagem.sucesso(mensagem, "Conta a pagar cancelada.");
        }
    }

    // ----- Fluxo de caixa -----

    private void prepararFluxo() {
        Periodo padrao = fluxoService.periodoPadrao();
        fluxoDe.setValue(padrao.inicio());
        fluxoAte.setValue(padrao.fim());
        fluxoAgrupamento.getItems().setAll(Agrupamento.values());
        fluxoAgrupamento.setValue(Agrupamento.SEMANA);
        texto(colunaFluxoPeriodo, FluxoCaixa.Grupo::rotulo);
        texto(colunaFluxoEntradasReal, g -> Formatos.moeda(g.entradasRealizadas()));
        texto(colunaFluxoSaidasReal, g -> Formatos.moeda(g.saidasRealizadas()));
        texto(colunaFluxoEntradasPrev, g -> Formatos.moeda(g.entradasPrevistas()));
        texto(colunaFluxoSaidasPrev, g -> Formatos.moeda(g.saidasPrevistas()));
        colunaFluxoSaldo.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().saldoFinal())));
        colunaFluxoSaldo.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String texto, boolean vazio) {
                super.updateItem(texto, vazio);
                getStyleClass().remove("negativo");
                FluxoCaixa.Grupo g = vazio ? null : getTableRow().getItem();
                setText(vazio ? null : texto);
                if (g != null && g.saldoFinal().signum() < 0) {
                    getStyleClass().add("negativo");
                }
            }
        });
        texto(colunaDetalheParte, l -> l.parte().rotulo());
        texto(colunaDetalheLancamento, FluxoCaixa.Lancamento::descricao);
        texto(colunaDetalheEntradas, l -> l.entradas().signum() == 0 ? "" : Formatos.moeda(l.entradas()));
        texto(colunaDetalheSaidas, l -> l.saidas().signum() == 0 ? "" : Formatos.moeda(l.saidas()));
        tabelaFluxo.getSelectionModel().selectedItemProperty().addListener((o, a, g) -> mostrarDetalheFluxo(g));
        abaFluxo.setOnSelectionChanged(e -> {
            if (abaFluxo.isSelected() && fluxo == null && !botaoFluxo.isDisabled()) {
                carregarFluxo();
            }
        });
    }

    @FXML
    private void gerarFluxo() {
        carregarFluxo();
    }

    /** Calcula em {@link Task}; a tela desabilita "Gerar" enquanto isso. */
    Task<FluxoCaixa> carregarFluxo() {
        Periodo periodo = new Periodo(fluxoDe.getValue(), fluxoAte.getValue());
        Agrupamento agrupamento = fluxoAgrupamento.getValue();
        Task<FluxoCaixa> tarefa = new Task<>() {
            @Override
            protected FluxoCaixa call() {
                return fluxoService.calcular(sessao, periodo, agrupamento);
            }
        };
        progressoFluxo.setVisible(true);
        botaoFluxo.setDisable(true);
        tarefa.setOnSucceeded(e -> {
            progressoFluxo.setVisible(false);
            botaoFluxo.setDisable(false);
            mostrarFluxo(tarefa.getValue());
        });
        tarefa.setOnFailed(e -> {
            progressoFluxo.setVisible(false);
            botaoFluxo.setDisable(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        return Tarefas.executar("fluxo-caixa", tarefa);
    }

    void mostrarFluxo(FluxoCaixa calculado) {
        this.fluxo = calculado;
        Mensagem.limpar(mensagem);
        resumoFluxo.setText("Saldo das contas em " + Formatos.data(calculado.periodo().inicio()) + ": "
                + Formatos.moeda(calculado.saldoInicial()) + " · hoje: " + Formatos.moeda(calculado.saldoAtual())
                + " · projetado para " + Formatos.data(calculado.periodo().fim()) + ": "
                + Formatos.moeda(calculado.saldoFinal()));
        tabelaFluxo.getItems().setAll(calculado.grupos());
        tabelaFluxoDetalhe.getItems().clear();
        XYChart.Series<String, Number> entradas = new XYChart.Series<>();
        entradas.setName("Entradas");
        XYChart.Series<String, Number> saidas = new XYChart.Series<>();
        saidas.setName("Saídas");
        XYChart.Series<String, Number> saldo = new XYChart.Series<>();
        saldo.setName("Saldo");
        for (FluxoCaixa.Grupo g : calculado.grupos()) {
            String rotulo = calculado.agrupamento() == Agrupamento.DIA ? g.inicio().format(DIA_GRAFICO) : g.rotulo();
            entradas.getData().add(new XYChart.Data<>(rotulo, g.entradas()));
            saidas.getData().add(new XYChart.Data<>(rotulo, g.saidas()));
            saldo.getData().add(new XYChart.Data<>(rotulo, g.saldoFinal()));
        }
        graficoFluxo.getData().setAll(List.of(entradas, saidas, saldo));
        calculado.grupos().stream().filter(g -> !g.inicio().isAfter(calculado.hoje())).reduce((a, b) -> b)
                .ifPresent(g -> tabelaFluxo.getSelectionModel().select(g));
    }

    FluxoCaixa fluxo() {
        return fluxo;
    }

    private void mostrarDetalheFluxo(FluxoCaixa.Grupo grupo) {
        if (grupo == null || fluxo == null) {
            tabelaFluxoDetalhe.getItems().clear();
            return;
        }
        tabelaFluxoDetalhe.getItems().setAll(fluxo.lancamentos().stream()
                .filter(l -> l.grupo().equals(grupo.inicio())).toList());
    }
}
