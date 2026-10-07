package br.com.varesync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Confere, sem subir o JavaFX, que cada FXML declara o controller (exigido pela controllerFactory)
 * e que todo {@code fx:id} e {@code onAction="#..."} existe no controller.
 */
class FxmlTest {

    private static final Pattern FX_ID = Pattern.compile("fx:id=\"(\\w+)\"");
    private static final Pattern ACAO = Pattern.compile("on\\w+=\"#(\\w+)\"");

    @ParameterizedTest
    @CsvSource({
            "/fxml/conexao.fxml, br.com.varesync.controller.ConexaoController",
            "/fxml/principal.fxml, br.com.varesync.controller.PrincipalController"
    })
    void fxmlCasaComOController(String recurso, String controller) throws Exception {
        String fxml;
        try (InputStream in = FxmlTest.class.getResourceAsStream(recurso)) {
            assertThat(in).as(recurso).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(fxml).contains("fx:controller=\"" + controller + "\"");

        Class<?> tipo = Class.forName(controller, false, FxmlTest.class.getClassLoader());
        Set<String> campos = Arrays.stream(tipo.getDeclaredFields()).map(f -> f.getName()).collect(Collectors.toSet());
        Set<String> metodos = Arrays.stream(tipo.getDeclaredMethods()).map(m -> m.getName()).collect(Collectors.toSet());

        Matcher ids = FX_ID.matcher(fxml);
        while (ids.find()) {
            String id = ids.group(1);
            if (!id.equals("conteudo")) { // área de conteúdo: usada pela navegação a partir da Fase 1
                assertThat(campos).as("campo @FXML %s em %s", id, controller).contains(id);
            }
        }
        Matcher acoes = ACAO.matcher(fxml);
        while (acoes.find()) {
            assertThat(metodos).as("método %s em %s", acoes.group(1), controller).contains(acoes.group(1));
        }
    }
}
