package br.com.varesync.controller;

import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.report.Agrupamento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.Criterio;
import br.com.varesync.report.ExportadorCsv;
import br.com.varesync.report.FiltroCrediario;
import br.com.varesync.report.FiltroFinanceiro;
import br.com.varesync.report.FiltroValidade;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.RelTitulosReceber;
import br.com.varesync.report.RelValidade;
import br.com.varesync.report.Relatorio;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.SituacaoTitulo;
import br.com.varesync.report.TipoColuna;
import br.com.varesync.report.TipoFiltro;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RelatorioService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Exportacao;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Tela genérica de relatórios (7.4, Fig. 22 revisada): escolher o relatório → só os filtros dele → Gerar (em
 * {@link Task}) → tabela com colunas ordenáveis e a linha de totais → Exportar CSV ou PDF. As colunas, a
 * formatação e os totais vêm da definição do relatório ({@link Coluna}).
 */
public class RelatoriosController {

    static final Opcao TODOS = new Opcao(0, "Todos");
    static final Opcao TODAS = new Opcao(0, "Todas");
    private static final String NEGATIVO = "negativo";
    private static final double PIXELS_POR_LARGURA = 72;
    /** Meses que a escolha do R25 oferece, do atual para trás. */
    static final int MESES_DO_DRE = 24;
    private static final DateTimeFormatter NOME_DO_MES = DateTimeFormatter.ofPattern("MMMM 'de' uuuu", Formatos.PT_BR);

    private final RelatorioService relatorioService;
    private final RelatorioPdf relatorioPdf;
    private final Sessao sessao;
    private final List<TipoMovimento> tipos = new ArrayList<>();
    private ResultadoRelatorio<?> resultado;

    @FXML private VBox raiz;
    @FXML private ComboBox<Relatorio<?>> escolhaRelatorio;
    @FXML private Label descricao;
    @FXML private HBox grupoPeriodo;
    @FXML private DatePicker dataInicio;
    @FXML private DatePicker dataFim;
    @FXML private HBox grupoOperador;
    @FXML private ComboBox<Opcao> escolhaOperador;
    @FXML private HBox grupoCategoria;
    @FXML private ComboBox<Opcao> escolhaCategoria;
    @FXML private HBox grupoFornecedor;
    @FXML private ComboBox<Opcao> escolhaFornecedor;
    @FXML private HBox grupoProduto;
    @FXML private TextField campoProduto;
    @FXML private HBox grupoTipo;
    @FXML private ComboBox<String> escolhaTipo;
    @FXML private HBox grupoCriterio;
    @FXML private ComboBox<Criterio> escolhaCriterio;
    @FXML private HBox grupoTopN;
    @FXML private TextField campoTopN;
    @FXML private HBox grupoDias;
    @FXML private TextField campoDias;
    @FXML private HBox grupoSituacao;
    @FXML private ComboBox<SituacaoTitulo> escolhaSituacao;
    @FXML private HBox grupoCliente;
    @FXML private TextField campoCliente;
    @FXML private HBox grupoVencimento;
    @FXML private DatePicker vencimentoDe;
    @FXML private DatePicker vencimentoAte;
    @FXML private HBox grupoForma;
    @FXML private ComboBox<Opcao> escolhaForma;
    @FXML private HBox grupoCategoriaDespesa;
    @FXML private ComboBox<Opcao> escolhaCategoriaDespesa;
    @FXML private HBox grupoAgrupamento;
    @FXML private ComboBox<Agrupamento> escolhaAgrupamento;
    @FXML private HBox grupoMes;
    @FXML private ComboBox<YearMonth> escolhaMes;
    @FXML private CheckBox filtroAbaixoMinimo;
    @FXML private CheckBox filtroInativos;
    @FXML private CheckBox filtroDetalhar;
    @FXML private HBox grupoValidade;
    @FXML private TextField campoDiasValidade;
    @FXML private CheckBox filtroVencidos;
    @FXML private Button botaoGerar;
    @FXML private ProgressIndicator progresso;
    @FXML private Label mensagem;
    @FXML private Button botaoCsv;
    @FXML private Button botaoPdf;
    @FXML private TableView<Object> tabela;
    @FXML private Label semDados;
    @FXML private HBox linhaTotais;

