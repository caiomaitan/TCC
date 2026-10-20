package br.com.varesync.config;

import br.com.varesync.controller.CaixaController;
import br.com.varesync.controller.CategoriasController;
import br.com.varesync.controller.ConfiguracoesController;
import br.com.varesync.controller.EstoqueController;
import br.com.varesync.controller.InventarioController;
import br.com.varesync.controller.LoginController;
import br.com.varesync.controller.Navegacao;
import br.com.varesync.controller.PainelController;
import br.com.varesync.controller.PdvController;
import br.com.varesync.controller.PrimeiroAcessoController;
import br.com.varesync.controller.PrincipalController;
import br.com.varesync.controller.ProdutoController;
import br.com.varesync.controller.ProdutosController;
import br.com.varesync.controller.RelatoriosController;
import br.com.varesync.controller.TrocaSenhaController;
import br.com.varesync.controller.UsuariosController;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.FechamentoCaixaPdf;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.GeradorDemo;
import br.com.varesync.service.Senhas;
import br.com.varesync.service.Sessao;
import br.com.varesync.service.Transacao;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;

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
    private final ComprovantePdf comprovantePdf = new ComprovantePdf();
    private final RelatorioPdf relatorioPdf = new RelatorioPdf();
    private final FechamentoCaixaPdf fechamentoPdf = new FechamentoCaixaPdf();

    AppContext(AppConfig config, HikariDataSource dataSource, Clock clock) {
        this.config = config;
        this.dataSource = dataSource;
        this.clock = clock;
        Senhas senhas = new Senhas();
        this.s = Servicos.montar(dataSource, clock, config.terminalId(), senhas);
        this.geradorDemo = Servicos.geradorDemo(dataSource, clock, config.terminalId(), senhas);
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
            return new PrimeiroAcessoController(s.acesso(), navegacao);
        }
        if (tipo == LoginController.class) {
            return new LoginController(s.acesso(), navegacao);
        }
        if (tipo == TrocaSenhaController.class) {
            return new TrocaSenhaController(s.acesso(), navegacao, sessao);
        }
        if (tipo == PrincipalController.class) {
            return new PrincipalController(config, clock, s.acesso(), s.caixa(), fechamentoPdf, navegacao, sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        if (tipo == UsuariosController.class) {
            return new UsuariosController(s.usuarios(), sessao, clock);
        }
        if (tipo == ProdutosController.class) {
            return new ProdutosController(s.produtos(), s.categorias(), sessao,
                    t -> criarController(t, navegacao, sessao));
        }
        if (tipo == ProdutoController.class) {
            return new ProdutoController(s.produtos(), s.categorias(), sessao);
        }
        if (tipo == CategoriasController.class) {
            return new CategoriasController(s.categorias(), sessao);
        }
        if (tipo == EstoqueController.class) {
            return new EstoqueController(s.estoque(), sessao);
        }
        if (tipo == InventarioController.class) {
            return new InventarioController(s.inventarios(), s.acesso(), relatorioPdf, sessao);
        }
        if (tipo == CaixaController.class) {
            return new CaixaController(s.caixa(), s.acesso(), fechamentoPdf, sessao);
        }
        if (tipo == PdvController.class) {
            return new PdvController(s.vendas(), s.caixa(), s.acesso(), comprovantePdf, fechamentoPdf, sessao);
        }
        if (tipo == ConfiguracoesController.class) {
            return new ConfiguracoesController(s.estoque(), geradorDemo, sessao);
        }
        if (tipo == PainelController.class) {
            return new PainelController(s.painel(), sessao);
        }
        if (tipo == RelatoriosController.class) {
            return new RelatoriosController(s.relatorios(), relatorioPdf, sessao);
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

    @Override
    public void close() {
        dataSource.close();
    }
}
