package br.com.varesync.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.com.varesync.util.Contraste;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contraste AA dos dois temas (F21.2), calculado sobre as variáveis da folha do sistema: 4,5:1 no texto normal e 3:1
 * no texto grande e no contorno de foco. E nenhuma cor literal fora dos blocos de variáveis, para que o tema escuro
 * troque mesmo todas as cores.
 */
class ContrasteTest {

    private static final Pattern COMENTARIO = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern REGRA = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern VARIAVEL = Pattern.compile("(-vs-[a-z-]+)\\s*:\\s*([^;]+);");

    /** Texto normal (4,5:1): {texto, fundo}. */
    private static final String[][] TEXTO = {
            {"-vs-texto", "-vs-fundo"}, {"-vs-texto", "-vs-superficie"},
            {"-vs-texto-suave", "-vs-fundo"}, {"-vs-texto-suave", "-vs-superficie"},
            {"-vs-erro", "-vs-fundo"}, {"-vs-erro", "-vs-superficie"},
            {"-vs-sucesso", "-vs-fundo"}, {"-vs-sucesso", "-vs-superficie"},
            {"-vs-alerta", "-vs-fundo"}, {"-vs-alerta", "-vs-superficie"},
            {"-vs-texto", "-vs-linha-alerta"}, {"-vs-texto", "-vs-linha-erro"}, {"-vs-texto", "-vs-entrada-erro"},
            {"-vs-texto-sobre-destaque", "-vs-destaque"}, {"-vs-texto-sobre-destaque", "-vs-destaque-escuro"},
            {"-vs-menu-texto", "-vs-menu-fundo"}, {"-vs-menu-texto", "-vs-menu-hover"},
            {"-vs-menu-texto-forte", "-vs-menu-fundo"}, {"-vs-menu-loja", "-vs-menu-fundo"},
            {"-vs-pdv-texto", "-vs-pdv-escuro"}, {"-vs-pdv-texto-suave", "-vs-pdv-escuro"},
            {"-vs-pdv-cliente", "-vs-pdv-escuro"}, {"-vs-pdv-cliente-negativo", "-vs-pdv-escuro"},
            {"-vs-pdv-erro", "-vs-pdv-escuro"}, {"-vs-pdv-sucesso", "-vs-pdv-escuro"},
            {"-vs-pdv-destaque", "-vs-pdv-escuro"}, {"-vs-menu-texto", "-vs-pdv-escuro"},
            {"-vs-papel-texto", "-vs-papel"}, {"-vs-texto-dica", "-vs-superficie"}};

    /** Texto grande (3:1): total do PDV, troco e títulos. */
    private static final String[][] TEXTO_GRANDE = {
            {"-vs-pdv-destaque", "-vs-pdv-escuro"}, {"-vs-sucesso", "-vs-superficie"}, {"-vs-sucesso", "-vs-fundo"},
            {"-vs-texto", "-vs-fundo"}};

    /** Contorno de foco (3:1) sobre os fundos em que aparece. */
    private static final String[][] CONTORNO = {
            {"-vs-foco", "-vs-fundo"}, {"-vs-foco", "-vs-superficie"}};

    private static String css;
    private static Map<String, String> claro;
    private static Map<String, String> escuro;

    @BeforeAll
    static void lerFolha() throws Exception {
        try (InputStream entrada = ContrasteTest.class.getResourceAsStream(Dialogos.CSS)) {
            assertThat(entrada).isNotNull();
            css = COMENTARIO.matcher(new String(entrada.readAllBytes(), StandardCharsets.UTF_8)).replaceAll("");
        }
        claro = variaveis(".root");
        escuro = new LinkedHashMap<>(claro);
        escuro.putAll(variaveis(".root.tema-escuro"));
    }

