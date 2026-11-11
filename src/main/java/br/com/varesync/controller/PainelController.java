package br.com.varesync.controller;

import br.com.varesync.model.AlertasPainel;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.Lote;
import br.com.varesync.model.DiaPainel;
import br.com.varesync.model.FinanceiroPainel;
import br.com.varesync.model.IndicadoresDia;
import br.com.varesync.model.Painel;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoPainel;
import br.com.varesync.service.PainelService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.Aparencia;
import br.com.varesync.ui.LinhaDeCartoes;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import br.com.varesync.ui.Atalhos;
import br.com.varesync.ui.Atalhos.Tecla;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/**
 * Painel da tela principal (7.5, Fig. 15 revisada). Com RELATORIOS: cartões de hoje (faturamento, nº de vendas,
 * ticket médio, lucro bruto, margem) com a variação em relação ao mesmo dia da semana anterior até a mesma hora,
 * gráfico de faturamento e lucro dos últimos 30 dias e top 5 do mês por lucro. Para todos: alertas clicáveis
 * (cada um só para quem pode abrir a tela a que leva) e atalhos para as rotinas. Carregado em {@link Task}.
 */
public class PainelController {

    private static final DateTimeFormatter DIA_SEMANA = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy", Formatos.PT_BR);
    private static final DateTimeFormatter DIA_CURTO = DateTimeFormatter.ofPattern("EEE dd/MM", Formatos.PT_BR);
    private static final DateTimeFormatter DIA_GRAFICO = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final String NEGATIVO = "negativo";
    private static final String POSITIVO = "positivo";

    private final PainelService painelService;
    private final Sessao sessao;
    private NavegacaoPainel navegacao;
    private Painel painel;

    @FXML private ScrollPane raiz;
    @FXML private Label saudacao;
    @FXML private Label subtitulo;
    @FXML private ProgressIndicator progresso;
    @FXML private Label atualizado;
    @FXML private Button botaoAtualizar;
    @FXML private Label mensagem;
    @FXML private VBox secaoIndicadores;
    @FXML private FlowPane cartoes;
    @FXML private LineChart<String, Number> grafico;
    @FXML private TableView<ProdutoPainel> tabelaTop;
    @FXML private TableColumn<ProdutoPainel, String> colunaTopPosicao;
    @FXML private TableColumn<ProdutoPainel, String> colunaTopProduto;
    @FXML private TableColumn<ProdutoPainel, String> colunaTopLucro;
    @FXML private TableColumn<ProdutoPainel, String> colunaTopMargem;
    @FXML private VBox secaoFinanceiro;
    @FXML private FlowPane cartoesFinanceiro;
    @FXML private VBox alertas;
    @FXML private FlowPane atalhos;

