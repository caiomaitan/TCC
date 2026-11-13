package br.com.varesync.config;

import br.com.varesync.util.Formatos;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;

/**
 * Versão do sistema (F11.6): a do pom, o commit do build e a data do build. Vem de dois arquivos que o Maven grava em
 * {@code target/classes}: {@code versao.properties} (recurso filtrado) e {@code git.properties} (plugin git-commit-id).
 * Sem Git no build, ou rodando sem o Maven ter processado os recursos, o que falta aparece como "desconhecido".
 *
 * @param versao   a versão do pom, como {@code 2.0.0-SNAPSHOT}
 * @param commit   os primeiros caracteres do commit, ou vazio
 * @param alterado o build tinha alterações ainda não commitadas
 * @param dataBuild quando o build foi feito, ou vazio
 */
public record VersaoSistema(String versao, Optional<String> commit, boolean alterado, Optional<Instant> dataBuild) {

    public static final String DESCONHECIDO = "desconhecido";

    /** A versão deste build, lida dos recursos do classpath. */
    public static VersaoSistema doBuild() {
        return de(ler("/versao.properties"), ler("/git.properties"));
    }

    /** Monta a versão a partir das propriedades lidas; um valor vazio ou ainda com {@code ${...}} conta como ausente. */
    static VersaoSistema de(Properties versao, Properties git) {
        String numero = valor(versao, "versao").orElse(DESCONHECIDO);
        Optional<String> commit = valor(git, "git.commit.id.abbrev");
        boolean alterado = valor(git, "git.dirty").map(Boolean::parseBoolean).orElse(false);
        Optional<Instant> data = valor(versao, "build.data").flatMap(VersaoSistema::instante);
        return new VersaoSistema(numero, commit, alterado, data);
    }

    /** "2.0.0-SNAPSHOT" → "2.0.0": a versão que o {@code jpackage} aceita. */
    public String versaoNumerica() {
        int traco = versao.indexOf('-');
        return traco >= 0 ? versao.substring(0, traco) : versao;
    }

    /** Commit para a tela: "a1b2c3d", "a1b2c3d (com alterações não commitadas)" ou "desconhecido". */
    public String commitTexto() {
        return commit.map(c -> alterado ? c + " (com alterações não commitadas)" : c).orElse(DESCONHECIDO);
    }

    /** Data do build no fuso do computador ({@code dd/MM/yyyy HH:mm}), ou "desconhecida". */
    public String dataBuildTexto() {
        return dataBuild.map(d -> Formatos.dataHora(OffsetDateTime.ofInstant(d, ZoneId.systemDefault())))
                .orElse("desconhecida");
    }

    /**
     * Texto curto da barra de status: "v2.0.0-SNAPSHOT". Curto porque, na fonte de 130% em 1280×720, a barra não tem
     * lugar para "Versão …" sem passar da borda; a dica ({@link #dicaBarraDeStatus()}) e a tela Sobre dizem por extenso.
     */
    public String textoBarraDeStatus() {
        return "v" + versao;
    }

    /** Dica da versão na barra de status: "Versão 2.0.0-SNAPSHOT — abre a tela Sobre". */
    public String dicaBarraDeStatus() {
        return "Versão " + versao + " — abre a tela Sobre";
    }

    private static Optional<String> valor(Properties propriedades, String chave) {
        String valor = propriedades.getProperty(chave);
        if (valor == null || valor.isBlank() || valor.contains("${")) {
            return Optional.empty();
        }
        return Optional.of(valor.trim());
    }

    private static Optional<Instant> instante(String texto) {
        try {
            return Optional.of(Instant.parse(texto));
        } catch (DateTimeParseException e) {
            return Optional.empty(); // data em formato estranho: a tela mostra "desconhecida"
        }
    }

    private static Properties ler(String recurso) {
        Properties propriedades = new Properties();
        try (InputStream entrada = VersaoSistema.class.getResourceAsStream(recurso)) {
            if (entrada != null) {
                propriedades.load(new InputStreamReader(entrada, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler " + recurso, e);
        }
        return propriedades;
    }
}
