package br.com.varesync.tela;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.BancoTeste;
import br.com.varesync.config.AppConfig;
import br.com.varesync.controller.ItemMenu;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

/**
 * Fluxo 1 (F11.3): tela de conexão com "Testar conexão" (F11.5), primeiro acesso e login, com o banco sem usuários.
 * O sistema começa com um endereço que não responde (porta 1), para cair na tela de conexão; a pessoa corrige o
 * endereço para o do banco de teste, testa e salva.
 */
class PrimeiroAcessoTelaIT extends TelaBase {

    @Test
    void conexaoPrimeiroAcessoELogin() throws Exception {
        BancoTeste.Credenciais cr = BancoTeste.credenciais();
        escreverConfiguracao(cr.url().replaceFirst("//[^/]+/", "//127.0.0.1:1/")); // servidor que não responde
        abrirSistema();

        // ----- Conexão com o banco -----
        Scene conexao = esperarCena(s -> s.getRoot().lookup("#campoUrl") != null, "tela de conexão");
        Parent c = conexao.getRoot();
        esperar(() -> !mensagem(c).isEmpty(), "motivo da falha");
        assertThat(mensagem(c)).contains("Não foi possível conectar ao servidor do banco");
        capturar(conexao, "conexao_sem_servidor");

        fx(() -> {
            ((TextField) c.lookup("#campoUrl")).setText(cr.url());
            ((TextField) c.lookup("#campoUsuario")).setText(cr.usuario());
            ((TextField) c.lookup("#campoSenha")).setText(cr.senha());
            botao(c, "Testar conexão").fire();
            return null;
        });
        esperar(() -> mensagem(c).startsWith("Conexão bem-sucedida"), "Testar conexão");
        assertThat(mensagem(c)).matches("Conexão bem-sucedida: PostgreSQL \\d+(\\.\\d+)*, resposta em \\d+ ms\\.");
        capturar(conexao, "testar_conexao");

        noFx(() -> botao(c, "Salvar").fire());

        // ----- Primeiro acesso -----
        Scene primeiro = esperarCena(s -> s.getRoot().lookup("#campoLoja") != null, "primeiro acesso");
        Parent p = primeiro.getRoot();
        conferirTravaDaUrl(); // o que a tela salvou continua apontando para o banco de teste
        assertThat(AppConfig.carregar(AppConfig.arquivoPadrao()).url()).isEqualTo(cr.url());
        Thread.sleep(300);
        capturar(primeiro, "primeiro_acesso");
        fx(() -> {
            ((TextField) p.lookup("#campoLoja")).setText("Mercado Bom Preço");
            ((TextField) p.lookup("#campoNome")).setText("Ana Admin");
            ((TextField) p.lookup("#campoLogin")).setText("Admin");
            ((TextField) p.lookup("#campoSenha")).setText("segredo1");
            ((TextField) p.lookup("#campoConfirmacao")).setText("segredo1");
            botao(p, "Criar supervisor").fire();
            return null;
        });

        // ----- Login: errado e certo -----
        Scene login = esperarLogin();
        Parent l = login.getRoot();
        assertThat(fx(() -> ((Label) l.lookup("#nomeLoja")).getText())).isEqualTo("Mercado Bom Preço");
        assertThat(contar("SELECT count(*) FROM usuario WHERE login = 'admin' AND supervisor AND ativo "
                + "AND NOT deve_trocar_senha")).as("supervisor criado, login em minúsculas").isEqualTo(1);
        capturar(login, "login");

        fx(() -> {
            ((TextField) l.lookup("#campoLogin")).setText("admin");
            ((TextField) l.lookup("#campoSenha")).setText("senha-errada");
            botao(l, "Entrar").fire();
            return null;
        });
        esperar(() -> mensagem(l).equals("Usuário ou senha inválidos."), "mensagem de login inválido");
        capturar(login, "login_invalido");

        Scene principal = entrar(login, "admin", "segredo1");
        Parent r = principal.getRoot();
        for (ItemMenu item : ItemMenu.values()) {
            assertThat(fxr(() -> r.lookupAll(".button").stream()
                    .anyMatch(b -> b instanceof javafx.scene.control.Button bt && item.rotulo().equals(bt.getText()))))
                    .as("o supervisor vê %s no menu", item.rotulo()).isTrue();
        }
        capturar(principal, "janela_principal");
        assertThat(contar("SELECT count(*) FROM auditoria WHERE acao = 'LOGIN_FALHOU'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM auditoria WHERE acao = 'LOGIN'")).isEqualTo(1);

        // ----- Log off volta ao login -----
        noFx(() -> botao(r, "Log off").fire());
        Scene denovo = esperarLogin();
        assertThat(fx(() -> ((TextField) denovo.getRoot().lookup("#campoLogin")).getText())).isEqualTo("admin");
        assertThat(contar("SELECT count(*) FROM auditoria WHERE acao = 'LOGOUT'")).isEqualTo(1);
    }

    private static String mensagem(Parent raiz) {
        return fxr(() -> {
            String texto = ((Label) raiz.lookup("#mensagem")).getText();
            return texto == null ? "" : texto;
        });
    }
}