    public PainelController(PainelService painelService, Sessao sessao) {
        this.painelService = painelService;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        saudacao.setText("Olá, " + sessao.usuario().nome() + "!");
        LinhaDeCartoes.distribuir(cartoes);
        LinhaDeCartoes.distribuir(cartoesFinanceiro);
        subtitulo.setText("Carregando o painel…");
        colunaTopPosicao.setCellValueFactory(c ->
                new ReadOnlyStringWrapper(String.valueOf(tabelaTop.getItems().indexOf(c.getValue()) + 1)));
        colunaTopProduto.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome()));
        colunaTopLucro.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formatos.moeda(c.getValue().lucro())));
        colunaTopMargem.setCellValueFactory(c ->
                new ReadOnlyStringWrapper(Formatos.percentual(c.getValue().margem())));
        boolean comIndicadores = sessao.pode(Permissao.RELATORIOS);
        secaoIndicadores.setVisible(comIndicadores);
        secaoIndicadores.setManaged(comIndicadores);
        boolean comFinanceiro = sessao.pode(Permissao.FINANCEIRO);
        secaoFinanceiro.setVisible(comFinanceiro);
        secaoFinanceiro.setManaged(comFinanceiro);
        Atalhos.de(raiz).em(Tecla.de(KeyCode.F5), "Atualizar o painel", this::atualizar);
    }

    /** Liga os alertas e atalhos à janela principal, monta os atalhos e carrega o painel. */
    public void preparar(NavegacaoPainel destino) {
        this.navegacao = destino;
        atalhos.getChildren().clear();
        for (ItemMenu item : ItemMenu.visiveis(sessao)) {
            if (item == ItemMenu.PAINEL || (item.fxml() == null && !item.janelaPropria())) {
                continue; // o próprio painel e as rotinas ainda em construção
            }
            Button botao = new Button(item.rotulo());
            botao.getStyleClass().add("atalho-painel");
            botao.setOnAction(e -> navegacao.abrir(item));
            atalhos.getChildren().add(botao);
        }
        atualizar();
    }

    @FXML
    private void atualizar() {
        carregar();
    }

    /** Carrega em {@link Task}; a tela mostra o indicador e desabilita "Atualizar" enquanto isso. */
    Task<Painel> carregar() {
        long inicio = System.nanoTime();
        Task<Painel> tarefa = new Task<>() {
            @Override
            protected Painel call() {
                return painelService.carregar(sessao);
            }
        };
        progresso.setVisible(true);
        botaoAtualizar.setDisable(true);
        tarefa.setOnSucceeded(e -> {
            progresso.setVisible(false);
            botaoAtualizar.setDisable(false);
            Mensagem.limpar(mensagem);
            mostrar(tarefa.getValue(), (System.nanoTime() - inicio) / 1e9);
        });
        tarefa.setOnFailed(e -> {
            progresso.setVisible(false);
            botaoAtualizar.setDisable(false);
            subtitulo.setText("");
            Mensagem.erro(mensagem, "Não foi possível carregar o painel. "
                    + TratadorExcecoes.mensagem(tarefa.getException()));
        });
        return Tarefas.executar("painel", tarefa);
    }

    void mostrar(Painel carregado, double segundos) {
        this.painel = carregado;
        LocalDate hoje = carregado.atualizadoEm().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        atualizado.setText("Atualizado às " + Formatos.hora(carregado.atualizadoEm()) + " ("
                + Formatos.numero(BigDecimal.valueOf(segundos), 1) + " s)");
        if (carregado.comIndicadores()) {
            subtitulo.setText("Hoje, " + DIA_SEMANA.format(hoje) + ". Variação em relação a "
                    + DIA_SEMANA.format(carregado.diaComparado()) + " até a mesma hora.");
            mostrarCartoes(carregado);
            mostrarGrafico(carregado.ultimos30Dias());
            tabelaTop.getItems().setAll(carregado.topMes());
        } else {
            subtitulo.setText("Hoje, " + DIA_SEMANA.format(hoje) + ". Use os atalhos ou o menu ao lado.");
        }
        secaoFinanceiro.setVisible(carregado.financeiro() != null);
        secaoFinanceiro.setManaged(carregado.financeiro() != null);
        if (carregado.financeiro() != null) {
            mostrarFinanceiro(carregado.financeiro(), hoje);
        }
        mostrarAlertas(carregado.alertas());
    }

    /** Cartões do Financeiro (F15.6): saldo das contas, com o de cada uma, e o resultado líquido do mês até hoje. */
    private void mostrarFinanceiro(FinanceiroPainel f, LocalDate hoje) {
        Label saldo = valorFinanceiro(f.saldoTotal());
        String porConta = f.contas().stream().map(c -> c.nome() + ": " + Formatos.moeda(c.saldo()))
                .reduce((a, b) -> a + " · " + b).orElse("Nenhuma conta ativa");
        Label resultado = valorFinanceiro(f.resultadoMes());
        cartoesFinanceiro.getChildren().setAll(
                cartaoFinanceiro("Saldo das contas", saldo, porConta),
                cartaoFinanceiro("Resultado líquido do mês", resultado, "DRE de " + DIA_MES.format(f.inicioMes())
                        + " a " + DIA_MES.format(hoje) + " (despesas pela competência)"));
    }

    private static Label valorFinanceiro(BigDecimal valor) {
        Label numero = new Label(Formatos.moeda(valor));
        numero.getStyleClass().add("cartao-valor");
        if (valor.signum() < 0) {
            numero.getStyleClass().add(NEGATIVO);
        }
        return numero;
    }

    private static VBox cartaoFinanceiro(String rotulo, Label valor, String detalhe) {
        Label titulo = new Label(rotulo);
        titulo.getStyleClass().add("cartao-rotulo");
        Label base = new Label(detalhe);
        base.getStyleClass().add("cartao-base");
        base.setWrapText(true);
        VBox cartao = new VBox(2, titulo, valor, base);
        cartao.getStyleClass().addAll("cartao-painel", Aparencia.LARGURA_FIXA);
        cartao.setMaxWidth(Double.MAX_VALUE);
        return cartao;
    }

    Painel painel() {
        return painel;
    }

    // ----- Cartões -----

    private void mostrarCartoes(Painel p) {
        IndicadoresDia hoje = p.hoje();
        IndicadoresDia antes = p.semanaPassada();
        String comparado = DIA_CURTO.format(p.diaComparado());
        cartoes.getChildren().setAll(
                cartao("Faturamento", Formatos.moeda(hoje.receita()), Formatos.moeda(antes.receita()),
                        IndicadoresDia.variacao(hoje.receita(), antes.receita()), false, comparado),
                cartao("Vendas", String.valueOf(hoje.vendas()), String.valueOf(antes.vendas()),
                        IndicadoresDia.variacao(BigDecimal.valueOf(hoje.vendas()), BigDecimal.valueOf(antes.vendas())),
                        false, comparado),
                cartao("Ticket médio", Formatos.moeda(hoje.ticketMedio()), Formatos.moeda(antes.ticketMedio()),
                        IndicadoresDia.variacao(hoje.ticketMedio(), antes.ticketMedio()), false, comparado),
                cartao("Lucro bruto", Formatos.moeda(hoje.lucro()), Formatos.moeda(antes.lucro()),
                        IndicadoresDia.variacao(hoje.lucro(), antes.lucro()), false, comparado),
                cartao("Margem", Formatos.percentual(hoje.margem()), Formatos.percentual(antes.margem()),
                        IndicadoresDia.variacaoEmPontos(hoje.margem(), antes.margem()), true, comparado));
    }

    /**
     * Um cartão: rótulo, valor de hoje, variação com seta e cor (subiu verde, caiu vermelho) e o valor de
     * comparação. Sem base (zero ou sem venda na semana anterior), a variação é "—".
     */
    private static VBox cartao(String rotulo, String valor, String valorAntes, Optional<BigDecimal> variacao,
                               boolean emPontos, String comparado) {
        Label titulo = new Label(rotulo);
        titulo.getStyleClass().add("cartao-rotulo");
        Label numero = new Label(valor);
        numero.getStyleClass().add("cartao-valor");
        Label delta = new Label(variacao.map(v -> textoVariacao(v, emPontos)).orElse(Formatos.SEM_VALOR));
        delta.getStyleClass().add("cartao-variacao");
        variacao.filter(v -> v.signum() != 0)
                .ifPresent(v -> delta.getStyleClass().add(v.signum() < 0 ? NEGATIVO : POSITIVO));
        Label base = new Label("em " + comparado + ": " + valorAntes);
        base.getStyleClass().add("cartao-base");
        VBox cartao = new VBox(2, titulo, numero, delta, base);
        cartao.getStyleClass().addAll("cartao-painel", Aparencia.LARGURA_FIXA);
        cartao.setMaxWidth(Double.MAX_VALUE);
        return cartao;
    }

    /** "▲ 12,50%", "▼ 3,10 p.p.", "= 0,00%". */
    static String textoVariacao(BigDecimal variacao, boolean emPontos) {
        String seta = variacao.signum() > 0 ? "▲ " : variacao.signum() < 0 ? "▼ " : "= ";
        String numero = Formatos.numero(variacao.abs(), 2);
        return seta + (emPontos ? numero + " p.p." : numero + "%");
    }

    // ----- Gráfico e top 5 -----

    private void mostrarGrafico(List<DiaPainel> dias) {
        XYChart.Series<String, Number> faturamento = serie("Faturamento", dias, DiaPainel::receita);
        XYChart.Series<String, Number> lucro = serie("Lucro bruto", dias, DiaPainel::lucro);
        grafico.getData().setAll(List.of(faturamento, lucro));
        for (XYChart.Series<String, Number> serie : grafico.getData()) {
            for (XYChart.Data<String, Number> ponto : serie.getData()) {
                if (ponto.getNode() != null) {
                    Tooltip.install(ponto.getNode(), new Tooltip(serie.getName() + " em " + ponto.getXValue() + ": "
                            + Formatos.moeda((BigDecimal) ponto.getYValue())));
                }
            }
        }
    }

    private static XYChart.Series<String, Number> serie(String nome, List<DiaPainel> dias,
                                                        Function<DiaPainel, BigDecimal> valor) {
        XYChart.Series<String, Number> serie = new XYChart.Series<>();
        serie.setName(nome);
        for (DiaPainel dia : dias) {
            serie.getData().add(new XYChart.Data<>(DIA_GRAFICO.format(dia.dia()), valor.apply(dia)));
        }
        return serie;
    }

    // ----- Alertas -----

    private void mostrarAlertas(AlertasPainel a) {
        alertas.getChildren().clear();
        if (a.abaixoDoMinimo() != null && a.abaixoDoMinimo() > 0) {
            alerta(plural(a.abaixoDoMinimo(), "produto está", "produtos estão")
                    + " abaixo do estoque mínimo — ver no Estoque", () -> navegacao.abrirEstoqueAbaixoDoMinimo());
        }
        if (a.precoAbaixoDoCusto() != null && a.precoAbaixoDoCusto() > 0) {
            alerta(plural(a.precoAbaixoDoCusto(), "produto tem", "produtos têm")
                    + " preço de venda abaixo do custo (cada venda dá prejuízo) — ver em Produtos",
                    () -> navegacao.abrirProdutosAbaixoDoCusto());
        }
        if (a.etiquetasPendentes() != null && a.etiquetasPendentes() > 0) {
            alerta(plural(a.etiquetasPendentes(), "produto com etiqueta pendente", "produtos com etiqueta pendente")
                    + " (sem etiqueta ou com preço novo) — imprimir nas Etiquetas",
                    () -> navegacao.abrirEtiquetasPendentes());
        }
        if (a.cargaPendente() != null && a.cargaPendente() > 0) {
            alerta("Carga de balança pendente (" + plural(a.cargaPendente(), "produto novo ou alterado",
                    "produtos novos ou alterados") + ") — gerar na Carga de balança",
                    () -> navegacao.abrirCargaBalanca());
        }
        if (a.inconsistenciasFiscais() != null && a.inconsistenciasFiscais() > 0) {
            alerta(plural(a.inconsistenciasFiscais(), "produto com inconsistência fiscal",
                    "produtos com inconsistência fiscal") + " — conferir o cadastro fiscal",
                    () -> navegacao.abrirInconsistenciasFiscais());
        }
        if (a.caixaAberto().isPresent()) {
            CaixaSessao caixa = a.caixaAberto().get();
            alerta("O caixa nº " + caixa.id() + " deste terminal está aberto desde "
                    + Formatos.dataHora(caixa.abertoEm()) + " (aberto por " + caixa.abertoPorNome() + ") — ver no Caixa",
                    () -> navegacao.abrir(ItemMenu.CAIXA));
        }
        if (a.inventariosAbertos() != null && a.inventariosAbertos() > 0) {
            alerta(a.inventariosAbertos() == 1 ? "1 inventário está aberto — ver no Inventário"
                    : a.inventariosAbertos() + " inventários estão abertos — ver no Inventário",
                    () -> navegacao.abrir(ItemMenu.INVENTARIO));
        }
        if (a.backupAtrasado() != null) {
            AlertasPainel.BackupAtrasado b = a.backupAtrasado();
            alerta((b.ultimo() == null ? "Nenhum backup do banco foi feito ainda"
                    : "O último backup do banco foi há " + b.dias() + " dias (" + Formatos.dataHora(b.ultimo()) + ")")
                    + " — fazer em Configurações › Backup", () -> navegacao.abrirBackup());
        }
        if (a.titulosVencidos() != null && a.titulosVencidos().quantidade() > 0) {
            AlertasPainel.TitulosVencidos v = a.titulosVencidos();
            String texto = plural(v.quantidade(), "título vencido", "títulos vencidos") + " ("
                    + Formatos.moeda(v.valor()) + ")";
            // Quem recebe no caixa pode não ter Relatórios: leva à tela que a pessoa pode abrir.
            if (sessao.pode(Permissao.RELATORIOS)) {
                alerta(texto + " — ver em Relatórios", () -> navegacao.abrirTitulosVencidos());
            } else if (sessao.pode(Permissao.PDV)) {
                alerta(texto + " — receber no Caixa", () -> navegacao.abrir(ItemMenu.CAIXA));
            } else {
                Label aviso = new Label(texto);
                aviso.getStyleClass().add("alerta-painel");
                alertas.getChildren().add(aviso);
            }
        }
        if (a.rupturas() != null && a.rupturas() > 0) {
            alerta(plural(a.rupturas(), "produto está", "produtos estão") + " em ruptura (sem estoque e com venda nos "
                    + "últimos 30 dias) — ver na Sugestão de compra", () -> navegacao.abrirRupturas());
        }
        if (a.pagarVencidas() != null && a.pagarVencidas().quantidade() > 0) {
            alerta(plural(a.pagarVencidas().quantidade(), "conta a pagar vencida", "contas a pagar vencidas") + " ("
                    + Formatos.moeda(a.pagarVencidas().valor()) + ") — ver no Financeiro",
                    () -> navegacao.abrirContasAPagar(null, painel.atualizadoEm()
                            .atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().minusDays(1)));
        }
        if (a.pagarHoje() != null && a.pagarHoje().quantidade() > 0) {
            LocalDate hoje = painel.atualizadoEm().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
            alerta(plural(a.pagarHoje().quantidade(), "conta a pagar vence", "contas a pagar vencem") + " hoje ("
                    + Formatos.moeda(a.pagarHoje().valor()) + ") — ver no Financeiro",
                    () -> navegacao.abrirContasAPagar(hoje, hoje));
        }
        if (a.lotes() != null) {
            AlertasPainel.Lotes l = a.lotes();
            String destino = sessao.pode(Permissao.RELATORIOS) ? " — ver no relatório de validade" : " — ver no Estoque";
            if (l.vencendo() > 0) {
                alerta(plural(l.vencendo(), "lote vence", "lotes vencem") + " em até " + l.dias()
                        + (l.dias() == 1 ? " dia" : " dias") + destino,
                        () -> navegacao.abrirValidade(l.dias(), false));
            }
            if (l.vencidos() > 0) {
                alerta(plural(l.vencidos(), "lote vencido", "lotes vencidos") + " com saldo (fazer a perda por "
                        + "vencimento)" + destino, () -> navegacao.abrirValidade(l.dias(), true));
            }
            if (l.semLoteNegativo() > 0) {
                alerta(plural(l.semLoteNegativo(), "produto", "produtos") + " com saldo negativo no "
                        + Lote.SEM_LOTE + " — ver no Estoque", () -> navegacao.abrirSemLoteNegativo());
            }
        }
        for (ContaFinanceira c : a.contasNegativas()) {
            alerta("A conta " + c.nome() + " está com saldo negativo (" + Formatos.moeda(c.saldo())
                    + ") — ver no Financeiro", () -> navegacao.abrirContasFinanceiras());
        }
        if (alertas.getChildren().isEmpty()) {
            Label nenhum = new Label("Nenhum alerta no momento.");
            nenhum.getStyleClass().add("subtitulo");
            alertas.getChildren().add(nenhum);
        }
    }

    private void alerta(String texto, Runnable acao) {
        Hyperlink link = new Hyperlink(texto);
        link.getStyleClass().add("alerta-painel");
        link.setOnAction(e -> acao.run());
        alertas.getChildren().add(link);
    }

    private static String plural(int quantidade, String um, String varios) {
        return quantidade + " " + (quantidade == 1 ? um : varios);
    }
}
