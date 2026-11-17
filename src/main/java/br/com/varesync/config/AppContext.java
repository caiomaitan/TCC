package br.com.varesync.config;

import br.com.varesync.controller.CaixaController;
import br.com.varesync.controller.FiscalController;
import br.com.varesync.controller.PerfisTributariosController;
import br.com.varesync.controller.CategoriasController;
import br.com.varesync.controller.ClientesController;
import br.com.varesync.controller.DevolucoesController;
import br.com.varesync.controller.UnidadesController;
import br.com.varesync.controller.ComprasController;
import br.com.varesync.controller.PedidosController;
import br.com.varesync.controller.ConfiguracoesController;
import br.com.varesync.controller.EstoqueController;
import br.com.varesync.controller.FinanceiroController;
import br.com.varesync.controller.FornecedoresController;
import br.com.varesync.controller.ImportacaoProdutosController;
import br.com.varesync.controller.InventarioController;
import br.com.varesync.controller.LoginController;
import br.com.varesync.controller.Navegacao;
import br.com.varesync.controller.NotasFiscaisController;
import br.com.varesync.controller.PainelController;
import br.com.varesync.controller.PdvController;
import br.com.varesync.controller.PrimeiroAcessoController;
import br.com.varesync.controller.PrincipalController;
import br.com.varesync.controller.AcoesBusca;
import br.com.varesync.controller.ProdutoController;
import br.com.varesync.controller.ProdutosController;
import br.com.varesync.controller.PromocoesController;
import br.com.varesync.controller.EtiquetasController;
import br.com.varesync.controller.CargaBalancaController;
import br.com.varesync.report.EtiquetasPdf;
import br.com.varesync.report.ImpressaoTermica;
import br.com.varesync.controller.RelatoriosController;
import br.com.varesync.controller.TrocaSenhaController;
import br.com.varesync.controller.UsuariosController;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.FechamentoCaixaPdf;
import br.com.varesync.report.ImpressaoComprovante;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.AuditoriaService;
import br.com.varesync.service.BackupService;
import br.com.varesync.service.ExecutorProcesso;
import br.com.varesync.service.GeradorDemo;
import br.com.varesync.service.PreferenciaService;
import br.com.varesync.service.Senhas;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.Transacao;
import com.zaxxer.hikari.HikariDataSource;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.dao.NumeracaoNfceDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.service.CancelamentoNfceService;
import br.com.varesync.service.CertificadoService;
import br.com.varesync.service.NfceService;
import br.com.varesync.service.EmissaoNfceService;
import br.com.varesync.service.ModoContingencia;
import br.com.varesync.service.TransmissorAgendado;
import br.com.varesync.service.TransmissorNfce;
import br.com.varesync.service.NfceNaVenda;
import br.com.varesync.service.NfceTerminalService;
import br.com.varesync.service.NotasFiscaisService;
import br.com.varesync.service.TributosVendaService;
import java.util.function.Function;
import br.com.varesync.service.nfce.Sefaz;
import br.com.varesync.service.nfce.SefazHomologacao;
import br.com.varesync.service.nfce.AssinaturaNfce;
import br.com.varesync.service.nfce.GeracaoNfce;
import br.com.varesync.service.nfce.MontagemNfce;
import br.com.varesync.service.nfce.ValidacaoXsd;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * Composition root: cria DataSource, Clock, DAOs e services (injeção pelo construtor, em {@link Servicos})
 * e entrega os controllers ao {@code FXMLLoader} por {@link #criarController(Class, Navegacao, Sessao)}.
 */
public final class AppContext implements AutoCloseable {

    private final AppConfig config;
    private final HikariDataSource dataSource;
    private final Clock clock;
    private final Servicos s;
    private final GeradorDemo geradorDemo;
    private final BackupService backup;
    private final ComprovantePdf comprovantePdf = new ComprovantePdf();
    private final ImpressaoComprovante impressaoComprovante;
    private final ImpressaoTermica impressaoTermica = new ImpressaoTermica();
    private final EtiquetasPdf etiquetasPdf = new EtiquetasPdf();
    private final RelatorioPdf relatorioPdf = new RelatorioPdf();
    private final FechamentoCaixaPdf fechamentoPdf = new FechamentoCaixaPdf();
    private final CertificadoService certificados;
    private final NfceService nfce;
    private final EmissaoNfceService emissaoNfce;
    private final TransmissorNfce transmissorNfce;
    private final TransmissorAgendado transmissorAgendado;
    private final NfceTerminalService nfceTerminal;
    private final NotasFiscaisService notasFiscais;

    /**
     * Peças trocadas só no roteiro de tela (F23.6): a SEFAZ falsa e a impressora que não imprime, para nada ir à rede
     * nem ao papel. Nulo = a do sistema.
     */
    public record Ajustes(Sefaz sefaz, Function<ComprovantePdf, ImpressaoComprovante> impressao) {
        public static final Ajustes NENHUM = new Ajustes(null, null);
    }

    AppContext(AppConfig config, HikariDataSource dataSource, Clock clock, Ajustes ajustes) {
        this.config = config;
        this.dataSource = dataSource;
        this.clock = clock;
        this.impressaoComprovante = ajustes.impressao() == null ? new ImpressaoComprovante(comprovantePdf)
                : ajustes.impressao().apply(comprovantePdf);
        Senhas senhas = new Senhas();
        // F23: a NFC-e é montada antes dos serviços, porque a venda a usa (F23.6).
        Transacao transacaoNfce = new Transacao(dataSource);
        // F23: o certificado é do terminal (config.properties); os XSD são copiados para ~/.varesync/schemas.
        this.certificados = new CertificadoService(transacaoNfce, new ConfiguracaoDAO(),
                new AuditoriaService(new AuditoriaDAO(), clock, config.terminalId()),
                new CertificadoNoArquivo(config.arquivo()), clock);
        ZoneId fuso = ZoneId.systemDefault();
        ValidacaoXsd validacao = new ValidacaoXsd(AppConfig.pastaPadrao().resolve("schemas"));
        AssinaturaNfce assinatura = new AssinaturaNfce(
                AppConfig.pastaPadrao().resolve("schemas").resolve(ValidacaoXsd.VERSAO_PACOTE), fuso);
        this.nfce = new NfceService(transacaoNfce, new NfceDAO(), new NumeracaoNfceDAO(), new TributoVendaDAO(),
                new ConfiguracaoDAO(), new GeracaoNfce(new MontagemNfce(fuso), assinatura, validacao), certificados,
                VersaoSistema.doBuild().versao(), new SecureRandom(), clock, fuso);
        // F23.4 e F23.5: a SEFAZ de homologação, o modo contingência deste terminal, a emissão e o transmissor; antes
        // dos serviços, porque o cancelamento da venda (F23.8) usa a SEFAZ.
        AuditoriaService auditoriaNfce = new AuditoriaService(new AuditoriaDAO(), clock, config.terminalId());
        Sefaz sefaz = ajustes.sefaz() == null ? new SefazHomologacao(assinatura) : ajustes.sefaz();
        this.emissaoNfce = new EmissaoNfceService(transacaoNfce, new NfceDAO(), nfce, certificados,
                new ConfiguracaoDAO(), sefaz, new ModoContingencia(), auditoriaNfce, clock, fuso, config.terminalId());
        CancelamentoNfceService cancelamentoNfce = new CancelamentoNfceService(new NfceDAO(), new ConfiguracaoDAO(),
                emissaoNfce, certificados, sefaz, auditoriaNfce, clock);
        this.s = Servicos.montar(dataSource, clock, config.terminalId(), senhas,
                new NfceNaVenda(new ConfiguracaoDAO(), new TributoVendaDAO(), certificados, nfce, cancelamentoNfce));
        this.geradorDemo = Servicos.geradorDemo(dataSource, clock, config.terminalId(), senhas);
        this.backup = new BackupService(s.transacao(), new ConfiguracaoDAO(), new AuditoriaDAO(),
                new AuditoriaService(new AuditoriaDAO(), clock, config.terminalId()), ExecutorProcesso.sistema(),
                conexaoBackup(config), AppConfig.pastaPadrao().resolve("backups"), BackupService.instalacoesPadrao(),
                System.getenv("PATH"), clock);
        this.transmissorNfce = new TransmissorNfce(transacaoNfce, new NfceDAO(), emissaoNfce, certificados, sefaz,
                auditoriaNfce, clock, config.terminalId());
        // F23.6 e F23.7: a NFC-e no PDV (emissão, DANFE, aviso da contingência) e em Configurações (ligar, testar).
        this.nfceTerminal = new NfceTerminalService(transacaoNfce, new NfceDAO(), new ConfiguracaoDAO(),
                new TributoVendaDAO(), emissaoNfce, certificados, sefaz, auditoriaNfce);
        // F23.9 e F23.10: a aba Notas fiscais e os alertas da NFC-e no painel.
        this.notasFiscais = new NotasFiscaisService(transacaoNfce, new NfceDAO(), new NumeracaoNfceDAO(),
                new ConfiguracaoDAO(), new TributosVendaService(new TributoVendaDAO(), new ConfiguracaoDAO()), emissaoNfce,
                transmissorNfce, nfceTerminal, certificados, sefaz, auditoriaNfce, clock, fuso);
        ConfiguracaoDAO configuracaoDAO = new ConfiguracaoDAO();
        this.transmissorAgendado = new TransmissorAgendado(
                () -> s.transacao().executar(configuracaoDAO::carregar).nfce(), transmissorNfce::transmitir, clock);
    }

    /** Banco do backup, pela URL da configuração local; URL fora do padrão deixa o backup indisponível (com aviso). */
    private static BackupService.Conexao conexaoBackup(AppConfig config) {
        try {
            return BackupService.Conexao.deUrl(config.url(), config.usuario(), config.senha());
        } catch (IllegalArgumentException e) {
            LoggerFactory.getLogger(AppContext.class).warn("Backup indisponível: {}", e.getMessage());
            return null;
        }
    }

    /** Abre o pool e aplica as migrations pendentes. */
    public static AppContext iniciar(AppConfig config) {
        return iniciar(config, Ajustes.NENHUM);
    }

    /** @param ajustes as peças do roteiro de tela ({@link Ajustes#NENHUM} no sistema) */
    public static AppContext iniciar(AppConfig config, Ajustes ajustes) {
        HikariDataSource dataSource = Banco.criarPool(config);
        try {
            new Migracao(dataSource).migrar();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new AppContext(config, dataSource, Clock.systemDefaultZone(), ajustes);
    }

    /**
     * Fábrica de controllers para {@code FXMLLoader.setControllerFactory}.
     *
     * @param sessao usuário logado; nulo nas telas de primeiro acesso e login
     */
    public Object criarController(Class<?> tipo, Navegacao navegacao, Sessao sessao) {
        if (tipo == PrimeiroAcessoController.class) {
            return new PrimeiroAcessoController(s.acesso(), navegacao);
        }
        if (tipo == LoginController.class) {
            return new LoginController(s.acesso(), navegacao);
        }
        if (tipo == TrocaSenhaController.class) {
            return new TrocaSenhaController(s.acesso(), navegacao, sessao);
        }
        if (tipo == PrincipalController.class) {
            // As telas abertas pelo menu recebem a sessão do momento (trocada quando o acesso muda, F10.1).
            return new PrincipalController(config, clock, s.acesso(), s.preferencias(), s.caixa(), backup,
                    fechamentoPdf, navegacao, sessao, t -> criarController(t, navegacao, navegacao.sessaoAtual()),
                    s.busca(), new AcoesBusca(s.vendas(), s.devolucoes(), s.clientes(), s.acesso(), comprovantePdf,
                            impressaoComprovante));
        }
        if (tipo == UsuariosController.class) {
            return new UsuariosController(s.usuarios(), sessao, clock);
        }
        if (tipo == ProdutosController.class) {
            return new ProdutosController(s.produtos(), s.categorias(), sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        if (tipo == ProdutoController.class) {
            return new ProdutoController(s.produtos(), s.categorias(), s.vinculos(), sessao);
        }
        if (tipo == CategoriasController.class) {
            return new CategoriasController(s.categorias(), sessao);
        }
        if (tipo == UnidadesController.class) {
            return new UnidadesController(s.unidades(), sessao);
        }
        if (tipo == EstoqueController.class) {
            return new EstoqueController(s.estoque(), sessao);
        }
        if (tipo == InventarioController.class) {
            return new InventarioController(s.inventarios(), s.acesso(), relatorioPdf, sessao);
        }
        if (tipo == FornecedoresController.class) {
            return new FornecedoresController(s.fornecedores(), sessao);
        }
        if (tipo == ComprasController.class) {
            return new ComprasController(s.compras(), s.nfe(), s.sugestao(), s.pedidos(), s.fiscal(), sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        if (tipo == PedidosController.class) {
            return new PedidosController(s.pedidos(), s.compras(), relatorioPdf, sessao);
        }
        if (tipo == CaixaController.class) {
            return new CaixaController(s.caixa(), s.recebimentos(), s.acesso(), fechamentoPdf, comprovantePdf,
                    impressaoComprovante, sessao);
        }
        if (tipo == PdvController.class) {
            return new PdvController(s.vendas(), s.clientes(), s.devolucoes(), s.caixa(), s.acesso(), comprovantePdf,
                    impressaoComprovante, fechamentoPdf, s.recebimentos(), nfceTerminal, sessao);
        }
        if (tipo == DevolucoesController.class) {
            return new DevolucoesController(s.devolucoes(), s.clientes(), s.acesso(), comprovantePdf,
                    impressaoComprovante, sessao);
        }
        if (tipo == ClientesController.class) {
            return new ClientesController(s.clientes(), s.credito(), relatorioPdf, sessao);
        }
        if (tipo == ConfiguracoesController.class) {
            return new ConfiguracoesController(s.configuracoes(), backup, s.auditoria(), s.usuarios(), s.estoque(),
                    s.credito(), s.clientes(), s.contas(), geradorDemo, impressaoTermica, certificados, nfce,
                    nfceTerminal, clock,
                    sessao);
        }
        if (tipo == FinanceiroController.class) {
            return new FinanceiroController(s.contas(), s.recebiveis(), s.contasPagar(), s.fluxoCaixa(), sessao);
        }
        if (tipo == CargaBalancaController.class) {
            return new CargaBalancaController(s.cargaBalanca(), sessao);
        }
        if (tipo == FiscalController.class) {
            return new FiscalController(s.fiscal(), sessao);
        }
        if (tipo == NotasFiscaisController.class) {
            return new NotasFiscaisController(notasFiscais, sessao, comprovantePdf, impressaoComprovante, clock);
        }
        if (tipo == PerfisTributariosController.class) {
            return new PerfisTributariosController(s.fiscal(), sessao);
        }
        if (tipo == EtiquetasController.class) {
            return new EtiquetasController(s.etiquetas(), s.produtos(), s.categorias(), etiquetasPdf,
                    impressaoTermica, sessao);
        }
        if (tipo == PromocoesController.class) {
            return new PromocoesController(s.promocoes(), s.produtos(), sessao);
        }
        if (tipo == ImportacaoProdutosController.class) {
            return new ImportacaoProdutosController(s.importacao(), sessao);
        }
        if (tipo == PainelController.class) {
            return new PainelController(s.painel(), s.primeirosPassos(), notasFiscais, sessao);
        }
        if (tipo == RelatoriosController.class) {
            return new RelatoriosController(s.relatorios(), relatorioPdf, sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        throw new IllegalArgumentException("Controller não registrado no AppContext: " + tipo.getName());
    }

    public AppConfig config() {
        return config;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Clock clock() {
        return clock;
    }

    public Transacao transacao() {
        return s.transacao();
    }

    public AcessoService acessoService() {
        return s.acesso();
    }

    public PreferenciaService preferenciaService() {
        return s.preferencias();
    }

    public CertificadoService certificadoService() {
        return certificados;
    }

    public EmissaoNfceService emissaoNfceService() {
        return emissaoNfce;
    }

    public TransmissorNfce transmissorNfce() {
        return transmissorNfce;
    }

    /** O transmissor em segundo plano (F23.5): o App liga no login e desliga no log off. */
    public TransmissorAgendado transmissorAgendado() {
        return transmissorAgendado;
    }

    public NfceTerminalService nfceTerminalService() {
        return nfceTerminal;
    }

    public NotasFiscaisService notasFiscaisService() {
        return notasFiscais;
    }

    public NfceService nfceService() {
        return nfce;
    }

    @Override
    public void close() {
        transmissorAgendado.close();
        dataSource.close();
    }
}
