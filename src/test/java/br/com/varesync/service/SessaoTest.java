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
