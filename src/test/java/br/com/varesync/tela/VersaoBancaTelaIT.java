package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.BancoTeste;
import br.com.varesync.config.AppConfig;
import br.com.varesync.config.VersaoSistema;
import br.com.varesync.service.DadosTela;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

/**
 * Versão 2.0.0: a proteção da banca e a tela Sobre.
 *
 * <ul>
 *   <li>Com a configuração apontando para o {@code varesync_demo} (o banco da banca do TCC), a tela de conexão explica
 *       e pede outro banco, na abertura e no "Testar conexão". O endereço é o {@link #URL_BANCA_SEM_SERVIDOR}, um
 *       servidor que não existe: se a recusa falhasse, a mensagem seria a de servidor fora do ar, e nenhum banco de
 *       verdade seria aberto.</li>
 *   <li>Trocado o endereço pelo do banco de teste, o sistema abre, e a tela Sobre (pelo link da barra de status)
 *       mostra a versão do build.</li>
 * </ul>
 */
class VersaoBancaTelaIT extends TelaBase {

    @Test
    void bancoDaBancaRecusadoESobreComAVersao() throws Exception {
        dados.criarSupervisor();
        BancoTeste.Credenciais cr = BancoTeste.credenciais();
        escreverConfiguracao(URL_BANCA_SEM_SERVIDOR);
        abrirSistema();

        // ----- Abertura com o banco da banca: a tela de conexão explica -----
        Scene conexao = esperarCena(s -> s.getRoot().lookup("#campoUrl") != null, "tela de conexão");
        Parent c = conexao.getRoot();
        esperar(() -> !mensagem(c).isEmpty(), "motivo da recusa");
        assertThat(mensagem(c)).contains("varesync_demo", "TCC", "Informe outro banco", "varesync_amostra")
                .doesNotContain("Não foi possível conectar ao servidor");
        Thread.sleep(300);
        capturar(conexao, "abertura_com_o_banco_da_banca");

        // ----- "Testar conexão" com o nome em maiúsculas: também recusa -----
        fx(() -> {
            ((TextField) c.lookup("#campoUrl")).setText("jdbc:postgresql://127.0.0.1:1/VARESYNC_DEMO");
            ((Label) c.lookup("#mensagem")).setText("");
            botao(c, "Testar conexão").fire();
            return null;
        });
        esperar(() -> mensagem(c).contains("varesync_demo"), "Testar conexão recusado");
        assertThat(mensagem(c)).doesNotContain("Não foi possível conectar ao servidor");
        capturar(conexao, "testar_conexao_banco_da_banca");

        // ----- "Salvar" também recusa e não grava -----
        fx(() -> {
            ((Label) c.lookup("#mensagem")).setText("");
            botao(c, "Salvar").fire();
            return null;
        });
        esperar(() -> mensagem(c).contains("varesync_demo"), "Salvar recusado");
        assertThat(AppConfig.carregar(AppConfig.arquivoPadrao()).url()).as("nada foi gravado")
                .isEqualTo(URL_BANCA_SEM_SERVIDOR);

        // ----- Outro banco: o de teste -----
        fx(() -> {
            ((TextField) c.lookup("#campoUrl")).setText(cr.url());
            ((TextField) c.lookup("#campoUsuario")).setText(cr.usuario());
            ((TextField) c.lookup("#campoSenha")).setText(cr.senha());
            return null;
        });
        conferirTravaDeUmEndereco(cr.url());
        noFx(() -> botao(c, "Salvar").fire());
        Scene login = esperarLogin();
        conferirTravaDaUrl(); // o que a tela salvou aponta para o banco de teste
        Scene principal = entrar(login, DadosTela.LOGIN_SUPERVISOR, DadosTela.SENHA_SUPERVISOR);

        // ----- Tela Sobre -----
        String versao = VersaoSistema.doBuild().versao();
        assertThat(versao).isNotEqualTo(VersaoSistema.DESCONHECIDO);
        Hyperlink link = fx(() -> (Hyperlink) principal.getRoot().lookup("#statusVersao"));
        assertThat(fx(link::getText)).isEqualTo("v" + versao);
        Platform.runLater(link::fire); // showAndWait: fora desta chamada
        DialogPane sobre = esperarDialogo("VareSync — ERP desktop");
        Thread.sleep(300);
        assertThat(fx(() -> ((Label) sobre.lookup("#sobre-versao")).getText())).isEqualTo(versao);
        System.out.println("Tela Sobre: versão " + versao);
        capturar(sobre.getScene(), "sobre");
        capturar(principal, "barra_de_status_com_a_versao");
        confirmarDialogo(sobre);
    }

    /** O endereço que a tela vai salvar tem de ser o do banco de teste. */
    private static void conferirTravaDeUmEndereco(String url) {
        if (!url.endsWith("/varesync_test")) {
            throw new IllegalStateException("O banco de teste não é o varesync_test; nada foi salvo: " + url);
        }
    }

    private static String mensagem(Parent raiz) {
        return fxr(() -> {
            String texto = ((Label) raiz.lookup("#mensagem")).getText();
            return texto == null ? "" : texto;
        });
    }
}