    public RelatoriosController(RelatorioService relatorioService, RelatorioPdf relatorioPdf, Sessao sessao) {
        this.relatorioService = relatorioService;
        this.relatorioPdf = relatorioPdf;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        escolhaRelatorio.setConverter(new StringConverter<>() {
            @Override
            public String toString(Relatorio<?> relatorio) {
                return relatorio == null ? "" : relatorio.titulo();
            }

            @Override
            public Relatorio<?> fromString(String texto) {
                return null;
            }
        });
        escolhaRelatorio.getItems().setAll(relatorioService.disponiveis(sessao));
        escolhaRelatorio.valueProperty().addListener((obs, anterior, escolhido) -> mostrarFiltros(escolhido));

        dataInicio.setConverter(new ConversorData());
        dataFim.setConverter(new ConversorData());
        Periodo padrao = relatorioService.periodoPadrao();
        dataInicio.setValue(padrao.inicio());
        dataFim.setValue(padrao.fim());

        RelatorioService.OpcoesFiltro opcoes = relatorioService.opcoes(sessao);
        escolhaOperador.getItems().add(TODOS);
        escolhaOperador.getItems().addAll(opcoes.operadores());
        escolhaOperador.setValue(TODOS);
        escolhaCategoria.getItems().add(TODAS);
        escolhaCategoria.getItems().addAll(opcoes.categorias());
        escolhaCategoria.setValue(TODAS);
        escolhaFornecedor.getItems().add(TODOS);
        escolhaFornecedor.getItems().addAll(opcoes.fornecedores());
        escolhaFornecedor.setValue(TODOS);
        escolhaForma.getItems().add(TODAS);
        escolhaForma.getItems().addAll(opcoes.formas());
        escolhaForma.setValue(TODAS);
        escolhaCategoriaDespesa.getItems().add(TODAS);
        escolhaCategoriaDespesa.getItems().addAll(opcoes.categoriasDespesa());
        escolhaCategoriaDespesa.setValue(TODAS);
        escolhaAgrupamento.getItems().setAll(Agrupamento.values());
        escolhaAgrupamento.setValue(Agrupamento.DIA);
        YearMonth atual = YearMonth.from(padrao.fim());
        for (int i = 0; i < MESES_DO_DRE; i++) {
            escolhaMes.getItems().add(atual.minusMonths(i));
        }
        escolhaMes.setConverter(new StringConverter<>() {
            @Override
            public String toString(YearMonth mes) {
                return mes == null ? "" : NOME_DO_MES.format(mes);
            }

            @Override
            public YearMonth fromString(String texto) {
                return null;
            }
        });
        escolhaMes.setValue(atual);
        escolhaSituacao.getItems().setAll(SituacaoTitulo.values());
        escolhaSituacao.setValue(SituacaoTitulo.EM_ABERTO);
        vencimentoDe.setConverter(new ConversorData());
        vencimentoAte.setConverter(new ConversorData());
        campoTopN.setText("20");
        campoDias.setText(String.valueOf(relatorioService.diasSemGiroPadrao(sessao)));
        campoDiasValidade.setText(String.valueOf(relatorioService.validadeAlertaDias(sessao)));
        filtroVencidos.setSelected(true);

        escolhaRelatorio.getSelectionModel().select(0);
    }

    /** Alerta do painel (F14.4): R21 já filtrado nos títulos vencidos e gerado. */
    public void mostrarTitulosVencidos() {
        escolhaRelatorio.getItems().stream().filter(r -> r instanceof RelTitulosReceber).findFirst()
                .ifPresent(r -> {
                    escolhaRelatorio.setValue(r);
                    escolhaSituacao.setValue(SituacaoTitulo.VENCIDOS);
                    gerar();
                });
    }

