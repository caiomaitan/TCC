package br.com.varesync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import br.com.varesync.service.Sessao;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Telas do menu na busca global (Ctrl+K, F21.3): pelo nome sem acento, só as que o usuário vê no menu. */
class BuscaTelasTest {

    private static Sessao sessao(boolean supervisor, Set<Permissao> permissoes) {
        return new Sessao(new Usuario(1, "teste", "Teste", supervisor, true, false, 0, null, null, permissoes),
                "CAIXA-01");
    }

    @Test
    void achaPeloNomeSemAcentoNemMaiusculas() {
        Sessao supervisor = sessao(true, Set.of());
        assertThat(BuscaTelas.filtrar("invent", supervisor)).containsExactly(ItemMenu.INVENTARIO);
        assertThat(BuscaTelas.filtrar("RELATORIO", supervisor)).containsExactly(ItemMenu.RELATORIOS);
        assertThat(BuscaTelas.filtrar("  devolu ", supervisor)).containsExactly(ItemMenu.DEVOLUCOES);
        assertThat(BuscaTelas.filtrar("co", supervisor)).as("na ordem do menu")
                .containsExactly(ItemMenu.DEVOLUCOES, ItemMenu.COMPRAS, ItemMenu.CONFIGURACOES);
    }

    @Test
    void soAsTelasQueOUsuarioVeNoMenu() {
        Sessao estoquista = sessao(false, EnumSet.of(Permissao.ESTOQUE));
        assertThat(BuscaTelas.filtrar("estoque", estoquista)).containsExactly(ItemMenu.ESTOQUE);
        assertThat(BuscaTelas.filtrar("co", estoquista)).as("Compras e Configurações").isEmpty();
        assertThat(BuscaTelas.filtrar("usu", estoquista)).as("Usuários é do supervisor").isEmpty();
        assertThat(BuscaTelas.filtrar("pain", estoquista)).containsExactly(ItemMenu.PAINEL);
    }

    @Test
    void menosDeDoisCaracteresNaoProcura() {
        Sessao supervisor = sessao(true, Set.of());
        assertThat(BuscaTelas.filtrar("p", supervisor)).isEmpty();
        assertThat(BuscaTelas.filtrar(" p ", supervisor)).isEmpty();
        assertThat(BuscaTelas.filtrar("", supervisor)).isEmpty();
    }
}
