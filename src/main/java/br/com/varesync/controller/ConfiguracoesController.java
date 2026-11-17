package br.com.varesync.controller;

import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.MeioPagamentoNfce;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.InconsistenciaAbatimento;
import br.com.varesync.model.InconsistenciaConta;
import br.com.varesync.model.InconsistenciaCredito;
import br.com.varesync.model.InconsistenciaEstoque;
import br.com.varesync.model.ParametrosVenda;
import br.com.varesync.model.PoliticaSenha;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.RegistroAuditoria;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.report.ImpressaoTermica;
import br.com.varesync.model.InfoCertificado;
import br.com.varesync.service.BackupService;
import br.com.varesync.service.CertificadoService;
import br.com.varesync.service.NfceService;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.ContaFinanceiraService;
import br.com.varesync.service.ConfiguracaoService;
import br.com.varesync.service.ConsultaAuditoriaService;
import br.com.varesync.service.CreditoService;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.GeradorDemo;
import br.com.varesync.service.NegocioException;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.UsuarioService;
import br.com.varesync.ui.ConversorData;
import br.com.varesync.ui.Dialogos;
import br.com.varesync.ui.Mensagem;
import br.com.varesync.ui.Tarefas;
import br.com.varesync.ui.TratadorExcecoes;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Gtin;
import br.com.varesync.util.Textos;
import java.io.File;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

/**
 * Configurações (6.13, só supervisor): dados da loja, parâmetros de venda, margem mínima e dias sem giro, balança,
 * bloqueio por inatividade (F10.2) e backup (salvos juntos por "Salvar alterações"), formas de pagamento, o diagnóstico do estoque (6.4) e o gerador de dados
 * de demonstração (6.14). Diagnóstico e gerador rodam em {@link Task}.
 */
public class ConfiguracoesController {

    /** F19.1: as 27 siglas, com o vazio (não informada) primeiro. */
    private static final List<String> UFS = List.of("", "AC", "AL", "AM", "AP", "BA", "CE", "DF", "ES", "GO", "MA",
            "MG", "MS", "MT", "PA", "PB", "PE", "PI", "PR", "RJ", "RN", "RO", "RR", "RS", "SC", "SE", "SP", "TO");

    private final ConfiguracaoService configuracaoService;
    private final BackupService backupService;
    private final ConsultaAuditoriaService auditoriaService;
    private final UsuarioService usuarioService;
    /** A aba Auditoria carrega a lista de usuários e a primeira consulta só quando é aberta. */
    private boolean auditoriaPreparada;
    private final EstoqueService estoqueService;
    private final CreditoService creditoService;
    private final ClienteService clienteService;
    private final ContaFinanceiraService contaService;
    private final GeradorDemo geradorDemo;
    private final ImpressaoTermica impressaoTermica;
    private final Clock clock;
    private final Sessao sessao;
    /** O que está gravado (base do "Desfazer" e do que não é editado aqui, como o backup). */
    private Configuracoes gravadas;

    @FXML private TabPane abas;
    @FXML private Tab abaLoja;
    @FXML private Tab abaVenda;
    @FXML private Tab abaRelatorios;
    @FXML private Tab abaBalanca;
    @FXML private Tab abaSeguranca;
    @FXML private Tab abaFormas;
    @FXML private Tab abaBackup;
    @FXML private Tab abaAuditoria;
    @FXML private TextField campoPastaBackup;
    @FXML private TextField campoPastaCarga;
    @FXML private TextField campoPgDump;
    @FXML private TextField campoManterBackups;
    @FXML private Label situacaoPgDump;
    @FXML private Button botaoFazerBackup;
    @FXML private ProgressIndicator progressoBackup;
    @FXML private Label resultadoBackup;
    @FXML private Label ultimoBackup;
    @FXML private TableView<BackupService.ArquivoBackup> tabelaBackups;
    @FXML private TableColumn<BackupService.ArquivoBackup, String> colunaBackupArquivo;
    @FXML private TableColumn<BackupService.ArquivoBackup, String> colunaBackupTamanho;
    @FXML private TableColumn<BackupService.ArquivoBackup, String> colunaBackupData;
    @FXML private DatePicker auditoriaInicio;
    @FXML private DatePicker auditoriaFim;
    @FXML private ComboBox<Escolha<Long>> auditoriaUsuario;
    @FXML private ComboBox<Escolha<AcaoAuditoria>> auditoriaAcao;
    @FXML private Button botaoConsultarAuditoria;
    @FXML private ProgressIndicator progressoAuditoria;
    @FXML private TableView<RegistroAuditoria> tabelaAuditoria;
    @FXML private TableColumn<RegistroAuditoria, String> colunaAuditoriaData;
    @FXML private TableColumn<RegistroAuditoria, String> colunaAuditoriaUsuario;
    @FXML private TableColumn<RegistroAuditoria, String> colunaAuditoriaAcao;
    @FXML private TableColumn<RegistroAuditoria, String> colunaAuditoriaDescricao;
    @FXML private TableColumn<RegistroAuditoria, String> colunaAuditoriaTerminal;
    @FXML private Label detalheAuditoria;
    @FXML private Label resumoAuditoria;
    @FXML private TextField campoNomeLoja;
    @FXML private TextField campoDocumento;
    @FXML private TextField campoEndereco;
    @FXML private TextField campoTelefone;
    @FXML private ComboBox<RegimeTributario> campoRegime;
    @FXML private ComboBox<String> campoUf;
    @FXML private TextField campoIe;
    @FXML private TextField campoRodape;
    @FXML private CheckBox campoEstoqueNegativo;
    @FXML private TextField campoDescontoMaximo;
    @FXML private CheckBox campoCancelarItem;
    @FXML private TextField campoPrazoDevolucao;
    @FXML private TextField campoMargemMinima;
    @FXML private TextField campoDiasSemGiro;
    @FXML private CheckBox campoBalancaAtiva;
    @FXML private TextField campoPrefixo;
    @FXML private ComboBox<Integer> campoDigitos;
    @FXML private ComboBox<TipoValorBalanca> campoTipoValor;
    @FXML private Label exemploEtiqueta;
    @FXML private TextField campoInatividade;
    @FXML private TextField campoSenhaTamanho;
    @FXML private CheckBox campoSenhaLetraENumero;
    @FXML private Tab abaFidelidade;
    @FXML private CheckBox campoFidelidadeAtiva;
    @FXML private TextField campoCashback;
    @FXML private TextField campoCompraMinima;
    @FXML private Tab abaCrediario;
    @FXML private TextField campoMaxParcelas;
    @FXML private TextField campoDiasPrimeira;
    @FXML private TextField campoJuros;
    @FXML private TextField campoMulta;
    @FXML private TextField campoCarencia;
    @FXML private TextField campoBloqueioAtraso;
    @FXML private Tab abaFinanceiro;
    @FXML private CheckBox campoIntegrarCaixa;
    @FXML private ListView<Tab> secoes;
    @FXML private Tab abaPrimeirosPassos;
    @FXML private CheckBox campoFormasRevisadas;
    @FXML private CheckBox campoSoEuUso;
    @FXML private CheckBox campoSemBalanca;
    @FXML private Tab abaComprasEstoque;
    @FXML private TextField campoAlertaValidade;
    @FXML private Label resultadoLotes;
    @FXML private ListView<String> listaLotes;
    @FXML private TextField campoDiasMedia;
    @FXML private TextField campoCobertura;
    @FXML private javafx.scene.layout.VBox painelRecebimento;
    @FXML private TextField campoTaxaForma;
    @FXML private TextField campoDiasForma;
    @FXML private ComboBox<ContaFinanceira> campoContaForma;
    @FXML private Label resultadoConta;
    @FXML private TableView<InconsistenciaConta> tabelaConta;
    @FXML private TableColumn<InconsistenciaConta, String> colunaContaNome;
    @FXML private TableColumn<InconsistenciaConta, String> colunaContaRegistrado;
    @FXML private TableColumn<InconsistenciaConta, String> colunaContaMovimentos;
    @FXML private TableColumn<InconsistenciaConta, String> colunaContaDiferenca;
    @FXML private Label resultadoAbatimento;
    @FXML private TableView<InconsistenciaAbatimento> tabelaAbatimento;
    @FXML private TableColumn<InconsistenciaAbatimento, String> colunaAbatimentoOrigem;
    @FXML private TableColumn<InconsistenciaAbatimento, String> colunaAbatimentoValor;
    @FXML private TableColumn<InconsistenciaAbatimento, String> colunaAbatimentoSoma;
    @FXML private TableColumn<InconsistenciaAbatimento, String> colunaAbatimentoDiferenca;
    @FXML private Label resultadoCredito;
    @FXML private TableView<InconsistenciaCredito> tabelaCredito;
    @FXML private TableColumn<InconsistenciaCredito, String> colunaCreditoCliente;
    @FXML private TableColumn<InconsistenciaCredito, String> colunaCreditoRegistrado;
    @FXML private TableColumn<InconsistenciaCredito, String> colunaCreditoMovimentos;
    @FXML private TableColumn<InconsistenciaCredito, String> colunaCreditoDiferenca;
    @FXML private HBox barraSalvar;
    @FXML private Label mensagemParametros;
    @FXML private Button botaoSalvar;
    @FXML private TableView<FormaPagamentoCadastro> tabelaFormas;
    @FXML private TableColumn<FormaPagamentoCadastro, String> colunaFormaDescricao;
    @FXML private TableColumn<FormaPagamentoCadastro, String> colunaFormaTipo;
    @FXML private TableColumn<FormaPagamentoCadastro, String> colunaFormaSituacao;
    @FXML private TableColumn<FormaPagamentoCadastro, String> colunaFormaTpag;
    @FXML private javafx.scene.layout.HBox painelTpag;
    @FXML private ComboBox<MeioPagamentoNfce> campoTpagForma;
    @FXML private TextField campoAliquotaCbs;
    @FXML private TextField campoAliquotaIbsUf;
    @FXML private TextField campoAliquotaIbsMun;
    @FXML private CheckBox campoExcluirIcms;
    @FXML private Button botaoSituacaoForma;
    @FXML private TextField campoNovaForma;
    @FXML private ComboBox<TipoFormaPagamento> campoTipoForma;
    @FXML private Label mensagemFormas;
    @FXML private Button botaoVerificar;
    @FXML private ProgressIndicator progresso;
    @FXML private Label resultado;
    @FXML private TableView<InconsistenciaEstoque> tabelaInconsistencias;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaProduto;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaRegistrado;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaMovimentos;
    @FXML private TableColumn<InconsistenciaEstoque, String> colunaDiferenca;
    @FXML private Button botaoGerarDemo;
    @FXML private ProgressBar progressoDemo;
    @FXML private Label situacaoDemo;
    @FXML private Tab abaEtiquetas;
    @FXML private TextField campoEtiquetaColunas;
    @FXML private TextField campoEtiquetaLinhas;
    @FXML private ComboBox<String> campoImpressoraTermica;
    @FXML private TextArea campoModeloTermica;
    @FXML private Button botaoImprimirTeste;
    @FXML private Label mensagemEtiquetas;
    @FXML private TextArea resultadoDemo;
    // F23.2: endereço do emitente (Loja) e a seção NFC-e, com o certificado deste terminal
    @FXML private TextField campoRazaoSocial;
    @FXML private TextField campoLogradouro;
    @FXML private TextField campoNumero;
    @FXML private TextField campoComplemento;
    @FXML private TextField campoBairro;
    @FXML private TextField campoCep;
    @FXML private TextField campoMunicipio;
    @FXML private TextField campoCodigoMunicipio;
    @FXML private Label situacaoEmitente;
    @FXML private Tab abaNfce;
    @FXML private TextField campoSerieNfce;
    @FXML private TextField campoPrazoCancelamento;
    @FXML private TextField campoTimeoutNfce;
    @FXML private TextField campoRetransmitir;
    @FXML private TextField campoValorIdentificacao;
    @FXML private TextField campoValorMaximo;
    @FXML private CheckBox campoViaEstabelecimento;
    @FXML private TextField campoAlertaCertificado;
    @FXML private TextField campoUrlQrCode;
    @FXML private TextField campoUrlConsulta;
    @FXML private TextField campoRespCnpj;
    @FXML private TextField campoRespContato;
    @FXML private TextField campoRespEmail;
    @FXML private TextField campoRespFone;
    @FXML private Label situacaoCertificado;
    @FXML private TextField campoCertificado;
    @FXML private PasswordField campoSenhaCertificado;
    @FXML private Label rotuloSenhaCertificado;
    @FXML private Button botaoRemoverCertificado;
    @FXML private Label mensagemCertificado;
    @FXML private Label resultadoNfce;
    @FXML private ListView<String> listaNfce;