    @Test
    void formulaDasWcag() {
        assertThat(Contraste.razao("#000000", "#ffffff")).isCloseTo(21.0, within(0.001));
        assertThat(Contraste.razao("#ffffff", "#ffffff")).isCloseTo(1.0, within(0.001));
        assertThat(Contraste.razao("#777777", "#ffffff")).isCloseTo(4.48, within(0.01)); // o cinza que reprova
        assertThat(Contraste.razao("#fff", "#000")).isCloseTo(21.0, within(0.001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"claro", "escuro"})
    void textoNormalTem45(String tema) {
        assertThat(reprovados(tema, TEXTO, Contraste.AA_TEXTO)).as("pares abaixo de 4,5:1 no tema %s", tema).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claro", "escuro"})
    void textoGrandeTem3(String tema) {
        assertThat(reprovados(tema, TEXTO_GRANDE, Contraste.AA_TEXTO_GRANDE))
                .as("pares abaixo de 3:1 no tema %s", tema).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claro", "escuro"})
    void contornoDeFocoTem3(String tema) {
        assertThat(reprovados(tema, CONTORNO, Contraste.AA_CONTORNO))
                .as("contorno de foco abaixo de 3:1 no tema %s", tema).isEmpty();
    }

    @Test
    void temaEscuroTrocaAsCoresDeFundoETexto() {
        for (String variavel : List.of("-vs-fundo", "-vs-superficie", "-vs-texto", "-vs-texto-suave")) {
            assertThat(escuro.get(variavel)).as(variavel).isNotEqualTo(claro.get(variavel));
        }
        assertThat(Contraste.luminancia(escuro.get("-vs-fundo"))).isLessThan(0.05);
        assertThat(escuro.get("-vs-papel")).as("o comprovante continua claro, como no papel")
                .isEqualTo(claro.get("-vs-papel"));
    }

    @Test
    void nenhumaCorLiteralForaDosBlocosDeVariaveis() {
        Pattern literal = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\(|hsba?\\(|:\\s*(white|black|red|blue|green|gray|"
                + "grey|yellow|orange)\\s*;");
        List<String> achados = new ArrayList<>();
        Matcher regra = REGRA.matcher(css);
        while (regra.find()) {
            String seletor = regra.group(1).trim();
            if (seletor.equals(".root") || seletor.equals(".root.tema-escuro")) {
                continue;
            }
            Matcher m = literal.matcher(regra.group(2));
            while (m.find()) {
                achados.add(seletor + " → " + m.group());
            }
        }
        assertThat(achados).isEmpty();
    }

    @Test
    void todaVariavelUsadaEstaDefinidaNosDoisTemas() {
        Matcher uso = Pattern.compile("(-vs-[a-z-]+)").matcher(css);
        List<String> faltando = new ArrayList<>();
        while (uso.find()) {
            String nome = uso.group(1);
            if (!claro.containsKey(nome) || !escuro.containsKey(nome)) {
                faltando.add(nome);
            }
        }
        assertThat(faltando).isEmpty();
    }

    private static List<String> reprovados(String tema, String[][] pares, double minimo) {
        Map<String, String> cores = tema.equals("claro") ? claro : escuro;
        List<String> abaixo = new ArrayList<>();
        for (String[] par : pares) {
            String frente = resolver(cores, par[0]);
            String fundo = resolver(cores, par[1]);
            double razao = Contraste.razao(frente, fundo);
            if (razao < minimo) {
                abaixo.add(String.format("%s %s sobre %s %s = %.2f:1", par[0], frente, par[1], fundo, razao));
            }
        }
        return abaixo;
    }

    /** Valor final da variável (uma variável pode apontar para outra). */
    private static String resolver(Map<String, String> cores, String nome) {
        String valor = cores.get(nome);
        assertThat(valor).as("variável %s", nome).isNotNull();
        return valor.startsWith("-vs-") ? resolver(cores, valor) : valor;
    }

    private static Map<String, String> variaveis(String seletor) {
        Map<String, String> mapa = new LinkedHashMap<>();
        Matcher regra = REGRA.matcher(css);
        while (regra.find()) {
            if (regra.group(1).trim().equals(seletor)) {
                Matcher v = VARIAVEL.matcher(regra.group(2));
                while (v.find()) {
                    mapa.put(v.group(1), v.group(2).trim());
                }
            }
        }
        assertThat(mapa).as("variáveis de %s", seletor).isNotEmpty();
        return mapa;
    }
}