    /**
     * Alerta do painel (F17.3): R29 já filtrado e gerado, com os lotes que vencem em até {@code dias} dias e, se
     * {@code vencidos}, os já vencidos.
     */
    public void mostrarValidade(int dias, boolean vencidos) {
        escolhaRelatorio.getItems().stream().filter(r -> r instanceof RelValidade).findFirst()
                .ifPresent(r -> {
                    escolhaRelatorio.setValue(r);
                    campoDiasValidade.setText(String.valueOf(dias));
                    filtroVencidos.setSelected(vencidos);
                    gerar();
                });
    }

    /** Mostra só os filtros do relatório escolhido (7.4) e limpa o resultado anterior. */
    private void mostrarFiltros(Relatorio<?> relatorio) {
        Set<TipoFiltro> usados = relatorio == null ? Set.of() : relatorio.filtros();
        Map<Node, TipoFiltro> grupos = Map.ofEntries(
                Map.entry(grupoPeriodo, TipoFiltro.PERIODO),
                Map.entry(grupoOperador, TipoFiltro.OPERADOR),
                Map.entry(grupoCategoria, TipoFiltro.CATEGORIA),
                Map.entry(grupoFornecedor, TipoFiltro.FORNECEDOR),
                Map.entry(grupoProduto, TipoFiltro.PRODUTO),
                Map.entry(grupoTipo, TipoFiltro.TIPO_MOVIMENTO),
                Map.entry(grupoCriterio, TipoFiltro.CRITERIO),
                Map.entry(grupoTopN, TipoFiltro.TOP_N),
                Map.entry(grupoDias, TipoFiltro.DIAS),
                Map.entry(grupoSituacao, TipoFiltro.SITUACAO_TITULO),
                Map.entry(grupoCliente, TipoFiltro.CLIENTE),
                Map.entry(grupoVencimento, TipoFiltro.VENCIMENTO),
                Map.entry(grupoForma, TipoFiltro.FORMA_PAGAMENTO),
                Map.entry(grupoCategoriaDespesa, TipoFiltro.CATEGORIA_DESPESA),
                Map.entry(grupoAgrupamento, TipoFiltro.AGRUPAMENTO),
                Map.entry(grupoMes, TipoFiltro.MES),
                Map.entry(filtroAbaixoMinimo, TipoFiltro.SO_ABAIXO_MINIMO),
                Map.entry(filtroInativos, TipoFiltro.INCLUIR_INATIVOS),
                Map.entry(grupoValidade, TipoFiltro.VALIDADE),
                Map.entry(filtroDetalhar, TipoFiltro.DETALHAR_VENDAS));
        grupos.forEach((no, filtro) -> {
            boolean visivel = usados.contains(filtro);
            no.setVisible(visivel);
            no.setManaged(visivel);
        });
        if (relatorio != null) {
            opcoesDoRelatorio(relatorio);
        }
        descricao.setText(relatorio == null ? "" : relatorio.descricao());
        limparResultado();
        Mensagem.limpar(mensagem);
    }

    /** Critérios e tipos de movimento que o relatório aceita; mantém a escolha anterior se ela ainda vale. */
    private void opcoesDoRelatorio(Relatorio<?> relatorio) {
        Criterio criterio = escolhaCriterio.getValue();
        escolhaCriterio.getItems().setAll(relatorio.criterios());
        escolhaCriterio.setValue(criterio != null && relatorio.criterios().contains(criterio) ? criterio
                : relatorio.criterios().contains(Criterio.RECEITA) ? Criterio.RECEITA : relatorio.criterios().get(0));

        TipoMovimento tipo = tipoEscolhido();
        tipos.clear();
        tipos.addAll(relatorio.tiposMovimento());
        escolhaTipo.getItems().setAll("Todos");
        tipos.forEach(t -> escolhaTipo.getItems().add(t.descricao()));
        escolhaTipo.getSelectionModel().select(tipos.contains(tipo) ? tipos.indexOf(tipo) + 1 : 0);
    }

