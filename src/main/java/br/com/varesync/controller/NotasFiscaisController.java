package br.com.varesync.controller;

import br.com.varesync.model.NotaFiscal;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.service.EmissaoNfceService;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.NotasFiscaisService;
import br.com.varesync.service.Sessao;
import br.com.varesync.ui.DialogosNfce;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Formatos;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aba Notas fiscais da janela Cadastro fiscal (F23.9, permissão FISCAL). As ações que falam com a SEFAZ (Corrigir e
 * reenviar, Transmitir agora, Inutilizar números) e o ZIP do mês rodam numa {@code Task}, com os botões desligados.
 * O painel (F23.10) abre a aba já filtrada ({@link #mostrar(Set)}).
 */
public class NotasFiscaisController {

    private static final Logger LOG = LoggerFactory.getLogger(NotasFiscaisController.class);
    static final String TODAS = "Todas";
    static final String A_TRANSMITIR = "A transmitir (contingência e sem resposta)";

    /** Uma opção do filtro de situação: o rótulo e as situações (vazio = todas). */
    record OpcaoSituacao(String rotulo, Set<StatusNfce> situacoes) {
    }

    private final NotasFiscaisService service;
    private final Sessao sessao;
    private final ComprovantePdf comprovantePdf;
    private final ImpressaoComprovante impressao;
    private final Clock clock;

    @FXML private VBox raiz;
    @FXML private DatePicker campoDe;
    @FXML private DatePicker campoAte;
    @FXML private ComboBox<OpcaoSituacao> campoSituacao;
    @FXML private TextField campoNumero;
    @FXML private Button botaoBuscar;
    @FXML private ProgressIndicator progresso;
    @FXML private TableView<NotaFiscal> tabela;
    @FXML private TableColumn<NotaFiscal, String> colunaNumero;
    @FXML private TableColumn<NotaFiscal, String> colunaSerie;
    @FXML private TableColumn<NotaFiscal, String> colunaData;
    @FXML private TableColumn<NotaFiscal, String> colunaValor;
    @FXML private TableColumn<NotaFiscal, String> colunaConsumidor;
    @FXML private TableColumn<NotaFiscal, String> colunaSituacao;
    @FXML private TableColumn<NotaFiscal, String> colunaProtocolo;
    @FXML private TableColumn<NotaFiscal, String> colunaMensagem;
    @FXML private Label resumo;
    @FXML private Button botaoDanfe;
    @FXML private Button botaoXml;
    @FXML private Button botaoReenviar;
    @FXML private Button botaoTransmitir;
    @FXML private Button botaoInutilizar;
    @FXML private Button botaoExportar;
    @FXML private Label mensagem;

    private boolean ocupado;

    public NotasFiscaisController(NotasFiscaisService service, Sessao sessao, ComprovantePdf comprovantePdf,
                                  ImpressaoComprovante impressao, Clock clock) {
        this.service = service;
        this.sessao = sessao;
        this.comprovantePdf = comprovantePdf;
        this.impressao = impressao;
        this.clock = clock;
    }

    @FXML
    private void initialize() {
        progresso.managedProperty().bind(progresso.visibleProperty());
        texto(colunaNumero, n -> String.valueOf(n.numero()));
        texto(colunaSerie, n -> String.valueOf(n.serie()));
        texto(colunaData, n -> Formatos.dataHora(n.emitidaEm()));
        texto(colunaValor, n -> Formatos.moeda(n.valor()));
        texto(colunaConsumidor, n -> n.consumidor() == null ? "Não identificado" : CpfCnpj.formatar(n.consumidor()));
        texto(colunaSituacao, NotasFiscaisController::situacao);
        texto(colunaProtocolo, n -> n.protocolo() == null ? "" : n.protocolo());
        texto(colunaMensagem, n -> n.mensagem() == null ? "" : n.mensagem());
        tabela.setRowFactory(t -> {
            TableRow<NotaFiscal> linha = new TableRow<>() {
                @Override
                protected void updateItem(NotaFiscal n, boolean vazio) {
                    super.updateItem(n, vazio);
                    getStyleClass().removeAll("inativo", "linha-aviso");
                    if (!vazio && n != null) {
                        switch (n.status()) {
                            case REJEITADA, DENEGADA, CONTINGENCIA, SEM_RESPOSTA, PENDENTE ->
                                    getStyleClass().add("linha-aviso");
                            case CANCELADA, SUBSTITUIDA, INUTILIZADA -> getStyleClass().add("inativo");
                            default -> {
                                // autorizada: sem destaque
                            }
                        }
                    }
                }
            };
            linha.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !linha.isEmpty()) {
                    verDanfe();
                }
            });
            return linha;
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((o, a, n) -> atualizarBotoes());

        campoSituacao.getItems().add(new OpcaoSituacao(TODAS, Set.of()));
        campoSituacao.getItems().add(new OpcaoSituacao(A_TRANSMITIR, NotasFiscaisService.A_TRANSMITIR));
        for (StatusNfce s : StatusNfce.values()) {
            campoSituacao.getItems().add(new OpcaoSituacao(s.rotulo(), EnumSet.of(s)));
        }
        campoSituacao.setCellFactory(l -> celula());
        campoSituacao.setButtonCell(celula());
        campoSituacao.getSelectionModel().selectFirst();
        LocalDate hoje = LocalDate.now(clock);
        campoDe.setValue(hoje.withDayOfMonth(1));
        campoAte.setValue(hoje);
        campoSituacao.setOnAction(e -> pesquisar());
        campoDe.valueProperty().addListener((o, a, n) -> pesquisar());
        campoAte.valueProperty().addListener((o, a, n) -> pesquisar());
        pesquisar();
    }

    /**
     * Do painel (F23.10): a aba sem limite de período, filtrada nas situações (as de contingência e sem resposta, as
     * rejeitadas; vazio = todas).
     */
    public void mostrar(Set<StatusNfce> situacoes) {
        campoNumero.clear();
        OpcaoSituacao opcao = campoSituacao.getItems().stream().filter(o -> o.situacoes().equals(situacoes))
                .findFirst().orElse(campoSituacao.getItems().get(0));
        campoSituacao.setOnAction(null);
        campoSituacao.setValue(opcao);
        campoSituacao.setOnAction(e -> pesquisar());
        campoDe.setValue(null);
        campoAte.setValue(null);
        pesquisar();
    }

    @FXML
    void pesquisar() {
        if (campoSituacao.getValue() == null) {
            return;
        }
        try {
            Integer numero = campoNumero.getText().isBlank() ? null : numero(campoNumero.getText());
            NotaFiscal selecionada = tabela.getSelectionModel().getSelectedItem();
            List<NotaFiscal> notas = service.listar(sessao, new NotasFiscaisService.Filtro(campoDe.getValue(),
                    campoAte.getValue(), campoSituacao.getValue().situacoes(), numero));
            tabela.getItems().setAll(notas);
            if (selecionada != null) {
                notas.stream().filter(n -> n.id() == selecionada.id()).findFirst()
                        .ifPresent(n -> tabela.getSelectionModel().select(n));
            }
            resumo.setText(resumo(notas));
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
        atualizarBotoes();
    }

    static String resumo(List<NotaFiscal> notas) {
        if (notas.isEmpty()) {
            return "";
        }
        return notas.size() + (notas.size() == 1 ? " NFC-e" : " NFC-e") + " no filtro. Emitidas só em homologação, "
                + "sem valor fiscal.";
    }

    private static String situacao(NotaFiscal n) {
        return n.status().rotulo() + (n.contingencia() && n.status() != StatusNfce.CONTINGENCIA
                ? " (contingência)" : "");
    }

    private void atualizarBotoes() {
        NotaFiscal n = tabela.getSelectionModel().getSelectedItem();
        botaoDanfe.setDisable(ocupado || n == null || !n.temXml() || !(n.status() == StatusNfce.AUTORIZADA
                || n.status() == StatusNfce.CONTINGENCIA || n.status() == StatusNfce.CANCELADA));
        botaoXml.setDisable(ocupado || n == null || !n.temXml());
        botaoReenviar.setDisable(ocupado || n == null || n.status() != StatusNfce.REJEITADA);
        botaoTransmitir.setDisable(ocupado);
        botaoInutilizar.setDisable(ocupado);
        botaoExportar.setDisable(ocupado);
        botaoBuscar.setDisable(ocupado);
    }

    // ----- DANFE e XML -----

    @FXML
    private void verDanfe() {
        NotaFiscal n = tabela.getSelectionModel().getSelectedItem();
        if (n == null || botaoDanfe.isDisabled()) {
            return;
        }
        try {
            ComprovanteController.abrir(raiz.getScene().getWindow(), service.danfe(sessao, n.id()), comprovantePdf,
                    impressao);
        } catch (RuntimeException e) {
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void salvarXml() {
        NotaFiscal n = tabela.getSelectionModel().getSelectedItem();
        if (n == null) {
            return;
        }
        try {
            NotasFiscaisService.XmlNota xml = service.xml(sessao, n.id());
            FileChooser escolha = new FileChooser();
            escolha.setTitle("Salvar o XML da NFC-e nº " + n.numero());
            escolha.setInitialFileName(xml.nomeArquivo());
            escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML", "*.xml"));
            File arquivo = escolha.showSaveDialog(raiz.getScene().getWindow());
            if (arquivo != null) {
                salvarXml(n.id(), arquivo.toPath());
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagem, e.getMessage());
        }
    }

    /** Grava o XML da NFC-e no arquivo (o "Salvar XML" sem o diálogo do sistema, também no roteiro de tela). */
    void salvarXml(long nfceId, Path destino) {
        NotasFiscaisService.XmlNota xml = service.xml(sessao, nfceId);
        try {
            Files.writeString(destino, xml.conteudo(), StandardCharsets.UTF_8);
            Mensagem.sucesso(mensagem, "XML salvo em " + destino.getFileName() + ".");
        } catch (IOException e) {
            LOG.error("Falha ao gravar o XML da NFC-e", e);
            Mensagem.erro(mensagem, "Não foi possível gravar o arquivo. Escolha outra pasta.");
        }
    }

    // ----- Ações com a SEFAZ (em Task) -----

    @FXML
    private void transmitirAgora() {
        executar("Transmitindo as NFC-e pendentes…", () -> service.transmitirAgora(sessao), resultado -> resultado);
    }

    @FXML
    private void reenviar() {
        NotaFiscal n = tabela.getSelectionModel().getSelectedItem();
        if (n == null || n.status() != StatusNfce.REJEITADA) {
            return;
        }
        executar("Corrigindo e reenviando a NFC-e nº " + n.numero() + "…", () -> service.reenviar(sessao, n.id()),
                NotasFiscaisController::resultadoReenvio);
    }

    static String resultadoReenvio(EmissaoNfceService.Resultado r) {
        var nota = r.nfce();
        return switch (nota.status()) {
            case AUTORIZADA -> "NFC-e nº " + nota.numero() + " corrigida e autorizada (protocolo " + nota.protocolo()
                    + ").";
            case CONTINGENCIA -> "NFC-e nº " + nota.numero() + " corrigida; continua em contingência"
                    + (r.mensagem() == null ? "." : ": " + r.mensagem());
            default -> "NFC-e nº " + nota.numero() + " " + nota.status().rotulo().toLowerCase()
                    + (r.mensagem() == null ? "." : ": " + r.mensagem());
        };
    }

    @FXML
    private void inutilizar() {
        NotaFiscal n = tabela.getSelectionModel().getSelectedItem();
        Optional<DialogosNfce.Faixa> faixa = DialogosNfce.faixaParaInutilizar(n == null ? 1 : n.serie());
        faixa.ifPresent(this::inutilizar);
    }

    /** Inutiliza a faixa (o "Inutilizar números" sem o diálogo, também no roteiro de tela). */
    Task<NotasFiscaisService.Inutilizacao> inutilizar(DialogosNfce.Faixa f) {
        return executar("Inutilizando os números na SEFAZ…", () -> service.inutilizar(sessao, f.serie(),
                f.inicial(), f.fim(), f.justificativa()), i -> (i.inicial() == i.fim() ? "Número " + i.inicial()
                : "Números " + i.inicial() + " a " + i.fim()) + " da série " + i.serie() + " inutilizados (protocolo "
                + i.protocolo() + ").");
    }

    @FXML
    private void exportarMes() {
        Optional<YearMonth> mes = DialogosNfce.mesParaExportar(YearMonth.now(clock));
        if (mes.isEmpty()) {
            return;
        }
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Salvar o ZIP dos XML de " + DialogosNfce.mes(mes.get()));
        escolha.setInitialFileName("NFC-e_" + mes.get().getYear() + String.format("%02d", mes.get().getMonthValue())
                + ".zip");
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        File arquivo = escolha.showSaveDialog(raiz.getScene().getWindow());
        if (arquivo != null) {
            exportarMes(mes.get(), arquivo.toPath());
        }
    }

    /** Grava o ZIP do mês (o "Exportar XML do mês" sem os diálogos, também no roteiro de tela). */
    Task<NotasFiscaisService.ExportacaoXml> exportarMes(YearMonth mes, Path destino) {
        return executar("Gravando o ZIP dos XML de " + DialogosNfce.mes(mes) + "…",
                () -> service.exportarMes(sessao, mes, destino), x -> "ZIP gravado em " + destino.getFileName() + ": "
                        + x.notas() + " NFC-e, " + x.eventos() + (x.eventos() == 1 ? " evento" : " eventos") + " e "
                        + x.inutilizacoes() + (x.inutilizacoes() == 1 ? " inutilização" : " inutilizações") + ".");
    }

    private <T> Task<T> executar(String aviso, Supplier<T> acao, Function<T, String> sucesso) {
        Mensagem.informar(mensagem, aviso);
        ocupar(true);
        Task<T> tarefa = new Task<>() {
            @Override
            protected T call() {
                return acao.get();
            }
        };
        tarefa.setOnSucceeded(e -> {
            ocupar(false);
            pesquisar();
            Mensagem.sucesso(mensagem, sucesso.apply(tarefa.getValue()));
        });
        tarefa.setOnFailed(e -> {
            ocupar(false);
            pesquisar();
            Mensagem.erro(mensagem, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        return Tarefas.executar("notas-fiscais", tarefa);
    }

    private void ocupar(boolean sim) {
        ocupado = sim;
        progresso.setVisible(sim);
        atualizarBotoes();
    }

    private static int numero(String texto) {
        String limpo = texto.strip();
        if (!limpo.matches("\\d{1,9}")) {
            throw new NegocioException("Informe o número da NFC-e só com números.");
        }
        return Integer.parseInt(limpo);
    }

    private static ListCell<OpcaoSituacao> celula() {
        return new ListCell<>() {
            @Override
            protected void updateItem(OpcaoSituacao o, boolean vazio) {
                super.updateItem(o, vazio);
                setText(vazio || o == null ? null : o.rotulo());
            }
        };
    }

    private static void texto(TableColumn<NotaFiscal, String> coluna, Function<NotaFiscal, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }
}
