package br.com.varesync;

import br.com.varesync.config.Migracao;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Banco de teste ({@code varesync_test}) compartilhado pela suíte de integração.
 * Na primeira chamada: {@code flyway.clean()} + {@code migrate()}. O clean só é habilitado aqui.
 *
 * <p>Ambiente: {@code VARESYNC_DB_SENHA} (obrigatória), {@code VARESYNC_DB_USUARIO} (padrão varesync)
 * e {@code VARESYNC_DB_TESTE_URL} (padrão jdbc:postgresql://localhost:5432/varesync_test).
 * Nenhuma senha fica em arquivo do repositório.
 */
public final class BancoTeste {

    public static final String URL_PADRAO = "jdbc:postgresql://localhost:5432/varesync_test";

    private static HikariDataSource dataSource;
    private static int migrationsNaPreparacao;

    private BancoTeste() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            dataSource = criar();
            limparEMigrar(dataSource);
            Runtime.getRuntime().addShutdownHook(new Thread(dataSource::close));
        }
        return dataSource;
    }

    /** Quantas migrations o {@code migrate()} aplicou no banco recém-limpo. */
    public static synchronized int migrationsNaPreparacao() {
        dataSource();
        return migrationsNaPreparacao;
    }

    public static long contar(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** URL, usuário e senha do banco de teste (do ambiente), para quem precisa conectar fora do pool (pg_dump). */
    public record Credenciais(String url, String usuario, String senha) {

        @Override
        public String toString() {
            return usuario + "@" + url;
        }
    }

    public static Credenciais credenciais() {
        return new Credenciais(valorOuPadrao(System.getenv("VARESYNC_DB_TESTE_URL"), URL_PADRAO),
                valorOuPadrao(System.getenv("VARESYNC_DB_USUARIO"), "varesync"), System.getenv("VARESYNC_DB_SENHA"));
    }

    private static HikariDataSource criar() {
        String senha = System.getenv("VARESYNC_DB_SENHA");
        if (senha == null || senha.isEmpty()) {
            throw new IllegalStateException("Testes de integração precisam da variável de ambiente VARESYNC_DB_SENHA "
                    + "com a senha do usuário do banco de teste (varesync_test). Defina-a e abra um terminal novo.");
        }
        String usuario = valorOuPadrao(System.getenv("VARESYNC_DB_USUARIO"), "varesync");
        String url = valorOuPadrao(System.getenv("VARESYNC_DB_TESTE_URL"), URL_PADRAO);

        HikariConfig config = new HikariConfig();
        config.setPoolName("varesync-teste");
        config.setJdbcUrl(url);
        config.setUsername(usuario);
        config.setPassword(senha);
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

    private static void limparEMigrar(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations(Migracao.LOCAL_MIGRATIONS)
                .cleanDisabled(false)
                .load()
                .clean();
        migrationsNaPreparacao = new Migracao(ds).migrar();
    }

    private static String valorOuPadrao(String valor, String padrao) {
        return valor == null || valor.isBlank() ? padrao : valor;
    }
}
