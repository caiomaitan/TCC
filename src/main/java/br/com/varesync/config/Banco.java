package br.com.varesync.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Conexão com o PostgreSQL: teste de conexão e criação do pool HikariCP. */
public final class Banco {

    private static final Logger log = LoggerFactory.getLogger(Banco.class);

    private static final String PREFIXO_URL = "jdbc:postgresql://";
    private static final int TIMEOUT_SEGUNDOS = 5;
    /** O driver recusa a conexão (08004) quando o servidor pede senha e nenhuma foi informada. */
    private static final String SEM_SENHA = "08004";

    private Banco() {
    }

    /**
     * Abre e fecha uma conexão para conferir URL, usuário e senha.
     *
     * @throws FalhaConexaoException com mensagem para o usuário
     */
    public static void testarConexao(String url, String usuario, String senha) {
        if (url == null || !url.startsWith(PREFIXO_URL)) {
            throw new FalhaConexaoException(
                    "Endereço inválido. Use o formato jdbc:postgresql://servidor:5432/banco.", null);
        }
        Properties props = propriedades(usuario, senha);
        try (Connection con = DriverManager.getConnection(url, props)) {
            log.info("Conexão com o banco testada com sucesso: {}", url);
        } catch (SQLException e) {
            log.warn("Falha ao conectar em {} com o usuário {} (SQLState {})", url, usuario, e.getSQLState(), e);
            boolean semSenha = senha == null || senha.isEmpty();
            String mensagem = semSenha && SEM_SENHA.equals(e.getSQLState())
                    ? "Informe a senha do banco."
                    : descreverFalha(e);
            throw new FalhaConexaoException(mensagem, e);
        }
    }

    public static void testarConexao(AppConfig config) {
        testarConexao(config.url(), config.usuario(), config.senha());
    }

    /** Pool de conexões da aplicação. Falha logo se o banco não responder. */
    public static HikariDataSource criarPool(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("varesync");
        hikari.setJdbcUrl(config.url());
        hikari.setUsername(config.usuario());
        hikari.setPassword(config.senha());
        hikari.setMaximumPoolSize(5);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(TIMEOUT_SEGUNDOS * 2_000L);
        hikari.addDataSourceProperty("ApplicationName", "VareSync " + config.terminalId());
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(TIMEOUT_SEGUNDOS));
        return new HikariDataSource(hikari);
    }

    /** Mensagem para o usuário conforme o SQLState da falha de conexão. */
    static String descreverFalha(SQLException e) {
        String estado = e.getSQLState() == null ? "" : e.getSQLState();
        return switch (estado) {
            case "28P01", "28000" -> "Usuário ou senha do banco inválidos.";
            case "3D000" -> "O banco de dados informado não existe. Confira o nome no fim do endereço.";
            case "08001", "08004", "08006" ->
                    "Não foi possível conectar ao servidor do banco. Verifique se o PostgreSQL está em execução "
                            + "e se o endereço está correto.";
            default -> "Não foi possível conectar ao banco. Os detalhes foram registrados no log.";
        };
    }

    private static Properties propriedades(String usuario, String senha) {
        Properties props = new Properties();
        props.setProperty("user", usuario == null ? "" : usuario);
        props.setProperty("password", senha == null ? "" : senha);
        props.setProperty("connectTimeout", String.valueOf(TIMEOUT_SEGUNDOS));
        props.setProperty("loginTimeout", String.valueOf(TIMEOUT_SEGUNDOS));
        return props;
    }
}
