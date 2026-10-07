package br.com.varesync.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aplica as migrations de {@code src/main/resources/db/migration} com o Flyway.
 * O {@code clean} fica desabilitado (padrão do Flyway); só a configuração de teste o habilita.
 */
public final class Migracao {

    public static final String LOCAL_MIGRATIONS = "classpath:db/migration";

    private static final Logger log = LoggerFactory.getLogger(Migracao.class);

    private final DataSource dataSource;

    public Migracao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return quantidade de migrations aplicadas agora (0 se o banco já estava atualizado) */
    public int migrar() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCAL_MIGRATIONS)
                .load();
        MigrateResult resultado = flyway.migrate();
        log.info("Migrations aplicadas: {}; versão do banco: {}",
                resultado.migrationsExecuted, resultado.targetSchemaVersion);
        return resultado.migrationsExecuted;
    }
}