    private TipoMovimento tipoEscolhido() {
        int indice = escolhaTipo.getSelectionModel().getSelectedIndex();
        return indice <= 0 || indice > tipos.size() ? null : tipos.get(indice - 1);
    }

    private void limparResultado() {
        resultado = null;
        tabela.getColumns().clear();
        tabela.getItems().clear();
        linhaTotais.getChildren().clear();
        semDados.setText("Escolha os filtros e clique em Gerar.");
        botaoCsv.setDisable(true);
        botaoPdf.setDisable(true);
    }

    /** Filtros da tela; o produto digitado é procurado pelo código (rápido, na própria thread da tela). */
    Filtros filtros(Relatorio<?> relatorio) {
        Set<TipoFiltro> usados = relatorio.filtros();
        Filtros f = Filtros.vazios();
        if (usados.contains(TipoFiltro.PERIODO)) {
            f = f.comPeriodo(new Periodo(dataInicio.getValue(), dataFim.getValue()));
        }
        if (usados.contains(TipoFiltro.OPERADOR)) {
            f = f.comOperador(nuloSeTodos(escolhaOperador.getValue()));
        }
        if (usados.contains(TipoFiltro.CATEGORIA)) {
            f = f.comCategoria(nuloSeTodos(escolhaCategoria.getValue()));
        }
        if (usados.contains(TipoFiltro.FORNECEDOR)) {
            f = f.comFornecedor(nuloSeTodos(escolhaFornecedor.getValue()));
        }
        if (usados.contains(TipoFiltro.PRODUTO) && !Textos.vazio(campoProduto.getText())) {
            Opcao produto = relatorioService.produto(sessao, campoProduto.getText());
            f = f.comProduto(produto);
        }
        if (usados.contains(TipoFiltro.TIPO_MOVIMENTO)) {
            f = f.comTipo(tipoEscolhido());
        }
        if (usados.contains(TipoFiltro.CRITERIO)) {
            f = f.comCriterio(escolhaCriterio.getValue());
        }
        if (usados.contains(TipoFiltro.TOP_N)) {
            f = f.comRanking(topN(), f.criterio());
        }
        if (usados.contains(TipoFiltro.DIAS)) {
            f = f.comDias(dias());
        }
        f = f.comCrediario(new FiltroCrediario(
                usados.contains(TipoFiltro.SITUACAO_TITULO) ? escolhaSituacao.getValue() : null,
                usados.contains(TipoFiltro.CLIENTE) ? campoCliente.getText() : null,
                usados.contains(TipoFiltro.VENCIMENTO) ? vencimentoDe.getValue() : null,
                usados.contains(TipoFiltro.VENCIMENTO) ? vencimentoAte.getValue() : null,
                usados.contains(TipoFiltro.FORMA_PAGAMENTO) ? nuloSeTodos(escolhaForma.getValue()) : null));
        f = f.comFinanceiro(new FiltroFinanceiro(
                usados.contains(TipoFiltro.CATEGORIA_DESPESA) ? nuloSeTodos(escolhaCategoriaDespesa.getValue()) : null,
                usados.contains(TipoFiltro.AGRUPAMENTO) ? escolhaAgrupamento.getValue() : null,
                usados.contains(TipoFiltro.MES) ? escolhaMes.getValue() : null));
        if (usados.contains(TipoFiltro.VALIDADE)) {
            f = f.comValidade(new FiltroValidade(diasValidade(), filtroVencidos.isSelected()));
        }
        f = f.comEstoque(filtroAbaixoMinimo.isSelected(), filtroInativos.isSelected());
        return f.comDetalhe(filtroDetalhar.isSelected());
    }