    private final CertificadoService certificadoService;
    private final NfceService nfceService;
    private final br.com.varesync.service.NfceTerminalService nfceTerminal;
    @FXML private CheckBox campoNfceAtiva;
    @FXML private Button botaoTestarNfce;
    @FXML private Label mensagemNfceAtiva;

    public ConfiguracoesController(ConfiguracaoService configuracaoService, BackupService backupService,
                                   ConsultaAuditoriaService auditoriaService, UsuarioService usuarioService,
                                   EstoqueService estoqueService, CreditoService creditoService,
                                   ClienteService clienteService, ContaFinanceiraService contaService,
                                   GeradorDemo geradorDemo, ImpressaoTermica impressaoTermica,
                                   CertificadoService certificadoService, NfceService nfceService,
                                   br.com.varesync.service.NfceTerminalService nfceTerminal, Clock clock,
                                   Sessao sessao) {
        this.nfceTerminal = nfceTerminal;
        this.certificadoService = certificadoService;
        this.nfceService = nfceService;
        this.impressaoTermica = impressaoTermica;
        this.clock = clock;
        this.contaService = contaService;
        this.clienteService = clienteService;
        this.configuracaoService = configuracaoService;
        this.backupService = backupService;
        this.auditoriaService = auditoriaService;
        this.usuarioService = usuarioService;
        this.estoqueService = estoqueService;
        this.creditoService = creditoService;
        this.geradorDemo = geradorDemo;
        this.sessao = sessao;
    }

