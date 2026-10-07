package br.com.varesync.config;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuração local da máquina, em {@code ~/.varesync/config.properties} (fora do repositório).
 * O arquivo é criado com os valores padrão na primeira execução.
 */
public final class AppConfig {

    public static final String URL_PADRAO = "jdbc:postgresql://localhost:5432/varesync";
    public static final String USUARIO_PADRAO = "varesync";
    public static final String TERMINAL_PADRAO = "CAIXA-01";

    static final String CHAVE_URL = "db.url";
    static final String CHAVE_USUARIO = "db.usuario";
    static final String CHAVE_SENHA = "db.senha";
    static final String CHAVE_TERMINAL = "terminal.id";

    private final Path arquivo;
    private final String url;
    private final String usuario;
    private final String senha;
    private final String terminalId;

    public AppConfig(Path arquivo, String url, String usuario, String senha, String terminalId) {
        this.arquivo = arquivo;
        this.url = valorOuPadrao(url, URL_PADRAO);
        this.usuario = valorOuPadrao(usuario, USUARIO_PADRAO);
        this.senha = senha == null ? "" : senha;
        this.terminalId = valorOuPadrao(terminalId, TERMINAL_PADRAO);
    }

    /** {@code ~/.varesync} — pasta de configuração e de logs. */
    public static Path pastaPadrao() {
        return Path.of(System.getProperty("user.home"), ".varesync");
    }

    public static Path arquivoPadrao() {
        return pastaPadrao().resolve("config.properties");
    }

    /** Lê o arquivo; se ele não existir, cria com os valores padrão. */
    public static AppConfig carregar(Path arquivo) {
        if (Files.notExists(arquivo)) {
            AppConfig padrao = new AppConfig(arquivo, URL_PADRAO, USUARIO_PADRAO, "", TERMINAL_PADRAO);
            padrao.salvar();
            return padrao;
        }
        Properties props = new Properties();
        try (Reader leitor = Files.newBufferedReader(arquivo, StandardCharsets.UTF_8)) {
            props.load(leitor);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler " + arquivo, e);
        }
        return new AppConfig(arquivo,
                props.getProperty(CHAVE_URL),
                props.getProperty(CHAVE_USUARIO),
                props.getProperty(CHAVE_SENHA),
                props.getProperty(CHAVE_TERMINAL));
    }

    public void salvar() {
        Properties props = new Properties();
        props.setProperty(CHAVE_URL, url);
        props.setProperty(CHAVE_USUARIO, usuario);
        props.setProperty(CHAVE_SENHA, senha);
        props.setProperty(CHAVE_TERMINAL, terminalId);
        try {
            Path pasta = arquivo.toAbsolutePath().getParent();
            if (pasta != null) {
                Files.createDirectories(pasta);
            }
            try (Writer escritor = Files.newBufferedWriter(arquivo, StandardCharsets.UTF_8)) {
                props.store(escritor, "VareSync - configuração local (terminal.id deve ser único por computador)");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar " + arquivo, e);
        }
    }

    /** Cópia com novos dados de conexão (o terminal é mantido). */
    public AppConfig comConexao(String novaUrl, String novoUsuario, String novaSenha) {
        return new AppConfig(arquivo, novaUrl, novoUsuario, novaSenha, terminalId);
    }

    public Path arquivo() {
        return arquivo;
    }

    public String url() {
        return url;
    }

    public String usuario() {
        return usuario;
    }

    public String senha() {
        return senha;
    }

    public String terminalId() {
        return terminalId;
    }

    /** Nunca inclui a senha: este texto pode ir para o log. */
    @Override
    public String toString() {
        return "AppConfig[url=" + url + ", usuario=" + usuario + ", terminal=" + terminalId + "]";
    }

    private static String valorOuPadrao(String valor, String padrao) {
        return valor == null || valor.isBlank() ? padrao : valor.strip();
    }
}
