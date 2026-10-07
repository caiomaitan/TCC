package br.com.varesync.config;

import br.com.varesync.controller.PrincipalController;
import br.com.varesync.service.Transacao;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;

/**
 * Composition root: cria DataSource, Clock, DAOs e services (injeção pelo construtor)
 * e entrega os controllers ao {@code FXMLLoader} por {@link #criarController(Class)}.
 */
public final class AppContext implements AutoCloseable {

    private final AppConfig config;
    private final HikariDataSource dataSource;
    private final Clock clock;
    private final Transacao transacao;

    AppContext(AppConfig config, HikariDataSource dataSource, Clock clock) {
        this.config = config;
        this.dataSource = dataSource;
        this.clock = clock;
        this.transacao = new Transacao(dataSource);
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

    /** Fábrica de controllers para {@code FXMLLoader.setControllerFactory}. */
    public Object criarController(Class<?> tipo) {
        if (tipo == PrincipalController.class) {
            return new PrincipalController(config, clock);
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

    @Override
    public void close() {
        dataSource.close();
    }
}
