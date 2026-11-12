package br.com.varesync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.util.MarkdownSimples;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Ajuda contextual (F1, F21.3): toda tela do menu tem o seu texto em {@code resources/ajuda}, e as telas do menu só
 * tratam teclas pelo registro de atalhos ({@code ui.Atalhos}), de onde sai a lista da ajuda.
 */
class AjudaTest {

    private static final Pattern CONTROLLER = Pattern.compile("fx:controller=\"([\\w.]+)\"");
    private static final Pattern INCLUDE = Pattern.compile("<fx:include[^>]*source=\"([\\w.-]+)\"");
    /** Tratamento de tecla fora do registro: a tecla funcionaria e não sairia na lista da ajuda. */
    private static final List<String> TECLADO_FORA_DO_REGISTRO = List.of("getCode()", ".match(e", "KeyEvent.KEY_",
            "setOnKeyPressed", "setOnKeyReleased", "setOnKeyTyped", "KeyCodeCombination");

    private static String recurso(String caminho) throws IOException {
        try (InputStream in = AjudaTest.class.getResourceAsStream(caminho)) {
            assertThat(in).as(caminho).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void todaTelaDoMenuTemOSeuTexto() throws IOException {
        for (ItemMenu item : ItemMenu.values()) {
            List<MarkdownSimples.Bloco> blocos = MarkdownSimples.ler(recurso(item.ajuda()));
            assertThat(blocos).as(item.ajuda()).hasSizeGreaterThan(2);
            assertThat(blocos.get(0)).as("título de %s", item.ajuda()).isInstanceOf(MarkdownSimples.Titulo.class);
            MarkdownSimples.Titulo titulo = (MarkdownSimples.Titulo) blocos.get(0);
            assertThat(titulo.nivel()).isEqualTo(1);
            assertThat(MarkdownSimples.textoSimples(titulo.trechos())).as(item.ajuda()).isEqualTo(item.rotulo());
        }
    }

    @Test
    void naoSobraTextoDeTelaQueNaoExiste() throws IOException, URISyntaxException {
        Path pasta = Path.of(AjudaTest.class.getResource(ItemMenu.PAINEL.ajuda()).toURI()).getParent();
        Set<String> esperados = Arrays.stream(ItemMenu.values())
                .map(i -> Path.of(i.ajuda()).getFileName().toString()).collect(Collectors.toSet());
        try (Stream<Path> arquivos = Files.list(pasta)) {
            assertThat(arquivos.map(p -> p.getFileName().toString())).containsExactlyInAnyOrderElementsOf(esperados);
        }
    }

    /** Só a marcação que o painel desenha: nada de tabela, código em bloco, citação, link ou imagem. */
    @Test
    void textosUsamSoOMarkdownSimples() throws IOException {
        for (ItemMenu item : ItemMenu.values()) {
            String texto = recurso(item.ajuda());
            for (String linha : texto.split("\n")) {
                String l = linha.strip();
                assertThat(l).as("%s: %s", item.ajuda(), l)
                        .doesNotStartWith("|").doesNotStartWith("```").doesNotStartWith(">").doesNotStartWith("####")
                        .doesNotContain("](").doesNotContain("<");
                assertThat(l.chars().filter(c -> c == '`').count() % 2).as("crases em par: %s", l).isZero();
            }
        }
    }

    /**
     * F21.5: a barra de atalhos do PDV e as dicas de atalho nos rodapés saem do registro (rótulos com a classe
     * {@code dica-atalhos}, preenchidos por {@code Atalhos.preencherDicas}): nenhum rótulo de FXML traz tecla de
     * função ou Ctrl escrita à mão, e as telas com rodapé de atalhos têm o rótulo de dica.
     */
    @Test
    void rodapesDeAtalhosSaemDoRegistro() throws IOException, URISyntaxException {
        Pattern rotulo = Pattern.compile("<Label\\b[^>]*>", Pattern.DOTALL);
        Pattern tecla = Pattern.compile("text=\"[^\"]*\\b(F([1-9]|1[0-2])|Ctrl\\+[A-Z])\\b");
        Path pasta = Path.of(AjudaTest.class.getResource("/fxml/pdv.fxml").toURI()).getParent();
        try (Stream<Path> arquivos = Files.list(pasta)) {
            for (Path arquivo : arquivos.toList()) {
                String fxml = Files.readString(arquivo, StandardCharsets.UTF_8);
                Matcher m = rotulo.matcher(fxml);
                while (m.find()) {
                    assertThat(tecla.matcher(m.group()).find()).as("%s: %s", arquivo.getFileName(), m.group())
                            .isFalse();
                }
            }
        }
        for (String fxml : List.of("pdv", "estoque", "caixa", "compras", "inventario", "pedidos")) {
            assertThat(recurso("/fxml/" + fxml + ".fxml")).as(fxml).contains("dica-atalhos");
        }
        assertThat(Files.readString(Path.of("src/main/java/br/com/varesync/controller/PrincipalController.java"),
                StandardCharsets.UTF_8)).contains("Atalhos.preencherDicas(tela)");
    }

    /**
     * A lista de atalhos da ajuda sai do registro: nenhuma tela do menu (nem as partes incluídas nela, como a aba
     * Pedidos de Compras) trata tecla por fora dele.
     */
    @Test
    void telasDoMenuSoTratamTeclasPeloRegistroDeAtalhos() throws IOException {
        Set<String> controllers = new LinkedHashSet<>();
        List<String> fxmls = new ArrayList<>();
        fxmls.add("/fxml/painel.fxml");
        for (ItemMenu item : ItemMenu.values()) {
            if (item.fxml() != null) {
                fxmls.add(item.fxml());
            }
        }
        for (int i = 0; i < fxmls.size(); i++) {
            String fxml = recurso(fxmls.get(i));
            Matcher controller = CONTROLLER.matcher(fxml);
            if (controller.find()) {
                controllers.add(controller.group(1));
            }
            Matcher incluido = INCLUDE.matcher(fxml);
            while (incluido.find()) {
                fxmls.add("/fxml/" + incluido.group(1));
            }
        }
        controllers.add(PrincipalController.class.getName()); // F1 e Ctrl+K
        assertThat(controllers).contains(PdvController.class.getName(), PedidosController.class.getName(),
                EstoqueController.class.getName());

        for (String controller : controllers) {
            Path fonte = Path.of("src/main/java", controller.replace('.', '/') + ".java");
            String codigo = Files.readString(fonte, StandardCharsets.UTF_8);
            for (String proibido : TECLADO_FORA_DO_REGISTRO) {
                assertThat(codigo).as("%s trata tecla fora do registro de atalhos (%s)", controller, proibido)
                        .doesNotContain(proibido);
            }
        }
    }
}