    @FXML
    private void initialize() {
        coluna(colunaProduto, i -> i.nome() + " (código " + i.produtoId() + ")");
        coluna(colunaRegistrado, i -> quantidade(i.saldoRegistrado()));
        coluna(colunaMovimentos, i -> quantidade(i.saldoPelosMovimentos()));
        coluna(colunaDiferenca, i -> quantidade(i.diferenca()));

        campoRegime.getItems().setAll(RegimeTributario.values());
        campoUf.getItems().setAll(UFS);
        campoDigitos.getItems().setAll(4, 5, 6);
        campoTipoValor.getItems().setAll(TipoValorBalanca.values());
        campoBalancaAtiva.selectedProperty().addListener((o, a, n) -> atualizarBalanca());
        campoPrefixo.textProperty().addListener((o, a, n) -> atualizarBalanca());
        campoDigitos.valueProperty().addListener((o, a, n) -> atualizarBalanca());
        campoTipoValor.valueProperty().addListener((o, a, n) -> atualizarBalanca());
        campoDocumento.focusedProperty().addListener((o, a, focado) -> {
            if (!focado && !Textos.vazio(campoDocumento.getText())
                    && CpfCnpj.validar(CpfCnpj.normalizar(campoDocumento.getText())).isEmpty()) {
                campoDocumento.setText(CpfCnpj.formatar(CpfCnpj.normalizar(campoDocumento.getText())));
            }
        });

        colunaCreditoCliente.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome() + " (código "
                + c.getValue().clienteId() + ")"));
        colunaCreditoRegistrado.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().saldoRegistrado())));
        colunaCreditoMovimentos.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().saldoPelosMovimentos())));
        colunaCreditoDiferenca.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().diferenca())));

        colunaAbatimentoOrigem.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().descricao()));
        colunaAbatimentoValor.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().valorAbatido())));
        colunaAbatimentoSoma.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().somaLinhas())));
        colunaAbatimentoDiferenca.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().diferenca())));

        colunaContaNome.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome()));
        colunaContaRegistrado.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().saldoRegistrado())));
        colunaContaMovimentos.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().saldoPelosMovimentos())));
        colunaContaDiferenca.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.moeda(c.getValue().diferenca())));

        Set<Tab> parametros = Set.of(abaLoja, abaVenda, abaRelatorios, abaBalanca, abaSeguranca, abaFidelidade,
                abaCrediario, abaFinanceiro, abaComprasEstoque, abaEtiquetas, abaNfce, abaBackup, abaPrimeirosPassos);
        prepararSecoes();
        abas.getSelectionModel().selectedItemProperty().addListener((o, a, aba) -> {
            boolean visivel = parametros.contains(aba);
            barraSalvar.setVisible(visivel);
            barraSalvar.setManaged(visivel);
            if (aba == abaBackup) {
                atualizarBackup();
            } else if (aba == abaEtiquetas) {
                carregarImpressoras();
            } else if (aba == abaAuditoria && !auditoriaPreparada) {
                prepararAuditoria();
            }
        });

        colunaFormaDescricao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().descricao()));
        colunaFormaTipo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().tipo().rotulo()));
        colunaFormaSituacao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().ativa() ? "Ativa"
                : "Desativada"));
        colunaFormaTpag.setCellValueFactory(c -> new ReadOnlyStringWrapper(MeioPagamentoNfce.rotulo(c.getValue().tpag())));
        campoTpagForma.getItems().setAll(MeioPagamentoNfce.TODOS);
        painelTpag.setDisable(true);
        tabelaFormas.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(FormaPagamentoCadastro f, boolean vazio) {
                super.updateItem(f, vazio);
                getStyleClass().remove("inativo");
                if (!vazio && f != null && !f.ativa()) {
                    getStyleClass().add("inativo");
                }
            }
        });
        tabelaFormas.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> {
            botaoSituacaoForma.setDisable(f == null);
            botaoSituacaoForma.setText(f == null || f.ativa() ? "Desativar" : "Ativar");
            mostrarRecebimento(f);
        });
        botaoSituacaoForma.setDisable(true);
        painelRecebimento.setDisable(true);
        // O crédito do cliente tem uma forma só, criada pela V7 (F12.4).
        campoTipoForma.getItems().setAll(Arrays.stream(TipoFormaPagamento.values())
                .filter(t -> t != TipoFormaPagamento.CREDITO_CLIENTE && t != TipoFormaPagamento.CREDIARIO).toList());
        campoTipoForma.setConverter(new StringConverter<>() {
            @Override
            public String toString(TipoFormaPagamento tipo) {
                return tipo == null ? "" : tipo.rotulo();
            }

            @Override
            public TipoFormaPagamento fromString(String texto) {
                return null;
            }
        });
        campoTipoForma.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TipoFormaPagamento tipo, boolean vazio) {
                super.updateItem(tipo, vazio);
                setText(vazio || tipo == null ? "Tipo" : tipo.rotulo());
            }
        });
        campoTipoForma.setValue(TipoFormaPagamento.OUTROS);

        prepararTabelaBackups();
        prepararTabelaAuditoria();
        mostrar(configuracaoService.carregar(sessao));
        mostrarCertificado();
        carregarFormas(null);
        verificar();
        javafx.application.Platform.runLater(secoes::requestFocus); // F21.5: foco inicial na lista de seções
    }

    // ----- Parâmetros (loja, venda, relatórios, balança) -----

    private void mostrar(Configuracoes c) {
        gravadas = c;
        campoNomeLoja.setText(c.loja().nome());
        campoDocumento.setText(c.loja().documento().isEmpty() ? "" : CpfCnpj.formatar(c.loja().documento()));
        campoEndereco.setText(c.loja().endereco());
        campoTelefone.setText(c.loja().telefone().isEmpty() ? "" : Formatos.telefone(c.loja().telefone()));
        campoRegime.setValue(c.loja().regime());
        campoUf.setValue(c.loja().uf());
        campoIe.setText(c.loja().ie());
        campoRodape.setText(c.rodapeComprovante());
        campoEstoqueNegativo.setSelected(c.venda().permitirEstoqueNegativo());
        campoDescontoMaximo.setText(Formatos.numero(c.venda().descontoMaximoPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoCancelarItem.setSelected(c.venda().cancelarItemExigeSupervisor());
        campoPrazoDevolucao.setText(String.valueOf(c.devolucaoPrazoDias()));
        campoMargemMinima.setText(Formatos.numero(c.margemMinimaPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoDiasSemGiro.setText(String.valueOf(c.diasSemGiro()));
        campoBalancaAtiva.setSelected(c.balanca().ativo());
        campoPrefixo.setText(String.valueOf(c.balanca().prefixo()));
        campoDigitos.setValue(c.balanca().digitosCodigo());
        campoTipoValor.setValue(c.balanca().tipoValor());
        campoPastaCarga.setText(c.balanca().pastaCarga());
        campoPastaBackup.setText(c.backup().pasta());
        campoPgDump.setText(c.backup().pgDump());
        campoManterBackups.setText(String.valueOf(c.backup().manterUltimos()));
        campoInatividade.setText(String.valueOf(c.inatividadeMinutos()));
        campoSenhaTamanho.setText(String.valueOf(c.politicaSenha().tamanhoMinimo()));
        campoSenhaLetraENumero.setSelected(c.politicaSenha().letraENumero());
        campoFidelidadeAtiva.setSelected(c.fidelidade().ativo());
        campoCashback.setText(Formatos.numero(c.fidelidade().cashbackPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoCompraMinima.setText(Formatos.numero(c.fidelidade().compraMinima(), Dinheiro.ESCALA_DINHEIRO));
        campoMaxParcelas.setText(String.valueOf(c.crediario().maxParcelas()));
        campoDiasPrimeira.setText(String.valueOf(c.crediario().diasPrimeira()));
        campoJuros.setText(Formatos.numero(c.crediario().jurosMesPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoMulta.setText(Formatos.numero(c.crediario().multaPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoCarencia.setText(String.valueOf(c.crediario().carenciaDias()));
        campoBloqueioAtraso.setText(String.valueOf(c.crediario().bloqueioAtrasoDias()));
        campoIntegrarCaixa.setSelected(c.integrarCaixa());
        campoDiasMedia.setText(String.valueOf(c.compras().diasMedia()));
        campoCobertura.setText(String.valueOf(c.compras().coberturaDias()));
        campoAlertaValidade.setText(String.valueOf(c.validadeAlertaDias()));
        campoEtiquetaColunas.setText(String.valueOf(c.etiquetas().colunas()));
        campoEtiquetaLinhas.setText(String.valueOf(c.etiquetas().linhas()));
        campoImpressoraTermica.getEditor().setText(c.etiquetas().impressoraTermica());
        campoModeloTermica.setText(c.etiquetas().modeloTermica());
        campoFormasRevisadas.setSelected(c.assistente().formasRevisadas());
        campoSoEuUso.setSelected(c.assistente().usuariosDispensado());
        campoSemBalanca.setSelected(c.assistente().balancaDispensada());
        campoAliquotaCbs.setText(Formatos.numero(c.tributos().aliquotaCbs(), Dinheiro.ESCALA_PERCENTUAL));
        campoAliquotaIbsUf.setText(Formatos.numero(c.tributos().aliquotaIbsUf(), Dinheiro.ESCALA_PERCENTUAL));
        campoAliquotaIbsMun.setText(Formatos.numero(c.tributos().aliquotaIbsMun(), Dinheiro.ESCALA_PERCENTUAL));
        campoExcluirIcms.setSelected(c.tributos().excluirIcmsBasePisCofins());
        Configuracoes.Emitente e = c.emitente();
        campoRazaoSocial.setText(e.razaoSocial());
        campoLogradouro.setText(e.logradouro());
        campoNumero.setText(e.numero());
        campoComplemento.setText(e.complemento());
        campoBairro.setText(e.bairro());
        campoCep.setText(e.cep().length() == 8 ? e.cep().substring(0, 5) + "-" + e.cep().substring(5) : e.cep());
        campoMunicipio.setText(e.municipio());
        campoCodigoMunicipio.setText(e.codigoMunicipio());
        Configuracoes.Nfce n = c.nfce();
        campoSerieNfce.setText(String.valueOf(n.serie()));
        campoPrazoCancelamento.setText(String.valueOf(n.prazoCancelamentoMinutos()));
        campoTimeoutNfce.setText(String.valueOf(n.timeoutSegundos()));
        campoRetransmitir.setText(String.valueOf(n.retransmitirMinutos()));
        campoValorIdentificacao.setText(Formatos.numero(n.valorIdentificacao(), Dinheiro.ESCALA_DINHEIRO));
        campoValorMaximo.setText(Formatos.numero(n.valorMaximo(), Dinheiro.ESCALA_DINHEIRO));
        campoViaEstabelecimento.setSelected(n.viaEstabelecimento());
        campoAlertaCertificado.setText(String.valueOf(n.alertaCertificadoDias()));
        campoUrlQrCode.setText(n.urlQrCode());
        campoUrlConsulta.setText(n.urlConsulta());
        campoRespCnpj.setText(n.respTecnico().cnpj().isEmpty() ? "" : CpfCnpj.formatar(n.respTecnico().cnpj()));
        campoRespContato.setText(n.respTecnico().contato());
        campoRespEmail.setText(n.respTecnico().email());
        campoRespFone.setText(n.respTecnico().fone().isEmpty() ? "" : Formatos.telefone(n.respTecnico().fone()));
        mostrarSituacaoEmitente(c);
        campoNfceAtiva.setSelected(c.nfce().ativa());
        atualizarBalanca();
    }

    /** F23.2: o que ainda falta na loja para a NFC-e (o bloco "Endereço para a NFC-e" e os dados de cima). */
    private void mostrarSituacaoEmitente(Configuracoes c) {
        List<String> falta = br.com.varesync.service.nfce.EmitenteNfce.pendencias(c);
        situacaoEmitente.getStyleClass().removeAll("mensagem-erro", "mensagem-sucesso");
        if (falta.isEmpty()) {
            situacaoEmitente.setText("Os dados do emitente estão completos para a NFC-e.");
            situacaoEmitente.getStyleClass().add("mensagem-sucesso");
        } else {
            situacaoEmitente.setText("Para a NFC-e, ainda falta: " + String.join(", ", falta) + ".");
            situacaoEmitente.getStyleClass().add("mensagem-erro");
        }
    }

    /** Lê os campos das seis abas de parâmetros; texto que não é número vira mensagem com o nome do campo. */
    Configuracoes lerCampos() {
        String prefixo = campoPrefixo.getText() == null ? "" : campoPrefixo.getText().strip();
        if (!prefixo.matches("\\d")) {
            throw new NegocioException("O prefixo da etiqueta de balança é um dígito, de 0 a 9 (normalmente 2).");
        }
        return new Configuracoes(new Configuracoes.Loja(campoNomeLoja.getText(), campoDocumento.getText(),
                campoEndereco.getText(), campoTelefone.getText(), campoRegime.getValue(),
                campoUf.getValue(), campoIe.getText()),
                new ParametrosVenda(campoEstoqueNegativo.isSelected(),
                        numero(campoDescontoMaximo, "Desconto máximo do operador"), campoCancelarItem.isSelected()),
                numero(campoMargemMinima, "Margem mínima"), inteiro(campoDiasSemGiro, "Dias sem giro"),
                new Configuracoes.Balanca(campoBalancaAtiva.isSelected(), Integer.parseInt(prefixo),
                        campoDigitos.getValue() == null ? 0 : campoDigitos.getValue(), campoTipoValor.getValue(),
                        campoPastaCarga.getText()),
                new Configuracoes.Backup(campoPastaBackup.getText(), campoPgDump.getText(),
                        inteiro(campoManterBackups, "Manter os últimos backups")), campoRodape.getText(),
                new Configuracoes.Seguranca(inteiro(campoInatividade, "Bloquear a tela depois de"),
                        new PoliticaSenha(inteiro(campoSenhaTamanho, "Tamanho mínimo da senha"),
                                campoSenhaLetraENumero.isSelected())),
                new Configuracoes.Fidelidade(campoFidelidadeAtiva.isSelected(),
                        numero(campoCashback, "Cashback"), numero(campoCompraMinima, "Compra mínima")),
                inteiro(campoPrazoDevolucao, "Prazo de devolução"),
                new Configuracoes.Crediario(inteiro(campoMaxParcelas, "Máximo de parcelas"),
                        inteiro(campoDiasPrimeira, "Dias até a 1ª parcela"), numero(campoJuros, "Juros ao mês"),
                        numero(campoMulta, "Multa por atraso"), inteiro(campoCarencia, "Carência"),
                        inteiro(campoBloqueioAtraso, "Atraso que pede liberação")),
                campoIntegrarCaixa.isSelected(),
                new Configuracoes.Compras(inteiro(campoDiasMedia, "Dias de venda na média diária"),
                        inteiro(campoCobertura, "Cobertura desejada")),
                inteiro(campoAlertaValidade, "Alerta de validade"),
                new Configuracoes.Etiquetas(inteiro(campoEtiquetaColunas, "Etiquetas por linha"),
                        inteiro(campoEtiquetaLinhas, "Linhas por folha"), campoModeloTermica.getText(),
                        campoImpressoraTermica.getEditor().getText()),
                new Configuracoes.Assistente(campoFormasRevisadas.isSelected(), campoSoEuUso.isSelected(),
                        campoSemBalanca.isSelected()),
                new Configuracoes.Tributos(numero(campoAliquotaCbs, "Alíquota da CBS"),
                        numero(campoAliquotaIbsUf, "Alíquota do IBS da UF"),
                        numero(campoAliquotaIbsMun, "Alíquota do IBS do município"), campoExcluirIcms.isSelected()),
                new Configuracoes.Emitente(campoRazaoSocial.getText(), campoLogradouro.getText(),
                        campoNumero.getText(), campoComplemento.getText(), campoBairro.getText(), campoCep.getText(),
                        campoMunicipio.getText(), campoCodigoMunicipio.getText()),
                // "NFC-e ligada" fica como está gravada: liga e desliga pelo campo próprio (alternarNfce, F23.6)
                new Configuracoes.Nfce(gravadas == null ? false : gravadas.nfce().ativa(),
                        inteiro(campoSerieNfce, "Série da NFC-e"),
                        inteiro(campoPrazoCancelamento, "Prazo de cancelamento"),
                        inteiro(campoTimeoutNfce, "Espera pela resposta da SEFAZ"),
                        inteiro(campoRetransmitir, "Intervalo do transmissor"),
                        numero(campoValorIdentificacao, "Valor que exige o CPF ou CNPJ na nota"),
                        numero(campoValorMaximo, "Valor que exige NF-e"), campoViaEstabelecimento.isSelected(),
                        inteiro(campoAlertaCertificado, "Aviso de vencimento do certificado"),
                        campoUrlQrCode.getText(), campoUrlConsulta.getText(),
                        new Configuracoes.RespTecnico(campoRespCnpj.getText(), campoRespContato.getText(),
                                campoRespEmail.getText(), campoRespFone.getText())));
    }

    // ----- NFC-e ligada e Testar comunicação (F23.6) -----

    /** "NFC-e ligada": só liga com o emitente completo e o certificado deste terminal na validade. */
    @FXML
    void alternarNfce() {
        boolean ligar = campoNfceAtiva.isSelected();
        try {
            nfceTerminal.ligar(sessao, ligar);
            gravadas = configuracaoService.carregar(sessao);
            Mensagem.sucessoNoRotulo(mensagemNfceAtiva, ligar ? "NFC-e ligada: o PDV emite a NFC-e (homologação) em cada venda."
                    : "NFC-e desligada: o PDV volta ao comprovante não fiscal.");
        } catch (NegocioException e) {
            campoNfceAtiva.setSelected(!ligar);
            Mensagem.erro(mensagemNfceAtiva, e.getMessage());
        } catch (RuntimeException e) {
            campoNfceAtiva.setSelected(!ligar);
            TratadorExcecoes.mostrar(e);
        }
    }

    /** "Testar comunicação": o status do serviço com o certificado deste terminal, numa {@link Task}. */
    @FXML
    void testarComunicacao() {
        Task<br.com.varesync.service.NfceTerminalService.TesteComunicacao> tarefa = new Task<>() {
            @Override
            protected br.com.varesync.service.NfceTerminalService.TesteComunicacao call() {
                return nfceTerminal.testarComunicacao(sessao);
            }
        };
        botaoTestarNfce.setDisable(true);
        Mensagem.informar(mensagemNfceAtiva, "Consultando o status do serviço da SEFAZ…");
        tarefa.setOnSucceeded(e -> {
            botaoTestarNfce.setDisable(false);
            var r = tarefa.getValue();
            if (r.emOperacao()) {
                Mensagem.sucessoNoRotulo(mensagemNfceAtiva, r.mensagem());
            } else {
                Mensagem.erro(mensagemNfceAtiva, r.mensagem());
            }
        });
        tarefa.setOnFailed(e -> {
            botaoTestarNfce.setDisable(false);
            Mensagem.erro(mensagemNfceAtiva, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("nfce-testar-comunicacao", tarefa);
    }

    // ----- Certificado A1 deste terminal (F23.2) -----

    /** Titular, CNPJ, vencimento e se a raiz do CNPJ bate com a da loja; nunca o caminho nem a senha. */
    private void mostrarCertificado() {
        boolean naVariavel = certificadoService.senhaNaVariavel();
        campoSenhaCertificado.setDisable(naVariavel);
        campoSenhaCertificado.setPromptText(naVariavel ? "vem da variável VARESYNC_CERT_SENHA" : "");
        situacaoCertificado.getStyleClass().removeAll("mensagem-erro", "mensagem-sucesso");
        try {
            var atual = certificadoService.atual(sessao);
            botaoRemoverCertificado.setDisable(atual.isEmpty());
            if (atual.isEmpty()) {
                situacaoCertificado.setText("Este terminal (" + sessao.terminal() + ") ainda não tem certificado. "
                        + "Escolha o arquivo .pfx ou .p12 do certificado A1 da loja e informe a senha.");
                return;
            }
            situacaoCertificado.setText(descreverCertificado(atual.get()));
            situacaoCertificado.getStyleClass().add(atual.get().problema().isPresent() ? "mensagem-erro"
                    : "mensagem-sucesso");
        } catch (NegocioException e) {
            botaoRemoverCertificado.setDisable(false);
            situacaoCertificado.setText("O certificado deste terminal não pôde ser aberto: " + e.getMessage());
            situacaoCertificado.getStyleClass().add("mensagem-erro");
        }
    }

    static String descreverCertificado(InfoCertificado i) {
        String validade = i.vencido() ? "vencido em " + Formatos.dataHora(i.validoAte())
                : "válido até " + Formatos.dataHora(i.validoAte()) + " (" + i.diasParaVencer()
                        + (i.diasParaVencer() == 1 ? " dia)" : " dias)");
        return "Titular: " + i.titular() + " · CNPJ: " + (i.cnpj().isEmpty() ? "não informado no certificado"
                : CpfCnpj.formatar(i.cnpj())) + " · " + validade + " · "
                + (i.raizConfere() ? "a raiz do CNPJ é a da loja" : "a raiz do CNPJ NÃO é a da loja")
                + i.problema().map(p -> ". " + p).orElse(".");
    }

    @FXML
    private void escolherCertificado() {
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Certificado A1 da loja");
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter("Certificado A1 (*.pfx, *.p12)", "*.pfx",
                "*.p12"));
        File arquivo = escolha.showOpenDialog(abas.getScene().getWindow());
        if (arquivo != null) {
            campoCertificado.setText(arquivo.getAbsolutePath());
        }
    }

    @FXML
    void salvarCertificado() {
        try {
            InfoCertificado info = certificadoService.salvar(sessao, campoCertificado.getText(),
                    campoSenhaCertificado.getText());
            campoSenhaCertificado.clear();
            campoCertificado.clear();
            mostrarCertificado();
            Mensagem.sucesso(mensagemCertificado, "Certificado deste terminal salvo: " + info.titular() + ".");
        } catch (NegocioException e) {
            Mensagem.erro(mensagemCertificado, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    @FXML
    private void removerCertificado() {
        if (!Dialogos.confirmar("Retirar o certificado deste terminal? Sem ele, este terminal não emite NFC-e.")) {
            return;
        }
        try {
            certificadoService.remover(sessao);
            mostrarCertificado();
            Mensagem.sucesso(mensagemCertificado, "Certificado retirado deste terminal.");
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    private static BigDecimal numero(TextField campo, String rotulo) {
        if (Textos.vazio(campo.getText())) {
            throw new NegocioException("Informe: " + rotulo + ".");
        }
        try {
            return Dinheiro.ler(campo.getText().replace("%", "").strip());
        } catch (IllegalArgumentException e) {
            throw new NegocioException(rotulo + ": valor inválido (\"" + campo.getText().strip() + "\").");
        }
    }

    private static int inteiro(TextField campo, String rotulo) {
        try {
            return Integer.parseInt(campo.getText().strip());
        } catch (NumberFormatException | NullPointerException e) {
            throw new NegocioException(rotulo + ": informe um número inteiro.");
        }
    }

    @FXML
    void salvar() {
        try {
            List<String> mudancas = configuracaoService.salvar(sessao, lerCampos());
            mostrar(configuracaoService.carregar(sessao));
            if (mudancas.isEmpty()) {
                Mensagem.informar(mensagemParametros, "Nada mudou.");
            } else {
                Mensagem.sucesso(mensagemParametros, "Configurações salvas: " + String.join("; ", mudancas) + ".");
            }
        } catch (NegocioException e) {
            Mensagem.erro(mensagemParametros, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    @FXML
    private void desfazer() {
        mostrar(gravadas);
        Mensagem.informar(mensagemParametros, "Campos voltaram ao que está gravado.");
    }

    /** Liga os campos à caixa "ler etiquetas" e mostra um exemplo de etiqueta com a configuração escolhida. */
    private void atualizarBalanca() {
        boolean ativa = campoBalancaAtiva.isSelected();
        campoPrefixo.setDisable(!ativa);
        campoDigitos.setDisable(!ativa);
        campoTipoValor.setDisable(!ativa);
        String prefixo = campoPrefixo.getText() == null ? "" : campoPrefixo.getText().strip();
        Integer digitos = campoDigitos.getValue();
        TipoValorBalanca tipo = campoTipoValor.getValue();
        if (!ativa || !prefixo.matches("\\d") || digitos == null || tipo == null) {
            exemploEtiqueta.setText(ativa ? "" : "Com a leitura desligada, o PDV não reconhece etiquetas e o campo "
                    + "PLU do cadastro de produto fica fechado.");
            return;
        }
        int casasValor = 11 - digitos;
        String plu = String.format("%0" + digitos + "d", 123);
        String valor = String.format("%0" + casasValor + "d", tipo == TipoValorBalanca.PRECO ? 1377 : 345);
        String semDv = prefixo + plu + valor;
        exemploEtiqueta.setText("Exemplo: " + prefixo + " " + plu + " " + valor + " " + Gtin.digitoVerificador(semDv)
                + " = PLU 123 com " + (tipo == TipoValorBalanca.PRECO ? "total de R$ 13,77" : "345 g") + ".");
    }

    // ----- Backup (6.12) -----

    /** Abre a tela já na aba Backup (alerta "último backup há mais de 7 dias" do Painel). */
    public void mostrarBackup() {
        abas.getSelectionModel().select(abaBackup);
    }

    /** Seções para onde o assistente "Primeiros passos" (F21.4) leva. */
    public enum Secao { LOJA, FORMAS, BALANCA, BACKUP, NFCE }

    public void mostrarSecao(Secao secao) {
        abas.getSelectionModel().select(switch (secao) {
            case LOJA -> abaLoja;
            case FORMAS -> abaFormas;
            case BALANCA -> abaBalanca;
            case BACKUP -> abaBackup;
            case NFCE -> abaNfce; // alerta do certificado no painel (F23.10)
        });
    }

    /**
     * F21.5: as seções numa lista à esquerda, no lugar da barra de abas, que já não cabia em 1280×720 e rolava (a
     * barra fica escondida pela classe {@code abas-ocultas}). A lista e as abas escolhem juntas.
     */
    private void prepararSecoes() {
        secoes.setItems(abas.getTabs());
        secoes.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(Tab aba, boolean vazio) {
                super.updateItem(aba, vazio);
                setText(vazio || aba == null ? null : aba.getText());
            }
        });
        secoes.getSelectionModel().select(abas.getSelectionModel().getSelectedItem());
        secoes.getSelectionModel().selectedItemProperty().addListener((o, a, aba) -> {
            if (aba != null && abas.getSelectionModel().getSelectedItem() != aba) {
                abas.getSelectionModel().select(aba);
            }
        });
        abas.getSelectionModel().selectedItemProperty().addListener((o, a, aba) -> {
            if (secoes.getSelectionModel().getSelectedItem() != aba) {
                secoes.getSelectionModel().select(aba);
                secoes.scrollTo(aba);
            }
        });
    }

    private void prepararTabelaBackups() {
        colunaBackupArquivo.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().nome()));
        colunaBackupTamanho.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                BackupService.tamanhoLegivel(c.getValue().tamanho())));
        colunaBackupData.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.dataHora(c.getValue().modificadoEm())));
    }

    /** Pasta, pg_dump encontrado, último backup e arquivos da pasta (pelo que está gravado). */
    private void atualizarBackup() {
        try {
            BackupService.Situacao s = backupService.situacao(sessao);
            situacaoPgDump.getStyleClass().remove("mensagem-erro");
            if (s.pgDump().isPresent()) {
                situacaoPgDump.setText("Será usado o pg_dump " + s.pgDump().get() + ". Pasta dos backups: "
                        + s.pasta() + ".");
            } else {
                situacaoPgDump.setText("O pg_dump não foi encontrado. Informe o caminho dele (ex.: C:\\Program Files\\"
                        + "PostgreSQL\\16\\bin\\pg_dump.exe) e salve. Pasta dos backups: " + s.pasta() + ".");
                situacaoPgDump.getStyleClass().add("mensagem-erro");
            }
            ultimoBackup.setText(s.ultimo().map(u -> "Último backup: " + Formatos.dataHora(u))
                    .orElse("Nenhum backup foi feito ainda."));
            tabelaBackups.getItems().setAll(s.arquivos());
        } catch (RuntimeException e) {
            Mensagem.erro(resultadoBackup, TratadorExcecoes.mensagem(e));
        }
    }

    @FXML
    private void escolherPastaBackup() {
        DirectoryChooser escolha = new DirectoryChooser();
        escolha.setTitle("Pasta dos backups");
        File atual = Textos.vazio(campoPastaBackup.getText()) ? null : new File(campoPastaBackup.getText().strip());
        if (atual != null && atual.isDirectory()) {
            escolha.setInitialDirectory(atual);
        }
        File pasta = escolha.showDialog(abas.getScene().getWindow());
        if (pasta != null) {
            campoPastaBackup.setText(pasta.getAbsolutePath());
            Mensagem.informar(mensagemParametros, "Clique em \"Salvar alterações\" para usar a nova pasta.");
        }
    }

    /** F18.2: pasta de onde o MGV importa o ITENSMGV.TXT. */
    @FXML
    private void escolherPastaCarga() {
        DirectoryChooser escolha = new DirectoryChooser();
        escolha.setTitle("Pasta da carga de balança");
        File atual = Textos.vazio(campoPastaCarga.getText()) ? null : new File(campoPastaCarga.getText().strip());
        if (atual != null && atual.isDirectory()) {
            escolha.setInitialDirectory(atual);
        }
        File pasta = escolha.showDialog(abas.getScene().getWindow());
        if (pasta != null) {
            campoPastaCarga.setText(pasta.getAbsolutePath());
            Mensagem.informar(mensagemParametros, "Clique em \"Salvar alterações\" para usar a nova pasta.");
        }
    }

    @FXML
    private void escolherPgDump() {
        FileChooser escolha = new FileChooser();
        escolha.setTitle("Programa pg_dump");
        escolha.getExtensionFilters().add(new FileChooser.ExtensionFilter("pg_dump", "pg_dump.exe", "pg_dump"));
        File arquivo = escolha.showOpenDialog(abas.getScene().getWindow());
        if (arquivo != null) {
            campoPgDump.setText(arquivo.getAbsolutePath());
            Mensagem.informar(mensagemParametros, "Clique em \"Salvar alterações\" para usar este pg_dump.");
        }
    }

    /** Backup manual, em {@link Task}, com a configuração gravada (campos alterados pedem "Salvar" antes). */
    @FXML
    private void fazerBackup() {
        Configuracoes naTela;
        try {
            naTela = lerCampos();
        } catch (NegocioException e) {
            naTela = null;
        }
        if (naTela == null || !naTela.backup().equals(gravadas.backup())) {
            Mensagem.erro(resultadoBackup, "Os campos de backup foram alterados: clique em \"Salvar alterações\" "
                    + "antes de fazer o backup.");
            return;
        }
        Task<BackupService.Resultado> tarefa = new Task<>() {
            @Override
            protected BackupService.Resultado call() {
                return backupService.fazer(sessao);
            }
        };
        botaoFazerBackup.setDisable(true);
        progressoBackup.setVisible(true);
        Mensagem.informar(resultadoBackup, "Fazendo o backup… (com muitos dados pode levar alguns minutos)");
        tarefa.setOnSucceeded(e -> {
            botaoFazerBackup.setDisable(false);
            progressoBackup.setVisible(false);
            BackupService.Resultado r = tarefa.getValue();
            Mensagem.sucesso(resultadoBackup, "Backup gravado em " + r.arquivo() + " ("
                    + BackupService.tamanhoLegivel(r.tamanho()) + ")."
                    + (r.removidos().isEmpty() ? "" : " Apagados os mais antigos: " + String.join(", ", r.removidos())
                    + "."));
            atualizarBackup();
        });
        tarefa.setOnFailed(e -> {
            botaoFazerBackup.setDisable(false);
            progressoBackup.setVisible(false);
            Mensagem.erro(resultadoBackup, TratadorExcecoes.mensagem(tarefa.getException()));
            atualizarBackup();
        });
        Tarefas.executar("backup-manual", tarefa);
    }

    // ----- Auditoria (6.13) -----

    /** Opção de filtro: valor nulo = todos. */
    record Escolha<T>(T valor, String rotulo) {
        @Override
        public String toString() {
            return rotulo;
        }
    }

    private void prepararTabelaAuditoria() {
        colunaAuditoriaData.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formatos.dataHoraComSegundos(c.getValue().criadoEm())));
        colunaAuditoriaUsuario.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().usuario() == null ? "(não identificado)" : c.getValue().usuario()));
        colunaAuditoriaAcao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().acao().rotulo()));
        colunaAuditoriaDescricao.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().descricao()));
        colunaAuditoriaTerminal.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().terminal()));
        auditoriaInicio.setConverter(new ConversorData());
        auditoriaFim.setConverter(new ConversorData());
        tabelaAuditoria.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> detalheAuditoria.setText(
                r == null ? "" : Formatos.dataHoraComSegundos(r.criadoEm()) + " — " + r.acao().rotulo() + ": "
                        + (r.descricao() == null ? "" : r.descricao())));
        List<Escolha<AcaoAuditoria>> acoes = new ArrayList<>();
        acoes.add(new Escolha<>(null, "Todas"));
        Arrays.stream(AcaoAuditoria.values()).sorted(Comparator.comparing(AcaoAuditoria::rotulo))
                .forEach(a -> acoes.add(new Escolha<>(a, a.rotulo())));
        auditoriaAcao.getItems().setAll(acoes);
        auditoriaAcao.setValue(acoes.get(0));
    }

    /** Na primeira abertura da aba: lista de usuários (inclusive inativos), período padrão e a consulta. */
    private void prepararAuditoria() {
        auditoriaPreparada = true;
        List<Escolha<Long>> usuarios = new ArrayList<>();
        usuarios.add(new Escolha<>(null, "Todos"));
        try {
            usuarioService.listar(sessao).forEach(u -> usuarios.add(new Escolha<>(u.id(), u.nome() + " ("
                    + u.login() + ")")));
        } catch (RuntimeException e) {
            Mensagem.erro(resumoAuditoria, TratadorExcecoes.mensagem(e));
        }
        auditoriaUsuario.getItems().setAll(usuarios);
        auditoriaUsuario.setValue(usuarios.get(0));
        Periodo padrao = auditoriaService.periodoPadrao();
        auditoriaInicio.setValue(padrao.inicio());
        auditoriaFim.setValue(padrao.fim());
        consultarAuditoria();
    }

    @FXML
    private void consultarAuditoria() {
        Periodo periodo = new Periodo(data(auditoriaInicio), data(auditoriaFim));
        Long usuarioId = auditoriaUsuario.getValue() == null ? null : auditoriaUsuario.getValue().valor();
        AcaoAuditoria acao = auditoriaAcao.getValue() == null ? null : auditoriaAcao.getValue().valor();
        Task<ConsultaAuditoriaService.Consulta> tarefa = new Task<>() {
            @Override
            protected ConsultaAuditoriaService.Consulta call() {
                return auditoriaService.consultar(sessao, periodo, usuarioId, acao);
            }
        };
        botaoConsultarAuditoria.setDisable(true);
        progressoAuditoria.setVisible(true);
        Mensagem.informar(resumoAuditoria, "Consultando…");
        tarefa.setOnSucceeded(e -> {
            botaoConsultarAuditoria.setDisable(false);
            progressoAuditoria.setVisible(false);
            ConsultaAuditoriaService.Consulta c = tarefa.getValue();
            tabelaAuditoria.getItems().setAll(c.registros());
            detalheAuditoria.setText("");
            int n = c.registros().size();
            String texto = (n == 1 ? "1 registro" : Formatos.numero(BigDecimal.valueOf(n), 0) + " registros")
                    + ", do mais recente para o mais antigo. Selecione uma linha para ler a descrição inteira.";
            if (c.limitado()) {
                Mensagem.erro(resumoAuditoria, "Mostrando os " + Formatos.numero(BigDecimal.valueOf(n), 0)
                        + " registros mais recentes; há mais no filtro. Reduza o período ou escolha o usuário ou a "
                        + "ação.");
            } else {
                Mensagem.informar(resumoAuditoria, texto);
            }
        });
        tarefa.setOnFailed(e -> {
            botaoConsultarAuditoria.setDisable(false);
            progressoAuditoria.setVisible(false);
            Mensagem.erro(resumoAuditoria, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("consulta-auditoria", tarefa);
    }

    /** O texto digitado vale mesmo sem sair do campo; inválido vira nulo (o service pede a data). */
    private static LocalDate data(DatePicker campo) {
        LocalDate lida = campo.getConverter().fromString(campo.getEditor().getText());
        campo.setValue(lida);
        return lida;
    }

    // ----- Formas de pagamento -----

    private void carregarFormas(Integer selecionar) {
        tabelaFormas.getItems().setAll(configuracaoService.formasPagamento(sessao));
        if (selecionar != null) {
            tabelaFormas.getItems().stream().filter(f -> f.id() == selecionar).findFirst()
                    .ifPresent(f -> tabelaFormas.getSelectionModel().select(f));
        }
    }

    @FXML
    void criarForma() {
        try {
            int id = configuracaoService.criarForma(sessao, campoNovaForma.getText(), campoTipoForma.getValue());
            carregarFormas(id);
            Mensagem.sucesso(mensagemFormas, "Forma de pagamento \"" + Textos.normalizarEspacos(campoNovaForma.getText())
                    + "\" criada.");
            campoNovaForma.clear();
        } catch (NegocioException e) {
            Mensagem.erro(mensagemFormas, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    @FXML
    private void alternarForma() {
        FormaPagamentoCadastro forma = tabelaFormas.getSelectionModel().getSelectedItem();
        if (forma == null) {
            return;
        }
        if (forma.ativa() && !Dialogos.confirmar("Desativar a forma de pagamento \"" + forma.descricao()
                + "\"? Ela deixa de aparecer no pagamento do PDV.")) {
            return;
        }
        try {
            configuracaoService.alterarSituacaoForma(sessao, forma.id(), !forma.ativa());
            carregarFormas(forma.id());
            Mensagem.sucesso(mensagemFormas, "\"" + forma.descricao() + "\" " + (forma.ativa() ? "desativada."
                    : "ativada."));
        } catch (NegocioException e) {
            Mensagem.erro(mensagemFormas, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    /** Mostra como a forma selecionada recebe (F15.2); dinheiro, crédito e crediário não têm recebimento. */
    private void mostrarRecebimento(FormaPagamentoCadastro f) {
        painelRecebimento.setDisable(f == null || !f.aceitaContaDestino());
        painelTpag.setDisable(f == null);
        campoTpagForma.setValue(f == null ? null : MeioPagamentoNfce.doCodigo(f.tpag()).orElse(null));
        campoTaxaForma.setText(f == null ? "" : Formatos.numero(f.taxaPct(), Dinheiro.ESCALA_PERCENTUAL));
        campoDiasForma.setText(f == null ? "" : String.valueOf(f.diasRecebimento()));
        campoContaForma.getItems().setAll(configuracaoService.contasAtivas(sessao));
        campoContaForma.setValue(f == null || f.contaDestinoId() == null ? null : campoContaForma.getItems().stream()
                .filter(c -> c.id() == f.contaDestinoId()).findFirst().orElse(null));
    }

    @FXML
    private void limparContaForma() {
        campoContaForma.setValue(null);
    }

    @FXML
    void salvarRecebimentoForma() {
        FormaPagamentoCadastro forma = tabelaFormas.getSelectionModel().getSelectedItem();
        if (forma == null) {
            return;
        }
        try {
            configuracaoService.alterarRecebimentoForma(sessao, forma.id(), numero(campoTaxaForma, "Taxa"),
                    inteiro(campoDiasForma, "Dias para receber"),
                    campoContaForma.getValue() == null ? null : campoContaForma.getValue().id());
            carregarFormas(forma.id());
            Mensagem.sucesso(mensagemFormas, "Recebimento de \"" + forma.descricao() + "\" salvo. Vale para os próximos "
                    + "pagamentos.");
        } catch (NegocioException e) {
            Mensagem.erro(mensagemFormas, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    @FXML
    void salvarTpagForma() {
        FormaPagamentoCadastro forma = tabelaFormas.getSelectionModel().getSelectedItem();
        if (forma == null) {
            return;
        }
        try {
            MeioPagamentoNfce meio = campoTpagForma.getValue();
            configuracaoService.alterarTpagForma(sessao, forma.id(), meio == null ? null : meio.codigo());
            carregarFormas(forma.id());
            Mensagem.sucesso(mensagemFormas, "Meio de pagamento na NFC-e de \"" + forma.descricao() + "\": " + meio
                    + ".");
        } catch (NegocioException e) {
            Mensagem.erro(mensagemFormas, e.getMessage());
        } catch (RuntimeException e) {
            TratadorExcecoes.mostrar(e);
        }
    }

    // ----- Diagnóstico e dados de demonstração -----

    private static void coluna(TableColumn<InconsistenciaEstoque, String> coluna,
                               Function<InconsistenciaEstoque, String> valor) {
        coluna.setCellValueFactory(c -> new ReadOnlyStringWrapper(valor.apply(c.getValue())));
    }

    /** Sem a unidade do produto aqui: 3 casas sempre, para não esconder diferença fracionada. */
    private static String quantidade(BigDecimal valor) {
        return Formatos.numero(valor, Dinheiro.ESCALA_QUANTIDADE);
    }

    /** Estoque (6.4) e crédito dos clientes (F12.3): as duas views de inconsistência precisam vir vazias. */
    private record Diagnostico(List<InconsistenciaEstoque> estoque, List<InconsistenciaCredito> credito,
                               List<InconsistenciaAbatimento> abatimento, List<InconsistenciaConta> conta,
                               List<String> lotes, List<String> nfce) {
    }

    @FXML
    private void verificar() {
        Task<Diagnostico> tarefa = new Task<>() {
            @Override
            protected Diagnostico call() {
                return new Diagnostico(estoqueService.diagnostico(sessao), creditoService.diagnostico(sessao),
                        clienteService.diagnosticoAbatimentos(sessao), contaService.diagnostico(sessao),
                        estoqueService.diagnosticoLotes(sessao), nfceService.diagnostico(sessao));
            }
        };
        botaoVerificar.setDisable(true);
        progresso.setVisible(true);
        Mensagem.informar(resultado, "Verificando…");
        Mensagem.limpar(resultadoCredito);
        Mensagem.limpar(resultadoAbatimento);
        Mensagem.limpar(resultadoConta);
        Mensagem.limpar(resultadoLotes);
        Mensagem.limpar(resultadoNfce);
        tarefa.setOnSucceeded(e -> {
            concluir();
            List<String> nfce = tarefa.getValue().nfce();
            listaNfce.getItems().setAll(nfce);
            if (nfce.isEmpty()) {
                Mensagem.sucesso(resultadoNfce, "Tudo certo: nenhuma NFC-e autorizada de venda cancelada nem NFC-e "
                        + "cancelada de venda finalizada.");
            } else {
                Mensagem.erro(resultadoNfce, nfce.size() + " NFC-e com a situação diferente da venda. Anote as linhas "
                        + "e procure o suporte.");
            }
            List<String> lotes = tarefa.getValue().lotes();
            listaLotes.getItems().setAll(lotes);
            if (lotes.isEmpty()) {
                Mensagem.sucesso(resultadoLotes, "Tudo certo: o saldo de todos os lotes confere com o razão e, nos "
                        + "produtos com controle de validade, a soma dos lotes confere com o saldo do produto.");
            } else {
                Mensagem.erro(resultadoLotes, lotes.size() + " diferença(s) nos lotes. Isso indica alteração feita fora "
                        + "do sistema; anote as linhas e procure o suporte.");
            }
            List<InconsistenciaConta> conta = tarefa.getValue().conta();
            tabelaConta.getItems().setAll(conta);
            if (conta.isEmpty()) {
                Mensagem.sucesso(resultadoConta, "Tudo certo: o saldo de todas as contas confere com o extrato.");
            } else {
                Mensagem.erro(resultadoConta, conta.size() + " conta(s) com o saldo diferente da soma do extrato. Isso "
                        + "indica alteração feita fora do sistema; anote as contas e procure o suporte.");
            }
            List<InconsistenciaEstoque> linhas = tarefa.getValue().estoque();
            tabelaInconsistencias.getItems().setAll(linhas);
            if (linhas.isEmpty()) {
                Mensagem.sucesso(resultado, "Tudo certo: o saldo de todos os produtos confere com a soma dos "
                        + "movimentos.");
            } else {
                Mensagem.erro(resultado, (linhas.size() == 1 ? "1 produto tem" : linhas.size() + " produtos têm")
                        + " saldo diferente da soma dos movimentos. Isso indica alteração feita fora do sistema; "
                        + "anote os produtos e procure o suporte.");
            }
            List<InconsistenciaCredito> credito = tarefa.getValue().credito();
            tabelaCredito.getItems().setAll(credito);
            if (credito.isEmpty()) {
                Mensagem.sucesso(resultadoCredito, "Tudo certo: o saldo de crédito de todos os clientes confere com "
                        + "a soma do extrato.");
            } else {
                Mensagem.erro(resultadoCredito, (credito.size() == 1 ? "1 cliente tem" : credito.size()
                        + " clientes têm") + " saldo de crédito diferente da soma do extrato. Isso indica alteração "
                        + "feita fora do sistema; anote os clientes e procure o suporte.");
            }
            List<InconsistenciaAbatimento> abatimento = tarefa.getValue().abatimento();
            tabelaAbatimento.getItems().setAll(abatimento);
            if (abatimento.isEmpty()) {
                Mensagem.sucesso(resultadoAbatimento, "Tudo certo: o abatido de todos os títulos e devoluções confere "
                        + "com os abatimentos por parcela.");
            } else {
                Mensagem.erro(resultadoAbatimento, abatimento.size() + " registro(s) com o abatido diferente da soma "
                        + "por parcela. Isso indica alteração feita fora do sistema; anote os números e procure o "
                        + "suporte.");
            }
        });
        tarefa.setOnFailed(e -> {
            concluir();
            Mensagem.erro(resultado, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("diagnostico-estoque", tarefa);
    }

    private void concluir() {
        botaoVerificar.setDisable(false);
        progresso.setVisible(false);
    }

    /**
     * Gerador de dados de demonstração (6.14): confere o banco (sem vendas, sem caixa aberto), pede confirmação e
     * gera em {@link Task} com a barra de progresso. No fim mostra os usuários criados e as senhas provisórias.
     */
    @FXML
    private void gerarDemo() {
        try {
            geradorDemo.conferir(sessao);
        } catch (RuntimeException e) {
            Mensagem.erro(situacaoDemo, TratadorExcecoes.mensagem(e));
            return;
        }
        if (!Dialogos.confirmar("Gerar os dados de demonstração neste banco? Serão criados usuários, fornecedores, "
                + "cerca de 80 produtos, 150 clientes e " + GeradorDemo.DIAS + " dias de vendas, compras, caixas e "
                + "movimentos de estoque, com a fidelidade (cashback) ligada. Isso leva alguns minutos e não pode ser "
                + "desfeito.")) {
            return;
        }
        Task<GeradorDemo.Resultado> tarefa = new Task<>() {
            @Override
            protected GeradorDemo.Resultado call() {
                return geradorDemo.gerar(sessao, (feito, total, mensagem) -> {
                    updateProgress(feito, total);
                    updateMessage(mensagem);
                });
            }
        };
        botaoGerarDemo.setDisable(true);
        botaoVerificar.setDisable(true);
        progressoDemo.setVisible(true);
        Mensagem.limpar(situacaoDemo);
        progressoDemo.progressProperty().bind(tarefa.progressProperty());
        situacaoDemo.textProperty().bind(tarefa.messageProperty());
        tarefa.setOnSucceeded(e -> {
            concluirDemo();
            GeradorDemo.Resultado r = tarefa.getValue();
            Mensagem.sucesso(situacaoDemo, "Dados de demonstração gerados: " + r.produtos() + " produtos e "
                    + r.vendas() + " vendas de " + Formatos.data(r.inicio()) + " a " + Formatos.data(r.fim()) + ".");
            resultadoDemo.setText(textoResultado(r));
            resultadoDemo.setVisible(true);
            resultadoDemo.setManaged(true);
        });
        tarefa.setOnFailed(e -> {
            concluirDemo();
            Mensagem.erro(situacaoDemo, TratadorExcecoes.mensagem(tarefa.getException())
                    + " Os dados gravados até a falha continuam no banco; para recomeçar, recrie o banco de "
                    + "demonstração (README).");
        });
        Tarefas.executar("dados-demonstracao", tarefa);
    }

    private void concluirDemo() {
        progressoDemo.progressProperty().unbind();
        situacaoDemo.textProperty().unbind();
        progressoDemo.setVisible(false);
        botaoGerarDemo.setDisable(false);
        botaoVerificar.setDisable(false);
    }

    static String textoResultado(GeradorDemo.Resultado r) {
        StringBuilder texto = new StringBuilder("Usuários criados (anote as senhas: elas não serão mostradas de "
                + "novo; cada um troca a senha no primeiro login):\n\n");
        for (GeradorDemo.UsuarioDemo u : r.usuarios()) {
            texto.append(String.format("  %-8s %-18s %-20s senha: %s%n", u.login(), u.nome(), u.perfil(), u.senha()));
        }
        texto.append("\nVendas: ").append(r.vendas()).append(" (").append(r.vendasCanceladas())
                .append(" canceladas, ").append(r.itensCancelados()).append(" itens cancelados durante a venda)")
                .append("\nFornecedores: ").append(r.fornecedores()).append(" (").append(r.entradas())
                .append(" entradas de mercadoria confirmadas)")
                .append("\nClientes: ").append(r.clientes()).append(" (").append(r.vendasComCliente())
                .append(" vendas identificadas, ").append(r.usosDeCredito())
                .append(" com uso de crédito; fidelidade ligada com 1% de cashback)")
                .append("\nDevoluções: ").append(r.devolucoes())
                .append("\nCrediário: ").append(r.clientesComLimite()).append(" clientes com limite, ")
                .append(r.vendasCrediario()).append(" vendas a prazo, ").append(r.recebimentos())
                .append(" recebimentos, ").append(r.titulosVencidos()).append(" títulos vencidos em aberto")
                .append("\nFinanceiro: integração do caixa ligada, ").append(r.contasPagas())
                .append(" contas pagas, ").append(r.recebiveisConciliados())
                .append(" recebíveis conciliados (até a semana anterior)")
                .append("\nInventário aplicado: nº ").append(r.inventarioId())
                .append("\nPeríodo: ").append(Formatos.data(r.inicio())).append(" a ").append(Formatos.data(r.fim()));
        return texto.toString();
    }

    // ----- Etiquetas (F18.1) -----

    /** Lista as impressoras instaladas no combo da térmica, mantendo o nome digitado. */
    private void carregarImpressoras() {
        String atual = campoImpressoraTermica.getEditor().getText();
        try {
            campoImpressoraTermica.getItems().setAll(ImpressaoTermica.impressorasInstaladas());
        } catch (RuntimeException e) {
            campoImpressoraTermica.getItems().clear();
        }
        campoImpressoraTermica.getEditor().setText(atual);
    }

    @FXML
    private void exemploZpl() {
        usarExemplo(ImpressaoTermica.Exemplo.ZPL);
    }

    @FXML
    private void exemploDpl() {
        usarExemplo(ImpressaoTermica.Exemplo.DPL);
    }

    private void usarExemplo(ImpressaoTermica.Exemplo exemplo) {
        if (!Textos.vazio(campoModeloTermica.getText())
                && !Dialogos.confirmar("Trocar o modelo atual pelo exemplo " + exemplo.rotulo() + "?")) {
            return;
        }
        campoModeloTermica.setText(exemplo.texto());
        Mensagem.informar(mensagemEtiquetas, "Exemplo " + exemplo.rotulo() + " carregado. Confira-o com uma "
                + "impressão de teste na impressora da loja e salve as alterações.");
    }

    /** Imprime um produto fictício com o modelo e a impressora como estão na tela (sem precisar salvar antes). */
    @FXML
    private void imprimirTeste() {
        String impressora = campoImpressoraTermica.getEditor().getText();
        String modelo = campoModeloTermica.getText();
        LocalDate hoje = LocalDate.now(clock);
        Task<Void> tarefa = new Task<>() {
            @Override
            protected Void call() {
                impressaoTermica.imprimirTeste(impressora, modelo, hoje);
                return null;
            }
        };
        botaoImprimirTeste.setDisable(true);
        Mensagem.informar(mensagemEtiquetas, "Enviando a etiqueta de teste…");
        tarefa.setOnSucceeded(e -> {
            botaoImprimirTeste.setDisable(false);
            Mensagem.sucesso(mensagemEtiquetas, "Etiqueta de teste enviada para \"" + impressora.strip() + "\".");
        });
        tarefa.setOnFailed(e -> {
            botaoImprimirTeste.setDisable(false);
            Mensagem.erro(mensagemEtiquetas, TratadorExcecoes.mensagem(tarefa.getException()));
        });
        Tarefas.executar("etiqueta-teste", tarefa);
    }
}
