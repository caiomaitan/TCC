package br.com.varesync.controller;

import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.Criterio;
import br.com.varesync.report.ExportadorCsv;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.Relatorio;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.TipoColuna;
import br.com.varesync.report.TipoFiltro;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.RelatorioService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
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
import javafx.stage.FileChooser;
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
    @FXML private HBox grupoProduto;
    @FXML private TextField campoProduto;
    @FXML private HBox grupoTipo;
    @FXML private ComboBox<String> escolhaTipo;
    @FXML private HBox grupoRanking;
    @FXML private ComboBox<Criterio> escolhaCriterio;
    @FXML private TextField campoTopN;
    @FXML private CheckBox filtroAbaixoMinimo;
    @FXML private CheckBox filtroInativos;
    @FXML private CheckBox filtroDetalhar;
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
        escolhaTipo.getItems().add("Todos");
        for (TipoMovimento tipo : TipoMovimento.values()) {
            tipos.add(tipo);
            escolhaTipo.getItems().add(tipo.descricao());
        }
        escolhaTipo.getSelectionModel().select(0);
        escolhaCriterio.getItems().setAll(Criterio.values());
        escolhaCriterio.setValue(Criterio.RECEITA);
        campoTopN.setText("20");

        escolhaRelatorio.getSelectionModel().select(0);
    }

    /** Mostra só os filtros do relatório escolhido (7.4) e limpa o resultado anterior. */
    private void mostrarFiltros(Relatorio<?> relatorio) {
        Set<TipoFiltro> usados = relatorio == null ? Set.of() : relatorio.filtros();
        Map<Node, Boolean> visiveis = Map.of(
                grupoPeriodo, usados.contains(TipoFiltro.PERIODO),
                grupoOperador, usados.contains(TipoFiltro.OPERADOR),
                grupoCategoria, usados.contains(TipoFiltro.CATEGORIA),
                grupoProduto, usados.contains(TipoFiltro.PRODUTO),
                grupoTipo, usados.contains(TipoFiltro.TIPO_MOVIMENTO),
                grupoRanking, usados.contains(TipoFiltro.TOP_N) || usados.contains(TipoFiltro.CRITERIO),
                filtroAbaixoMinimo, usados.contains(TipoFiltro.SO_ABAIXO_MINIMO),
                filtroInativos, usados.contains(TipoFiltro.INCLUIR_INATIVOS),
                filtroDetalhar, usados.contains(TipoFiltro.DETALHAR_VENDAS));
        visiveis.forEach((no, visivel) -> {
            no.setVisible(visivel);
            no.setManaged(visivel);
        });
        descricao.setText(relatorio == null ? "" : relatorio.descricao());
        limparResultado();
        Mensagem.limpar(mensagem);
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
        if (usados.contains(TipoFiltro.PRODUTO) && !Textos.vazio(campoProduto.getText())) {
            Opcao produto = relatorioService.produto(sessao, campoProduto.getText());
            f = f.comProduto(produto);
        }
        if (usados.contains(TipoFiltro.TIPO_MOVIMENTO)) {
            int indice = escolhaTipo.getSelectionModel().getSelectedIndex();
            f = f.comTipo(indice <= 0 ? null : tipos.get(indice - 1));
        }
        if (usados.contains(TipoFiltro.TOP_N) || usados.contains(TipoFiltro.CRITERIO)) {
            f = f.comRanking(topN(), escolhaCriterio.getValue());
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
        for (int i = 0; i < r.colunas().size(); i++) {
            Coluna<Object> coluna = r.colunas().get(i);
            TableColumn<Object, Object> tc = new TableColumn<>(coluna.titulo());
            tc.setPrefWidth(coluna.largura() * PIXELS_POR_LARGURA);
            tc.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(coluna.valorDe(c.getValue())));
            tc.setComparator(coluna.comparador());
            tc.setCellFactory(t -> celula(coluna));
            switch (coluna.alinhamento()) {
                case DIREITA -> tc.getStyleClass().add("coluna-numero");
                case CENTRO -> tc.getStyleClass().add("coluna-centro");
                case ESQUERDA -> { }
            }
            tabela.getColumns().add(tc);

            if (!totais.isEmpty()) {
                Label total = new Label(totais.get(i));
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
        exportar("CSV", "*.csv", destino -> ExportadorCsv.salvar(resultado, destino));
    }

    @FXML
    private void exportarPdf() {
        exportar("PDF", "*.pdf", destino -> relatorioPdf.salvar(resultado, destino));
    }

    private void exportar(String formato, String extensao, Consumer<Path> gravar) {
        if (resultado == null) {
            return;
        }
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Exportar " + formato);
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter(formato, extensao));
        escolha.setInitialFileName(nomeArquivo(resultado, extensao.substring(1)));
        File arquivo = escolha.showSaveDialog(raiz.getScene().getWindow());
        if (arquivo == null) {
            return;
        }
        Path destino = arquivo.toPath();
        Task<Void> tarefa = new Task<>() {
            @Override
            protected Void call() {
                gravar.accept(destino);
                return null;
            }
        };
        botaoCsv.setDisable(true);
        botaoPdf.setDisable(true);
        Mensagem.informar(mensagem, "Exportando " + formato + "…");
        tarefa.setOnSucceeded(e -> {
            botaoCsv.setDisable(false);
            botaoPdf.setDisable(false);
            Mensagem.sucesso(mensagem, formato + " salvo em " + destino + ".");
        });
        tarefa.setOnFailed(e -> {
            botaoCsv.setDisable(false);
            botaoPdf.setDisable(false);
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("exportar-relatorio", tarefa);
    }

    /** "R01_vendas_por_periodo_2026-10-17.csv". */
    static String nomeArquivo(ResultadoRelatorio<?> r, String extensao) {
        String nome = Normalizer.normalize(r.relatorio().nome(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        LocalDate dia = r.emitidoEm().toLocalDate();
        return r.relatorio().codigo() + "_" + nome + "_" + dia + extensao;
    }
}