    private Integer topN() {
        String texto = Textos.normalizarEspacos(campoTopN.getText());
        if (texto.isEmpty()) {
            return null;
        }
        if (!Textos.soDigitos(texto) || texto.length() > 6) {
            throw new NegocioException("Informe quantos produtos mostrar no ranking (ou deixe vazio para todos).");
        }
        return Integer.parseInt(texto);
    }

    /** R29: vazio = o padrão da configuração ({@code validade.alerta_dias}). */
    private Integer diasValidade() {
        String texto = Textos.normalizarEspacos(campoDiasValidade.getText());
        if (texto.isEmpty()) {
            return null;
        }
        if (!Textos.soDigitos(texto) || texto.length() > 4) {
            throw new NegocioException("Informe em quantos dias os lotes vencem (número inteiro).");
        }
        return Integer.parseInt(texto);
    }

    /** Vazio = o padrão da configuração ({@code relatorio.dias_sem_giro}). */
    private Integer dias() {
        String texto = Textos.normalizarEspacos(campoDias.getText());
        if (texto.isEmpty()) {
            return null;
        }
        if (!Textos.soDigitos(texto) || texto.length() > 6) {
            throw new NegocioException("Informe os dias sem venda (número inteiro).");
        }
        return Integer.parseInt(texto);
    }

    private static Opcao nuloSeTodos(Opcao opcao) {
        return opcao == null || opcao.id() == 0 ? null : opcao;
    }

