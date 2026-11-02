package br.com.varesync.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import br.com.varesync.service.Sessao;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ItemMenuTest {

    private static Sessao sessao(boolean supervisor, Set<Permissao> permissoes) {
        return new Sessao(new Usuario(1, "teste", "Teste", supervisor, true, false, 0, null, null, permissoes),
                "CAIXA-01");
    }

    /** F15: o menu Financeiro aparece só com a permissão FINANCEIRO. */
    @Test
    void financeiroAbreASuaTelaComAPermissaoFinanceiro() {
        Sessao financeiro = sessao(false, EnumSet.of(Permissao.FINANCEIRO));
        assertThat(ItemMenu.visiveis(financeiro)).containsExactly(ItemMenu.PAINEL, ItemMenu.FINANCEIRO);
        assertThat(ItemMenu.FINANCEIRO.fxml()).isEqualTo("/fxml/financeiro.fxml");
        assertThat(ItemMenu.FINANCEIRO.janelaPropria()).isFalse();
    }

    @Test
    void supervisorVeTodoOMenu() {
        assertThat(ItemMenu.visiveis(sessao(true, Set.of()))).containsExactly(ItemMenu.values());
    }

    @Test
    void operadorSemProdutosNaoVeOMenuProdutos() {
        Sessao caixa = sessao(false, EnumSet.of(Permissao.PDV, Permissao.SANGRIA_SUPRIMENTO));

        assertThat(ItemMenu.visiveis(caixa)).containsExactly(ItemMenu.PAINEL, ItemMenu.PDV, ItemMenu.CAIXA);
        assertThat(ItemMenu.PRODUTOS.visivelPara(caixa)).isFalse();
    }

    @Test
    void cadaPermissaoDeRotinaMostraOSeuItem() {
        Sessao estoquista = sessao(false, EnumSet.of(Permissao.PRODUTOS, Permissao.ESTOQUE, Permissao.INVENTARIO));
        assertThat(ItemMenu.visiveis(estoquista))
                .containsExactly(ItemMenu.PAINEL, ItemMenu.PRODUTOS, ItemMenu.ESTOQUE, ItemMenu.INVENTARIO);

        Sessao comprador = sessao(false, EnumSet.of(Permissao.COMPRAS, Permissao.FORNECEDORES, Permissao.RELATORIOS));
        assertThat(ItemMenu.visiveis(comprador))
                .containsExactly(ItemMenu.PAINEL, ItemMenu.COMPRAS, ItemMenu.FORNECEDORES, ItemMenu.RELATORIOS);

        Sessao atendente = sessao(false, EnumSet.of(Permissao.PDV, Permissao.CLIENTES));
        assertThat(ItemMenu.visiveis(atendente))
                .containsExactly(ItemMenu.PAINEL, ItemMenu.PDV, ItemMenu.CAIXA, ItemMenu.CLIENTES);
        assertThat(ItemMenu.CLIENTES.fxml()).isEqualTo("/fxml/clientes.fxml");

        // F13.4: Devoluções com a permissão DEVOLUCAO (no PDV, o F9 devolve com liberação).
        Sessao trocas = sessao(false, EnumSet.of(Permissao.PDV, Permissao.DEVOLUCAO));
        assertThat(ItemMenu.visiveis(trocas))
                .containsExactly(ItemMenu.PAINEL, ItemMenu.PDV, ItemMenu.CAIXA, ItemMenu.DEVOLUCOES);
        assertThat(ItemMenu.DEVOLUCOES.fxml()).isEqualTo("/fxml/devolucoes.fxml");
    }

    @Test
    void pdvECaixaAparecemComAPermissaoPdvESoOPdvAbreEmJanelaPropria() {
        Sessao semPdv = sessao(false, EnumSet.of(Permissao.SANGRIA_SUPRIMENTO, Permissao.CANCELAR_VENDA));
        assertThat(ItemMenu.visiveis(semPdv)).containsExactly(ItemMenu.PAINEL);

        assertThat(ItemMenu.PDV.rotulo()).isEqualTo("PDV");
        assertThat(ItemMenu.CAIXA.rotulo()).isEqualTo("Caixa");
        assertThat(ItemMenu.PDV.fxml()).isEqualTo("/fxml/pdv.fxml");
        assertThat(ItemMenu.CAIXA.fxml()).isEqualTo("/fxml/caixa.fxml");
        assertThat(ItemMenu.values()).filteredOn(ItemMenu::janelaPropria).containsExactly(ItemMenu.PDV);
    }

    @Test
    void inventarioAbreASuaTelaComAPermissaoInventario() {
        Sessao conferente = sessao(false, EnumSet.of(Permissao.INVENTARIO));

        assertThat(ItemMenu.visiveis(conferente)).containsExactly(ItemMenu.PAINEL, ItemMenu.INVENTARIO);
        assertThat(ItemMenu.INVENTARIO.fxml()).isEqualTo("/fxml/inventario.fxml");
        assertThat(ItemMenu.INVENTARIO.janelaPropria()).isFalse();
    }

    @Test
    void relatoriosAbreATelaGenericaComAPermissaoRelatorios() {
        Sessao gerente = sessao(false, EnumSet.of(Permissao.RELATORIOS));

        assertThat(ItemMenu.visiveis(gerente)).containsExactly(ItemMenu.PAINEL, ItemMenu.RELATORIOS);
        assertThat(ItemMenu.RELATORIOS.fxml()).isEqualTo("/fxml/relatorios.fxml");
        assertThat(ItemMenu.RELATORIOS.janelaPropria()).isFalse();
    }

    @Test
    void comprasEFornecedoresAbremAsSuasTelasCadaUmComASuaPermissao() {
        assertThat(ItemMenu.visiveis(sessao(false, EnumSet.of(Permissao.COMPRAS))))
                .containsExactly(ItemMenu.PAINEL, ItemMenu.COMPRAS);
        assertThat(ItemMenu.visiveis(sessao(false, EnumSet.of(Permissao.FORNECEDORES))))
                .containsExactly(ItemMenu.PAINEL, ItemMenu.FORNECEDORES);
        assertThat(ItemMenu.COMPRAS.fxml()).isEqualTo("/fxml/compras.fxml");
        assertThat(ItemMenu.FORNECEDORES.fxml()).isEqualTo("/fxml/fornecedores.fxml");
        assertThat(ItemMenu.values()).as("todas as rotinas já têm tela").allSatisfy(item ->
                assertThat(item == ItemMenu.PAINEL || item.fxml() != null).isTrue());
    }

    @Test
    void usuariosEConfiguracoesSoParaSupervisor() {
        Sessao quaseTudo = sessao(false, EnumSet.allOf(Permissao.class));
        assertThat(ItemMenu.visiveis(quaseTudo)).doesNotContain(ItemMenu.USUARIOS, ItemMenu.CONFIGURACOES);
    }

    @Test
    void semNenhumaPermissaoSoOPainel() {
        assertThat(ItemMenu.visiveis(sessao(false, Set.of()))).containsExactly(ItemMenu.PAINEL);
    }
}
