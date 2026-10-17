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
            "/fxml/principal.fxml, br.com.varesync.controller.PrincipalController",
            "/fxml/primeiro-acesso.fxml, br.com.varesync.controller.PrimeiroAcessoController",
            "/fxml/login.fxml, br.com.varesync.controller.LoginController",
            "/fxml/troca-senha.fxml, br.com.varesync.controller.TrocaSenhaController",
            "/fxml/usuarios.fxml, br.com.varesync.controller.UsuariosController",
            "/fxml/produtos.fxml, br.com.varesync.controller.ProdutosController",
            "/fxml/produto.fxml, br.com.varesync.controller.ProdutoController",
            "/fxml/categorias.fxml, br.com.varesync.controller.CategoriasController",
            "/fxml/estoque.fxml, br.com.varesync.controller.EstoqueController",
            "/fxml/inventario.fxml, br.com.varesync.controller.InventarioController",
            "/fxml/configuracoes.fxml, br.com.varesync.controller.ConfiguracoesController",
            "/fxml/caixa.fxml, br.com.varesync.controller.CaixaController",
            "/fxml/fechamento-caixa.fxml, br.com.varesync.controller.FechamentoCaixaController",
            "/fxml/pdv.fxml, br.com.varesync.controller.PdvController",
            "/fxml/pagamento.fxml, br.com.varesync.controller.PagamentoController",
            "/fxml/vendas-sessao.fxml, br.com.varesync.controller.VendasSessaoController",
            "/fxml/comprovante.fxml, br.com.varesync.controller.ComprovanteController"
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
            assertThat(campos).as("campo @FXML %s em %s", id, controller).contains(id);
        }
        Matcher acoes = ACAO.matcher(fxml);
        while (acoes.find()) {
            assertThat(metodos).as("método %s em %s", acoes.group(1), controller).contains(acoes.group(1));
        }
    }
}