    @FXML
    private void gerar() {
        Relatorio<?> relatorio = escolhaRelatorio.getValue();
        if (relatorio == null) {
            return;
        }
        Filtros filtros;
        try {
            filtros = filtros(relatorio);
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
            return;
        }
        long inicio = System.nanoTime();
        Task<ResultadoRelatorio<?>> tarefa = new Task<>() {
            @Override
            protected ResultadoRelatorio<?> call() {
                return relatorioService.gerar(sessao, relatorio, filtros);
            }
        };
        ocupado(true);
        limparResultado();
        semDados.setText("Gerando…");
        Mensagem.informar(mensagem, "Gerando o relatório…");
        tarefa.setOnSucceeded(e -> {
            ocupado(false);
            mostrar(tarefa.getValue());
            double segundos = (System.nanoTime() - inicio) / 1e9;
            int linhas = tarefa.getValue().linhas().size();
            Mensagem.informar(mensagem, (linhas == 1 ? "1 linha" : Formatos.numero(BigDecimal.valueOf(linhas), 0)
                    + " linhas") + " · gerado em " + Formatos.numero(BigDecimal.valueOf(segundos), 1) + " s");
        });
        tarefa.setOnFailed(e -> {
            ocupado(false);
            semDados.setText("Não foi possível gerar o relatório.");
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("relatorio", tarefa);
    }

    private void ocupado(boolean sim) {
        progresso.setVisible(sim);
        botaoGerar.setDisable(sim);
        escolhaRelatorio.setDisable(sim);
    }

    /** Monta as colunas a partir da definição do relatório e a linha de totais alinhada a elas. */
    @SuppressWarnings("unchecked")
    void mostrar(ResultadoRelatorio<?> gerado) {
        this.resultado = gerado;
        ResultadoRelatorio<Object> r = (ResultadoRelatorio<Object>) gerado;
        semDados.setText("Nenhum registro para os filtros escolhidos.");
        List<String> totais = r.temTotais() ? r.totais() : List.of();
        List<TableColumn<Object, ?>> colunas = new ArrayList<>();
        for (int i = 0; i < r.colunas().size(); i++) {
            Coluna<Object> coluna = r.colunas().get(i);
            TableColumn<Object, Object> tc = new TableColumn<>(coluna.titulo());
            tc.setPrefWidth(coluna.largura() * PIXELS_POR_LARGURA);
            tc.setMinWidth(coluna.largura() * PIXELS_POR_LARGURA * 0.6);
            tc.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(coluna.valorDe(c.getValue())));
            tc.setComparator(coluna.comparador());
            tc.setCellFactory(t -> celula(coluna));
            switch (coluna.alinhamento()) {
                case DIREITA -> tc.getStyleClass().add("coluna-numero");
                case CENTRO -> tc.getStyleClass().add("coluna-centro");
                case ESQUERDA -> { }
            }
            colunas.add(tc);

            if (!totais.isEmpty()) {
                // Na tela, só "Total": a quantidade de linhas já está na mensagem e a coluna pode ser estreita.
                Label total = new Label(totais.get(i).startsWith("Total (") ? "Total" : totais.get(i));
                total.getStyleClass().add("total-relatorio");
                total.setMaxWidth(Double.MAX_VALUE);
                total.prefWidthProperty().bind(tc.widthProperty());
                total.minWidthProperty().bind(tc.widthProperty());
                total.setAlignment(switch (coluna.alinhamento()) {
                    case ESQUERDA -> Pos.CENTER_LEFT;
                    case CENTRO -> Pos.CENTER;
                    case DIREITA -> Pos.CENTER_RIGHT;
                });
                if (totais.get(i).startsWith("-")) {
                    total.getStyleClass().add(NEGATIVO);
                }
                linhaTotais.getChildren().add(total);
            }
        }
        tabela.getColumns().setAll(colunas);
        tabela.getItems().setAll(r.linhas());
        botaoCsv.setDisable(false);
        botaoPdf.setDisable(false);
    }

    private static TableCell<Object, Object> celula(Coluna<Object> coluna) {
        return new TableCell<>() {
            @Override
            protected void updateItem(Object valor, boolean vazio) {
                super.updateItem(valor, vazio);
                getStyleClass().remove(NEGATIVO);
                Object linha = getTableRow() == null ? null : getTableRow().getItem();
                if (vazio || linha == null) {
                    setText(null);
                    return;
                }
                setText(coluna.formatar(linha));
                if (valor instanceof BigDecimal numero && numero.signum() < 0 && coluna.tipo() != TipoColuna.TEXTO) {
                    getStyleClass().add(NEGATIVO);
                }
            }
        };
    }

    @FXML
    private void exportarCsv() {
        escolherEExportar("CSV", "csv");
    }

    @FXML
    private void exportarPdf() {
        if (resultado != null && confirmarPdfGrande(resultado)) {
            escolherEExportar("PDF", "pdf");
        }
    }

    /** Acima de {@value RelatorioPdf#PAGINAS_PARA_CONFIRMAR} páginas estimadas, pede confirmação (F10.7). */
    static boolean confirmarPdfGrande(ResultadoRelatorio<?> r) {
        return RelatorioPdf.avisoPdfGrande(r).map(Dialogos::confirmar).orElse(true);
    }

    private void escolherEExportar(String formato, String extensao) {
        if (resultado != null) {
            Exportacao.escolher(raiz.getScene().getWindow(), formato, extensao,
                    nomeArquivo(resultado, "." + extensao)).ifPresent(destino -> exportar(formato, destino));
        }
    }

    /** Grava o relatório da tela em CSV ou PDF, em {@link Task}. */
    Task<Void> exportar(String formato, Path destino) {
        ResultadoRelatorio<?> atual = resultado;
        Consumer<Path> gravacao = "CSV".equals(formato) ? d -> ExportadorCsv.salvar(atual, d)
                : d -> relatorioPdf.salvar(atual, d);
        return Exportacao.gravar(formato, destino, gravacao, mensagem, List.of(botaoCsv, botaoPdf));
    }

    /** "R01_vendas_por_periodo_2026-10-17.csv". */
    static String nomeArquivo(ResultadoRelatorio<?> r, String extensao) {
        String nome = Normalizer.normalize(r.relatorio().nome(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        LocalDate dia = r.emitidoEm().toLocalDate();
        return r.relatorio().codigo() + "_" + nome + "_" + dia + extensao;
    }
}
