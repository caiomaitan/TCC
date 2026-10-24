package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessaoTest {

    static Usuario usuario(boolean supervisor, Set<Permissao> permissoes) {
        return new Usuario(1, "teste", "Teste", supervisor, true, false, 0, null, null, permissoes);
    }

    @Test
    void supervisorTemTodasAsPermissoes() {
        Sessao sessao = new Sessao(usuario(true, Set.of()), "CAIXA-01");

        assertThat(sessao.permissoes()).containsExactlyInAnyOrder(Permissao.values());
        assertThatCode(sessao::exigirSupervisor).doesNotThrowAnyException();
        for (Permissao p : Permissao.values()) {
            assertThatCode(() -> sessao.exigir(p)).doesNotThrowAnyException();
        }
    }

    @Test
    void operadorSemProdutosERecusadoNoService() {
        Sessao sessao = new Sessao(usuario(false, EnumSet.of(Permissao.PDV)), "CAIXA-01");

        assertThat(sessao.pode(Permissao.PDV)).isTrue();
        assertThat(sessao.pode(Permissao.PRODUTOS)).isFalse();
        assertThatCode(() -> sessao.exigir(Permissao.PDV)).doesNotThrowAnyException();
        assertThatThrownBy(() -> sessao.exigir(Permissao.PRODUTOS))
                .isInstanceOf(PermissaoNegadaException.class)
                .isInstanceOf(NegocioException.class)
                .hasMessage("Você não tem permissão para usar a rotina Produtos. Procure o supervisor.");
        assertThatThrownBy(sessao::exigirSupervisor)
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Somente o supervisor pode realizar esta ação.");
    }

    @Test
    void permissoesDaSessaoNaoPodemSerAlteradas() {
        Sessao sessao = new Sessao(usuario(false, EnumSet.of(Permissao.PDV)), "CAIXA-01");
        assertThatThrownBy(() -> sessao.permissoes().add(Permissao.PRODUTOS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ----- Sessão revalidada (F10.1), com o banco simulado -----

    /** Banco simulado: o usuário "como está no banco agora" e o que a interface recebeu. */
    static final class BancoFalso implements ConferenciaAcesso {
        final java.util.Map<Long, Usuario> usuarios = new java.util.HashMap<>();
        final java.util.List<Sessao> alteradas = new java.util.ArrayList<>();
        final java.util.List<Sessao> encerradas = new java.util.ArrayList<>();

        @Override
        public java.util.Optional<Usuario> ler(long usuarioId) {
            return java.util.Optional.ofNullable(usuarios.get(usuarioId));
        }

        @Override
        public void acessoAlterado(Sessao nova) {
            alteradas.add(nova);
        }

        @Override
        public void acessoEncerrado(Sessao sessao) {
            encerradas.add(sessao);
        }
    }

    static Usuario noBanco(long id, boolean ativo, boolean deveTrocar, int versao, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(java.util.List.of(permissoes));
        return new Usuario(id, "u" + id, "Usuário " + id, false, ativo, deveTrocar, 0, null, null, conjunto, versao);
    }

    @Test
    void versaoIgualValeASessaoEVersaoDiferenteValeOBanco() {
        BancoFalso banco = new BancoFalso();
        Usuario login = noBanco(1, true, false, 3, Permissao.PDV, Permissao.PRODUTOS);
        banco.usuarios.put(1L, login);
        Sessao sessao = new Sessao(login, "CAIXA-01", banco);

        assertThat(sessao.exigir(Permissao.PRODUTOS)).isSameAs(sessao);
        assertThat(banco.alteradas).isEmpty();

        banco.usuarios.put(1L, noBanco(1, true, false, 4, Permissao.PDV));
        assertThatThrownBy(() -> sessao.exigir(Permissao.PRODUTOS)).isInstanceOf(PermissaoNegadaException.class);
        assertThat(banco.alteradas).singleElement().satisfies(nova -> {
            assertThat(nova.versaoAcesso()).isEqualTo(4);
            assertThat(nova.pode(Permissao.PRODUTOS)).isFalse();
        });
        assertThat(sessao.exigir(Permissao.PDV).versaoAcesso()).as("devolve a sessão conferida").isEqualTo(4);
        assertThat(sessao.pode(Permissao.PRODUTOS)).as("a sessão continua imutável").isTrue();
        assertThat(banco.encerradas).isEmpty();
    }

    @Test
    void inativoOuSenhaRedefinidaDepoisDoLoginEncerramASessao() {
        BancoFalso banco = new BancoFalso();
        Usuario login = noBanco(1, true, false, 1, Permissao.PDV);
        Sessao sessao = new Sessao(login, "CAIXA-01", banco);

        banco.usuarios.put(1L, noBanco(1, false, false, 2, Permissao.PDV));
        assertThatThrownBy(() -> sessao.exigir(Permissao.PDV)).isInstanceOf(SessaoEncerradaException.class)
                .hasMessage("Seu acesso foi alterado pelo supervisor. Entre novamente.");
        banco.usuarios.put(1L, noBanco(1, true, true, 3, Permissao.PDV));
        assertThatThrownBy(sessao::exigirSupervisor).isInstanceOf(SessaoEncerradaException.class);
        banco.usuarios.remove(1L);
        assertThatThrownBy(sessao::conferida).isInstanceOf(SessaoEncerradaException.class);
        assertThat(banco.encerradas).hasSize(3);

        // Sessão que já nasceu com a troca de senha pendente (entre o login e a troca) não é encerrada por isso.
        Usuario provisorio = noBanco(2, true, true, 5, Permissao.PDV);
        banco.usuarios.put(2L, provisorio);
        assertThat(new Sessao(provisorio, "CAIXA-01", banco).exigir(Permissao.PDV).versaoAcesso()).isEqualTo(5);
    }

    @Test
    void liberacaoSoValeSeQuemLiberouContinuaAtivoEComAPermissao() {
        BancoFalso banco = new BancoFalso();
        Usuario operador = noBanco(1, true, false, 1, Permissao.PDV);
        banco.usuarios.put(1L, operador);
        banco.usuarios.put(2L, noBanco(2, true, false, 1, Permissao.CANCELAR_VENDA));
        Sessao sessao = new Sessao(operador, "CAIXA-01", banco);
        Liberacao liberacao = new Liberacao(2, "u2", "Usuário 2", Permissao.CANCELAR_VENDA);

        assertThat(sessao.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao)).isEqualTo(liberacao);
        banco.usuarios.put(2L, noBanco(2, true, false, 2));
        assertThatThrownBy(() -> sessao.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("A liberação de Usuário 2 (u2) não vale mais: o acesso dessa pessoa foi alterado. Peça "
                        + "uma nova liberação.");
        banco.usuarios.put(2L, noBanco(2, false, false, 3, Permissao.CANCELAR_VENDA));
        assertThatThrownBy(() -> sessao.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThat(new Sessao(operador, "CAIXA-01").exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao))
                .as("sem conferência (sessão montada à mão), vale o que a liberação diz").isEqualTo(liberacao);
    }

    @Test
    void descreveAsMudancasDePermissao() {
        assertThat(UsuarioService.diferencaPermissoes(
                EnumSet.of(Permissao.PDV, Permissao.RELATORIOS),
                EnumSet.of(Permissao.PDV, Permissao.ESTOQUE, Permissao.PRODUTOS)))
                .isEqualTo("incluídas: Produtos, Estoque; removidas: Relatórios");
        assertThat(UsuarioService.diferencaPermissoes(Set.of(Permissao.PDV), Set.of(Permissao.PDV))).isEmpty();
        assertThat(UsuarioService.rotulos(Set.of())).isEqualTo("nenhuma");
    }
}
