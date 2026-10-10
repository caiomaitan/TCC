package br.com.varesync.config;

import br.com.varesync.controller.LoginController;
import br.com.varesync.controller.Navegacao;
import br.com.varesync.controller.PrimeiroAcessoController;
import br.com.varesync.controller.PrincipalController;
import br.com.varesync.controller.TrocaSenhaController;
import br.com.varesync.controller.UsuariosController;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.AuditoriaService;
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
        this.usuarioService = new UsuarioService(transacao, usuarioDAO, auditoriaService, senhas);
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
            return new PrincipalController(config, clock, acessoService, navegacao, sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        if (tipo == UsuariosController.class) {
            return new UsuariosController(usuarioService, sessao, clock);
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
