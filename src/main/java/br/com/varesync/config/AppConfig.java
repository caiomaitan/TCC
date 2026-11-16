package br.com.varesync.config;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Configuração local da máquina, em {@code ~/.varesync/config.properties} (fora do repositório).
 * O arquivo é criado com os valores padrão na primeira execução.
 *
 * <p>F23.2: o certificado A1 da NFC-e é do terminal e fica só aqui ({@code nfce.certificado} e
 * {@code nfce.certificado.senha}), como a senha do banco: nunca no banco, no repositório, no log ou em mensagem. A
 * variável de ambiente {@code VARESYNC_CERT_SENHA}, se existir, vale no lugar da senha do arquivo.
 */
public final class AppConfig {

    public static final String URL_PADRAO = "jdbc:postgresql://localhost:5432/varesync";
    public static final String USUARIO_PADRAO = "varesync";
    public static final String TERMINAL_PADRAO = "CAIXA-01";
    /** Variável de ambiente com a senha do certificado (vale no lugar da do arquivo). */
    public static final String VARIAVEL_SENHA_CERTIFICADO = "VARESYNC_CERT_SENHA";

    static final String CHAVE_URL = "db.url";
    static final String CHAVE_USUARIO = "db.usuario";
    static final String CHAVE_SENHA = "db.senha";
    static final String CHAVE_TERMINAL = "terminal.id";
    static final String CHAVE_CERTIFICADO = "nfce.certificado";
    static final String CHAVE_SENHA_CERTIFICADO = "nfce.certificado.senha";

    private final Path arquivo;
    private final String url;
    private final String usuario;
    private final String senha;
    private final String terminalId;
    private final String certificado;
    private final String senhaCertificado;

    public AppConfig(Path arquivo, String url, String usuario, String senha, String terminalId) {
        this(arquivo, url, usuario, senha, terminalId, "", "");
    }

    public AppConfig(Path arquivo, String url, String usuario, String senha, String terminalId, String certificado,
                     String senhaCertificado) {
        this.arquivo = arquivo;
        this.url = valorOuPadrao(url, URL_PADRAO);
        this.usuario = valorOuPadrao(usuario, USUARIO_PADRAO);
        this.senha = senha == null ? "" : senha;
        this.terminalId = valorOuPadrao(terminalId, TERMINAL_PADRAO);
        this.certificado = certificado == null ? "" : certificado.strip();
        this.senhaCertificado = senhaCertificado == null ? "" : senhaCertificado;
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
                props.getProperty(CHAVE_TERMINAL),
                props.getProperty(CHAVE_CERTIFICADO),
                props.getProperty(CHAVE_SENHA_CERTIFICADO));
    }

    public void salvar() {
        Properties props = new Properties();
        props.setProperty(CHAVE_URL, url);
        props.setProperty(CHAVE_USUARIO, usuario);
        props.setProperty(CHAVE_SENHA, senha);
        props.setProperty(CHAVE_TERMINAL, terminalId);
        if (!certificado.isEmpty() || !senhaCertificado.isEmpty()) {
            props.setProperty(CHAVE_CERTIFICADO, certificado);
            props.setProperty(CHAVE_SENHA_CERTIFICADO, senhaCertificado);
        }
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

    /** Cópia com novos dados de conexão (o terminal e o certificado são mantidos). */
    public AppConfig comConexao(String novaUrl, String novoUsuario, String novaSenha) {
        return new AppConfig(arquivo, novaUrl, novoUsuario, novaSenha, terminalId, certificado, senhaCertificado);
    }

    /** Cópia com outro certificado A1 deste terminal (F23.2); caminho vazio tira o certificado. */
    public AppConfig comCertificado(String novoCaminho, String novaSenha) {
        boolean semCertificado = novoCaminho == null || novoCaminho.isBlank();
        return new AppConfig(arquivo, url, usuario, senha, terminalId, semCertificado ? "" : novoCaminho,
                semCertificado ? "" : novaSenha);
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

    /** Caminho do arquivo .pfx ou .p12 deste terminal; vazio = sem certificado. */
    public String certificado() {
        return certificado;
    }

    /** Senha do certificado gravada no arquivo (sem a variável de ambiente). */
    public String senhaCertificadoDoArquivo() {
        return senhaCertificado;
    }

    /** Senha que vale: a de {@code VARESYNC_CERT_SENHA}, se a variável existir, ou a do arquivo. */
    public String senhaCertificado() {
        return senhaCertificado(System.getenv());
    }

    String senhaCertificado(Map<String, String> ambiente) {
        String daVariavel = ambiente.get(VARIAVEL_SENHA_CERTIFICADO);
        return daVariavel != null ? daVariavel : senhaCertificado;
    }

    /** Se a senha do certificado vem da variável de ambiente (a tela não pede a senha, então). */
    public static boolean senhaCertificadoNaVariavel() {
        return System.getenv(VARIAVEL_SENHA_CERTIFICADO) != null;
    }

    /** Nunca inclui as senhas nem o caminho do certificado: este texto pode ir para o log. */
    @Override
    public String toString() {
        return "AppConfig[url=" + url + ", usuario=" + usuario + ", terminal=" + terminalId + ", certificado="
                + (certificado.isEmpty() ? "não" : "sim") + "]";
    }

    private static String valorOuPadrao(String valor, String padrao) {
        return valor == null || valor.isBlank() ? padrao : valor.strip();
    }
}
