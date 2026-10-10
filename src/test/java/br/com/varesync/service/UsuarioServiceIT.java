package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cadastro de usuários e permissões, exclusivo do supervisor, com as proteções de 6.1. */
class UsuarioServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private UsuarioService usuarios;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        usuarios = t.usuarios;
        admin = t.supervisor();
    }

    private long criarOperador(String login, Permissao... permissoes) {
        Set<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(Set.of(permissoes));
        return usuarios.criar(admin, login, "Usuário " + login, false, conjunto, "inicial1", "inicial1");
    }

    private Usuario buscar(long id) {
        return usuarios.listar(admin).stream().filter(u -> u.id() == id).findFirst().orElseThrow();
    }

    @Test
    void criaOperadorComSenhaProvisoriaEPermissoes() throws Exception {
        long id = usuarios.criar(admin, " Maria ", " Maria   Souza ", false,
                EnumSet.of(Permissao.PDV, Permissao.ESTOQUE), "inicial1", "inicial1");

        Usuario maria = buscar(id);
        assertThat(maria.login()).isEqualTo("maria");
        assertThat(maria.nome()).isEqualTo("Maria Souza");
        assertThat(maria.supervisor()).isFalse();
        assertThat(maria.ativo()).isTrue();
        assertThat(maria.deveTrocarSenha()).isTrue();
        assertThat(maria.permissoes()).containsExactlyInAnyOrder(Permissao.PDV, Permissao.ESTOQUE);
        assertThat(t.auditoria(AcaoAuditoria.USUARIO_CRIADO)).last().asString()
                .isEqualTo("Usuário Maria Souza (maria) criado como operador; permissões: Estoque, PDV");

        Sessao sessaoMaria = t.acesso.entrar("maria", "inicial1");
        assertThat(sessaoMaria.permissoes()).containsExactlyInAnyOrder(Permissao.PDV, Permissao.ESTOQUE);
        assertThat(sessaoMaria.pode(Permissao.PRODUTOS)).isFalse();
    }

    @Test
    void loginRepetidoOuInvalidoERecusado() {
        criarOperador("maria");
        assertThatThrownBy(() -> criarOperador("MARIA"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Este login já está em uso por outro usuário");
        assertThatThrownBy(() -> criarOperador("ma"))
                .hasMessage(ValidacaoUsuario.LOGIN_INVALIDO);
        assertThatThrownBy(() -> usuarios.criar(admin, "joao", " ", false, Set.of(), "inicial1", "inicial1"))
                .hasMessage("Informe o nome do usuário.");
        assertThatThrownBy(() -> usuarios.criar(admin, "joao", "João", false, Set.of(), "joao", "joao"))
                .hasMessageContaining("ao menos 6");
        assertThatThrownBy(() -> usuarios.criar(admin, "joao123", "João", false, Set.of(), "Joao123", "Joao123"))
                .hasMessage("A senha não pode ser igual ao login.");
    }

    @Test
    void soOSupervisorAdministraUsuarios() {
        long id = criarOperador("caixa1", Permissao.PDV, Permissao.CANCELAR_VENDA, Permissao.RELATORIOS);
        Sessao operador = t.acesso.entrar("caixa1", "inicial1");

        assertThatThrownBy(() -> usuarios.listar(operador))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Somente o supervisor pode realizar esta ação.");
        assertThatThrownBy(() -> usuarios.criar(operador, "outro", "Outro", true, Set.of(), "inicial1", "inicial1"))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> usuarios.alterar(operador, id, "Caixa", true, Set.of()))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> usuarios.alterarSituacao(operador, admin.usuarioId(), false))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> usuarios.redefinirSenha(operador, admin.usuarioId(), "tomada1", "tomada1"))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> usuarios.desbloquear(operador, id))
                .isInstanceOf(PermissaoNegadaException.class);

        assertThat(buscar(id).supervisor()).isFalse();
    }

    @Test
    void alterarNomeSupervisorEPermissoesFicaAuditado() throws Exception {
        long id = criarOperador("caixa1", Permissao.PDV, Permissao.RELATORIOS);

        usuarios.alterar(admin, id, "Carlos Caixa", false, EnumSet.of(Permissao.PDV, Permissao.PRODUTOS));

        Usuario carlos = buscar(id);
        assertThat(carlos.nome()).isEqualTo("Carlos Caixa");
        assertThat(carlos.permissoes()).containsExactlyInAnyOrder(Permissao.PDV, Permissao.PRODUTOS);
        assertThat(t.auditoria(AcaoAuditoria.USUARIO_ALTERADO))
                .containsExactly("Usuário caixa1: nome alterado de \"Usuário caixa1\" para \"Carlos Caixa\"");
        assertThat(t.auditoria(AcaoAuditoria.PERMISSOES_ALTERADAS))
                .containsExactly("Permissões de caixa1: incluídas: Produtos; removidas: Relatórios");

        usuarios.alterar(admin, id, "Carlos Caixa", true, carlos.permissoes());
        assertThat(t.acesso.entrar("caixa1", "inicial1").permissoes()).containsExactlyInAnyOrder(Permissao.values());
        assertThat(t.auditoria(AcaoAuditoria.USUARIO_ALTERADO)).last().asString()
                .isEqualTo("Usuário caixa1: passou a ser supervisor");
        assertThat(t.auditoria(AcaoAuditoria.PERMISSOES_ALTERADAS)).hasSize(1);
    }

    @Test
    void ninguemInativaASiMesmo() {
        assertThatThrownBy(() -> usuarios.alterarSituacao(admin, admin.usuarioId(), false))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Você não pode inativar o seu próprio usuário.");
        assertThat(buscar(admin.usuarioId()).ativo()).isTrue();
    }

    @Test
    void naoEPossivelInativarOuRebaixarOUltimoSupervisorAtivo() {
        long id = usuarios.criar(admin, "sup2", "Segundo Supervisor", true, Set.of(), "inicial1", "inicial1");
        Sessao sup2 = t.acesso.entrar("sup2", "inicial1");

        // Com dois supervisores ativos, um pode inativar o outro.
        usuarios.alterarSituacao(sup2, admin.usuarioId(), false);
        assertThat(buscar(admin.usuarioId()).ativo()).isFalse();

        // Agora sup2 é o último supervisor ativo: ninguém o inativa nem tira o perfil dele.
        assertThatThrownBy(() -> usuarios.alterarSituacao(sup2, id, false))
                .hasMessage("Você não pode inativar o seu próprio usuário.");
        assertThatThrownBy(() -> usuarios.alterar(sup2, id, "Segundo Supervisor", false, Set.of()))
                .hasMessage("Você não pode remover o seu próprio acesso de supervisor.");

        // Mesmo um supervisor que sobrou numa sessão antiga (admin, já inativo) não consegue.
        assertThatThrownBy(() -> usuarios.alterarSituacao(admin, id, false))
                .hasMessage(UsuarioService.SEM_SUPERVISOR);
        assertThatThrownBy(() -> usuarios.alterar(admin, id, "Segundo Supervisor", false, Set.of()))
                .hasMessage(UsuarioService.SEM_SUPERVISOR);

        assertThat(buscar(id).supervisor()).isTrue();
        assertThat(buscar(id).ativo()).isTrue();

        // Reativar devolve o segundo supervisor; aí o rebaixamento é permitido.
        usuarios.alterarSituacao(sup2, admin.usuarioId(), true);
        usuarios.alterar(admin, id, "Segundo Supervisor", false, Set.of(Permissao.PDV));
        assertThat(buscar(id).supervisor()).isFalse();
    }

    @Test
    void redefinirSenhaObrigaATrocaEDesbloqueia() throws Exception {
        long id = criarOperador("caixa1", Permissao.PDV);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> t.acesso.entrar("caixa1", "errada")).isInstanceOf(NegocioException.class);
        }
        assertThat(buscar(id).bloqueadoAte()).isNotNull();

        usuarios.redefinirSenha(admin, id, "provisoria9", "provisoria9");

        Sessao sessao = t.acesso.entrar("caixa1", "provisoria9");
        assertThat(sessao.deveTrocarSenha()).isTrue();
        assertThat(t.auditoria(AcaoAuditoria.SENHA_REDEFINIDA))
                .containsExactly("Senha provisória definida para caixa1 pelo supervisor");
    }

    @Test
    void desbloquearLiberaOLoginAntesDosCincoMinutos() throws Exception {
        long id = criarOperador("caixa1", Permissao.PDV);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> t.acesso.entrar("caixa1", "errada")).isInstanceOf(NegocioException.class);
        }
        assertThatThrownBy(() -> t.acesso.entrar("caixa1", "inicial1")).hasMessageStartingWith("Usuário bloqueado");

        usuarios.desbloquear(admin, id);

        assertThat(t.acesso.entrar("caixa1", "inicial1").usuario().login()).isEqualTo("caixa1");
        assertThat(t.auditoria(AcaoAuditoria.USUARIO_DESBLOQUEADO)).containsExactly("Usuário caixa1 desbloqueado");
    }

    @Test
    void usuarioInexistente() {
        assertThatThrownBy(() -> usuarios.desbloquear(admin, 999_999))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Usuário não encontrado.");
    }
}
