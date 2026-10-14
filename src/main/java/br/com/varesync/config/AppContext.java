package br.com.varesync.config;

import br.com.varesync.controller.CaixaController;
import br.com.varesync.controller.CategoriasController;
import br.com.varesync.controller.ConfiguracoesController;
import br.com.varesync.controller.EstoqueController;
import br.com.varesync.controller.LoginController;
import br.com.varesync.controller.Navegacao;
import br.com.varesync.controller.PrimeiroAcessoController;
import br.com.varesync.controller.PrincipalController;
import br.com.varesync.controller.ProdutoController;
import br.com.varesync.controller.ProdutosController;
import br.com.varesync.controller.TrocaSenhaController;
import br.com.varesync.controller.UsuariosController;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.CaixaDAO;
import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.UnidadeMedidaDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.AuditoriaService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.CategoriaService;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.ProdutoService;
import br.com.varesync.service.Senhas;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.Transacao;
import br.com.varesync.service.UsuarioService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;

/**
 * Composition root: cria DataSource, Clock, DAOs e services (injeção pelo construtor)
 * e entrega os controllers ao {@code FXMLLoader} por {@link #criarController(Class, Navegacao, Sessao)}.
 */
public final class AppContext implements AutoCloseable {

    private final AppConfig config;
    private final HikariDataSource dataSource;
    private final Clock clock;
    private final Transacao transacao;
    private final AcessoService acessoService;
    private final UsuarioService usuarioService;
    private final CategoriaService categoriaService;
    private final ProdutoService produtoService;
    private final EstoqueService estoqueService;
    private final CaixaService caixaService;

    AppContext(AppConfig config, HikariDataSource dataSource, Clock clock) {
        this.config = config;
        this.dataSource = dataSource;
        this.clock = clock;
        this.transacao = new Transacao(dataSource);

        UsuarioDAO usuarioDAO = new UsuarioDAO();
        ConfiguracaoDAO configuracaoDAO = new ConfiguracaoDAO();
        Senhas senhas = new Senhas();
        AuditoriaService auditoriaService = new AuditoriaService(new AuditoriaDAO(), clock, config.terminalId());
        this.acessoService = new AcessoService(transacao, usuarioDAO, configuracaoDAO, auditoriaService, senhas,
                clock, config.terminalId());
        this.usuarioService = new UsuarioService(transacao, usuarioDAO, auditoriaService, senhas, clock);

        ProdutoDAO produtoDAO = new ProdutoDAO();
        CategoriaDAO categoriaDAO = new CategoriaDAO();
        this.estoqueService = new EstoqueService(transacao, produtoDAO, new EstoqueDAO(), new MovimentoEstoqueDAO(),
                auditoriaService, clock);
        this.categoriaService = new CategoriaService(transacao, categoriaDAO);
        this.produtoService = new ProdutoService(transacao, produtoDAO, categoriaDAO, new UnidadeMedidaDAO(),
                configuracaoDAO, estoqueService, auditoriaService, clock);
        this.caixaService = new CaixaService(transacao, new CaixaDAO(), auditoriaService, clock);
    }

    /** Abre o pool e aplica as migrations pendentes. */
    public static AppContext iniciar(AppConfig config) {
        HikariDataSource dataSource = Banco.criarPool(config);
        try {
            new Migracao(dataSource).migrar();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new AppContext(config, dataSource, Clock.systemDefaultZone());
    }

    /**
     * Fábrica de controllers para {@code FXMLLoader.setControllerFactory}.
     *
     * @param sessao usuário logado; nulo nas telas de primeiro acesso e login
     */
    public Object criarController(Class<?> tipo, Navegacao navegacao, Sessao sessao) {
        if (tipo == PrimeiroAcessoController.class) {
            return new PrimeiroAcessoController(acessoService, navegacao);
        }
        if (tipo == LoginController.class) {
            return new LoginController(acessoService, navegacao);
        }
        if (tipo == TrocaSenhaController.class) {
            return new TrocaSenhaController(acessoService, navegacao, sessao);
        }
        if (tipo == PrincipalController.class) {
            return new PrincipalController(config, clock, acessoService, estoqueService, caixaService, navegacao,
                    sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        if (tipo == UsuariosController.class) {
            return new UsuariosController(usuarioService, sessao, clock);
        }
        if (tipo == ProdutosController.class) {
            return new ProdutosController(produtoService, categoriaService, sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        if (tipo == ProdutoController.class) {
            return new ProdutoController(produtoService, categoriaService, sessao);
        }
        if (tipo == CategoriasController.class) {
            return new CategoriasController(categoriaService, sessao);
        }
        if (tipo == EstoqueController.class) {
            return new EstoqueController(estoqueService, sessao);
        }
        if (tipo == CaixaController.class) {
            return new CaixaController(caixaService, acessoService, sessao);
        }
        if (tipo == ConfiguracoesController.class) {
            return new ConfiguracoesController(estoqueService, sessao);
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
        return transacao;
    }

    public AcessoService acessoService() {
        return acessoService;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
