package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Permissao;
import br.com.varesync.service.AcessoService.PrimeiroAcesso;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Primeiro acesso, login com bloqueio, troca de senha, logout e liberação de supervisor (6.1, 6.2). */
class AcessoServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private AcessoService acesso;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        acesso = t.acesso;
    }

    // ---- Primeiro acesso ----

    @Test
    void primeiroAcessoCriaOSupervisorEGravaONomeDaLoja() throws Exception {
        assertThat(acesso.precisaPrimeiroAcesso()).isTrue();

        long id = acesso.criarPrimeiroSupervisor(new PrimeiroAcesso(
                "  Mercado   Bom Preço ", "Ana  Admin", "  Admin ", "segredo1", "segredo1"));

        assertThat(acesso.precisaPrimeiroAcesso()).isFalse();
        assertThat(acesso.nomeEmpresa()).isEqualTo("Mercado Bom Preço");
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("""
                     SELECT login, nome, senha_hash, supervisor, ativo, deve_trocar_senha
                       FROM usuario WHERE id = ?
                     """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("login")).isEqualTo("admin");
                assertThat(rs.getString("nome")).isEqualTo("Ana Admin");
                assertThat(rs.getString("senha_hash")).startsWith("$2a$").doesNotContain("segredo1");
                assertThat(t.senhas.confere("segredo1", rs.getString("senha_hash"))).isTrue();
                assertThat(rs.getBoolean("supervisor")).isTrue();
                assertThat(rs.getBoolean("ativo")).isTrue();
                assertThat(rs.getBoolean("deve_trocar_senha")).as("não existe senha padrão a trocar").isFalse();
            }
        }
        assertThat(t.auditoria(AcaoAuditoria.USUARIO_CRIADO)).singleElement().asString()
                .contains("Primeiro acesso", "admin");

        Sessao sessao = acesso.entrar("admin", "segredo1");
        assertThat(sessao.supervisor()).isTrue();
        assertThat(sessao.permissoes()).containsExactlyInAnyOrder(Permissao.values());
        assertThat(sessao.deveTrocarSenha()).isFalse();
    }

    @Test
    void primeiroAcessoSoAconteceUmaVez() {
        t.supervisor();
        assertThatThrownBy(() -> acesso.criarPrimeiroSupervisor(new PrimeiroAcesso(
                "Outra", "Intruso", "intruso", "segredo1", "segredo1")))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("primeiro acesso já foi feito");
    }

    @Test
    void primeiroAcessoValidaOsCampos() {
        assertThatThrownBy(() -> acesso.criarPrimeiroSupervisor(new PrimeiroAcesso(
                " ", "Ana", "admin", "segredo1", "segredo1"))).hasMessage("Informe o nome da loja.");
        assertThatThrownBy(() -> acesso.criarPrimeiroSupervisor(new PrimeiroAcesso(
                "Loja", "Ana", "Ana Maria", "segredo1", "segredo1"))).hasMessageStartingWith("O login deve ter");
        assertThatThrownBy(() -> acesso.criarPrimeiroSupervisor(new PrimeiroAcesso(
                "Loja", "Ana", "admin", "12345", "12345")))
                .hasMessage("A senha deve ter ao menos 6 caracteres e ser diferente do login.");
        assertThatThrownBy(() -> acesso.criarPrimeiroSupervisor(new PrimeiroAcesso(
                "Loja", "Ana", "admin1", "ADMIN1", "ADMIN1"))).hasMessage("A senha não pode ser igual ao login.");
        assertThatThrownBy(() -> acesso.criarPrimeiroSupervisor(new PrimeiroAcesso(
                "Loja", "Ana", "admin", "segredo1", "segredo2")))
                .hasMessage("A confirmação não confere com a senha.");
        assertThat(acesso.precisaPrimeiroAcesso()).isTrue();
    }

    // ---- Login ----

    @Test
    void loginNormalizaGravaUltimoLoginEAudita() throws Exception {
        t.supervisor();
        t.relogio.avancar(Duration.ofHours(1));

        Sessao sessao = acesso.entrar("  ADMIN ", "segredo1");

        assertThat(sessao.usuario().login()).isEqualTo("admin");
        assertThat(sessao.terminal()).isEqualTo(AcessoTeste.TERMINAL);
        assertThat(sessao.usuario().ultimoLoginEm()).isEqualTo(OffsetDateTime.now(t.relogio));
        assertThat(t.auditoria(AcaoAuditoria.LOGIN)).hasSize(2).allMatch(d -> d.equals("Entrou no sistema"));
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = 'LOGIN' AND terminal = 'CAIXA-TESTE'"
                + " AND usuario_id IS NOT NULL")).isEqualTo(2);
    }

    @Test
    void falhaDeLoginTemMensagemGenericaEFicaAuditada() throws Exception {
        t.supervisor();

        assertThatThrownBy(() -> acesso.entrar("admin", "errada"))
                .isInstanceOf(NegocioException.class).hasMessage(AcessoService.FALHA_LOGIN);
        assertThatThrownBy(() -> acesso.entrar("ninguem", "errada"))
                .isInstanceOf(NegocioException.class).hasMessage(AcessoService.FALHA_LOGIN);

        assertThat(t.auditoria(AcaoAuditoria.LOGIN_FALHOU))
                .containsExactly("Login: senha incorreta (tentativa 1 de 5)", "Login: login inexistente");
        assertThat(t.contar("SELECT tentativas_falhas FROM usuario WHERE login = 'admin'")).isEqualTo(1);
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE descricao LIKE '%errada%'"))
                .as("a senha digitada nunca vai para a auditoria").isZero();
    }

    @Test
    void cincoSenhasErradasBloqueiamPorCincoMinutos() throws Exception {
        t.supervisor();
        OffsetDateTime fimBloqueio = OffsetDateTime.now(t.relogio).plusMinutes(5);
        String mensagemBloqueio = "Usuário bloqueado por excesso de tentativas. Tente novamente às "
                + AcessoService.hora(fimBloqueio) + ".";

        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> acesso.entrar("admin", "errada")).hasMessage(AcessoService.FALHA_LOGIN);
        }
        assertThatThrownBy(() -> acesso.entrar("admin", "errada")).hasMessage(mensagemBloqueio);

        // Bloqueado: nem a senha certa entra, e a tentativa não conta para um novo bloqueio.
        t.relogio.avancar(Duration.ofMinutes(4).plusSeconds(59));
        assertThatThrownBy(() -> acesso.entrar("admin", "segredo1")).hasMessage(mensagemBloqueio);

        t.relogio.avancar(Duration.ofSeconds(1));
        Sessao sessao = acesso.entrar("admin", "segredo1");

        assertThat(sessao.usuario().tentativasFalhas()).isZero();
        assertThat(sessao.usuario().bloqueadoAte()).isNull();
        assertThat(t.auditoria(AcaoAuditoria.LOGIN_FALHOU)).hasSize(6)
                .anyMatch(d -> d.contains("5ª vez seguida; bloqueado até"))
                .anyMatch(d -> d.contains("usuário bloqueado até"));
    }

    @Test
    void acertoZeraAsFalhasAnteriores() throws Exception {
        t.supervisor();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> acesso.entrar("admin", "errada")).hasMessage(AcessoService.FALHA_LOGIN);
        }
        acesso.entrar("admin", "segredo1");

        // Contagem recomeça: mais 4 erros ainda não bloqueiam.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> acesso.entrar("admin", "errada")).hasMessage(AcessoService.FALHA_LOGIN);
        }
        assertThat(acesso.entrar("admin", "segredo1").usuario().tentativasFalhas()).isZero();
    }

    @Test
    void usuarioInativoNaoEntra() {
        Sessao admin = t.supervisor();
        long id = t.usuarios.criar(admin, "caixa1", "Carlos Caixa", false, Set.of(Permissao.PDV), "inicial1", "inicial1");
        t.usuarios.alterarSituacao(admin, id, false);

        assertThatThrownBy(() -> acesso.entrar("caixa1", "inicial1"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Este usuário está inativo. Procure o supervisor.");
        assertThatThrownBy(() -> acesso.entrar("caixa1", "errada")).hasMessage(AcessoService.FALHA_LOGIN);
    }

    @Test
    void loginSemUsuarioOuSenhaPedeOsDados() {
        assertThatThrownBy(() -> acesso.entrar(" ", "x")).hasMessage("Informe o usuário e a senha.");
        assertThatThrownBy(() -> acesso.entrar("admin", "")).hasMessage("Informe o usuário e a senha.");
    }

    // ---- Troca de senha ----

    @Test
    void senhaProvisoriaObrigaATrocaAntesDeEntrar() throws Exception {
        Sessao admin = t.supervisor();
        t.usuarios.criar(admin, "caixa1", "Carlos Caixa", false, Set.of(Permissao.PDV), "inicial1", "inicial1");

        Sessao provisoria = acesso.entrar("caixa1", "inicial1");
        assertThat(provisoria.deveTrocarSenha()).isTrue();

        assertThatThrownBy(() -> acesso.trocarSenha(provisoria, "errada", "novaSenha1", "novaSenha1"))
                .hasMessage("A senha atual está incorreta.");
        assertThatThrownBy(() -> acesso.trocarSenha(provisoria, "inicial1", "inicial1", "inicial1"))
                .hasMessage("A nova senha deve ser diferente da atual.");
        assertThatThrownBy(() -> acesso.trocarSenha(provisoria, "inicial1", "caixa1", "caixa1"))
                .hasMessage("A senha não pode ser igual ao login.");

        Sessao definitiva = acesso.trocarSenha(provisoria, "inicial1", "novaSenha1", "novaSenha1");

        assertThat(definitiva.deveTrocarSenha()).isFalse();
        assertThat(acesso.entrar("caixa1", "novaSenha1").deveTrocarSenha()).isFalse();
        assertThatThrownBy(() -> acesso.entrar("caixa1", "inicial1")).hasMessage(AcessoService.FALHA_LOGIN);
        assertThat(t.auditoria(AcaoAuditoria.SENHA_REDEFINIDA)).contains("Senha trocada pelo próprio usuário");
    }

    // ---- Logout ----

    @Test
    void logoutFicaAuditado() throws Exception {
        Sessao admin = t.supervisor();
        acesso.sair(admin);
        assertThat(t.auditoria(AcaoAuditoria.LOGOUT)).containsExactly("Saiu do sistema");
    }

    // ---- Liberação de supervisor ----

    @Test
    void liberacaoPorSupervisorOuPorQuemTemAPermissao() {
        Sessao admin = t.supervisor();
        t.usuarios.criar(admin, "gerente", "Gil Gerente", false, Set.of(Permissao.CANCELAR_VENDA),
                "inicial1", "inicial1");
        t.usuarios.criar(admin, "caixa1", "Carlos Caixa", false, Set.of(Permissao.PDV), "inicial1", "inicial1");

        Liberacao porSupervisor = acesso.liberar("ADMIN", "segredo1", Permissao.CANCELAR_VENDA);
        assertThat(porSupervisor.login()).isEqualTo("admin");
        assertThat(porSupervisor.descricao()).isEqualTo("liberado por Ana Admin (admin)");

        assertThat(acesso.liberar("gerente", "inicial1", Permissao.CANCELAR_VENDA).nome()).isEqualTo("Gil Gerente");

        assertThatThrownBy(() -> acesso.liberar("caixa1", "inicial1", Permissao.CANCELAR_VENDA))
                .hasMessageContaining("não tem permissão para liberar");
        assertThatThrownBy(() -> acesso.liberar("gerente", "inicial1", Permissao.DESCONTO_ACIMA_LIMITE))
                .hasMessageContaining("não tem permissão para liberar");
        assertThatThrownBy(() -> acesso.liberar("admin", "errada", Permissao.CANCELAR_VENDA))
                .hasMessage(AcessoService.FALHA_LOGIN);
    }

    @Test
    void senhaErradaNaLiberacaoContaParaOBloqueio() throws Exception {
        t.supervisor();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> acesso.liberar("admin", "errada", Permissao.SANGRIA_SUPRIMENTO))
                    .isInstanceOf(NegocioException.class);
        }
        assertThatThrownBy(() -> acesso.entrar("admin", "segredo1")).hasMessageStartingWith("Usuário bloqueado");
        assertThat(t.auditoria(AcaoAuditoria.LOGIN_FALHOU)).hasSize(6)
                .satisfies(d -> assertThat(d.subList(0, 5)).allMatch(s -> s.startsWith("Liberação: senha incorreta")))
                .last().asString().startsWith("Login: usuário bloqueado");
    }
}
