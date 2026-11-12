package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.BuscaGlobalDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.GrupoBusca;
import br.com.varesync.model.ItemBusca;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoFormaPagamento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Busca global (Ctrl+K, F21.3) com os critérios de aceite da parte B: encontra produto (pelo nome, pelo código, pelo
 * código de barras, inclusive o da caixa, e pelo PLU), cliente (nome e CPF), venda pelo número e fornecedor; cada
 * grupo só para quem pode abrir o que o resultado abre; até 8 por grupo; a partir de 2 caracteres. E a segunda via do
 * comprovante, que a venda achada abre.
 */
class BuscaGlobalIT extends IntegracaoBase {

    private static final String UNIDADE = "7891000315507";
    private static final String CAIXA = "17891000315504";
    private static final String CPF_MARIA = "52998224725";

    private AcessoTeste t;
    private BuscaGlobalService busca;
    private Sessao admin;
    private long refri;
    private long laranja;
    private long queijo;
    private long acucar;
    private long maria;
    private long anonimo;
    private long distribuidora;
    private long venda;

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        busca = new BuscaGlobalService(new Transacao(dataSource), new BuscaGlobalDAO(), new FornecedorDAO());
        admin = t.supervisor();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE configuracao SET valor = 'true' WHERE chave = 'balanca.ativo'");
        }
        refri = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 2 L", null, "UN", v("9.49"), v("5.20"),
                null, null, List.of(new CodigoBarras(UNIDADE, true), new CodigoBarras(CAIXA, false)), v("50")));
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE produto_codigo_barras SET quantidade = 12 WHERE codigo = '" + CAIXA + "'");
        }
        laranja = t.produtos.criar(admin, new DadosProduto("Refrigerante laranja 2 L", null, "UN", v("8.99"),
                v("5.00"), null, null, List.of(), null));
        t.produtos.alterarSituacao(admin, laranja, false);
        queijo = t.produtos.criar(admin, new DadosProduto("Queijo prato", null, "KG", v("39.90"), v("25.00"),
                null, 98765, List.of(), null));
        acucar = t.produtos.criar(admin, new DadosProduto("Açúcar refinado 1 kg", null, "UN", v("4.99"), v("3.40"),
                null, null, List.of(), null));

        maria = t.clientes.criar(admin, new DadosCliente("Maria da Silva", "529.982.247-25", null, null, null, null,
                null, null, null, null, "Ribeirão Preto", "SP", null, false));
        anonimo = t.clientes.criar(admin, DadosCliente.rapido("João Anônimo", "111.444.777-35"));
        t.clientes.anonimizar(admin, anonimo, "João Anônimo");
        distribuidora = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora de Bebidas Ltda", "DisBeb",
                "11.222.333/0001-81", null, null, null, "Ribeirão Preto", "SP", null));

        t.caixa.abrir(admin, v("100.00"));
        // A numeração das vendas recomeça em 1 (a limpeza dos testes esvazia a tabela, mas não volta o contador): a
        // venda nº 7 é a de 1 dígito (F21.5) e a última tem 2 dígitos.
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("ALTER TABLE venda ALTER COLUMN id RESTART WITH 1");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        do {
            venda = vender();
        } while (venda < 10);
    }

    private long vender() {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, refri), BigDecimal.ONE);
        Recebimento r = new Recebimento(carrinho.total());
        r.adicionar(t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), carrinho.total());
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private Sessao usuario(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Usuário " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    private static List<ItemBusca> doGrupo(List<ItemBusca> itens, GrupoBusca grupo) {
        return itens.stream().filter(i -> i.grupo() == grupo).toList();
    }

    private static List<GrupoBusca> grupos(List<ItemBusca> itens) {
        return itens.stream().map(ItemBusca::grupo).distinct().toList();
    }

    @Test
    void produtoPeloCodigoDaCaixa() {
        List<ItemBusca> achados = doGrupo(busca.buscar(admin, CAIXA), GrupoBusca.PRODUTOS);

        assertThat(achados).extracting(ItemBusca::id).containsExactly(refri);
        assertThat(achados.get(0).titulo()).isEqualTo("Refrigerante cola 2 L");
        assertThat(achados.get(0).detalhe())
                .isEqualTo("Código " + refri + " · embalagem com 12 UN (código " + CAIXA + ") · R$ 9,49 / UN");
        assertThat(doGrupo(busca.buscar(admin, UNIDADE), GrupoBusca.PRODUTOS).get(0).detalhe())
                .contains("código de barras " + UNIDADE);
    }

    @Test
    void produtoPeloNomeSemAcentoComOsAtivosPrimeiro() {
        assertThat(doGrupo(busca.buscar(admin, "acucar"), GrupoBusca.PRODUTOS)).extracting(ItemBusca::id)
                .containsExactly(acucar);
        List<ItemBusca> refrigerantes = doGrupo(busca.buscar(admin, "REFRI"), GrupoBusca.PRODUTOS);
        assertThat(refrigerantes).extracting(ItemBusca::id).containsExactly(refri, laranja);
        assertThat(refrigerantes.get(1).detalhe()).endsWith(" · inativo");
    }

    @Test
    void produtoPeloCodigoInternoEPeloPlu() {
        assertThat(doGrupo(busca.buscar(admin, String.valueOf(acucar)), GrupoBusca.PRODUTOS))
                .extracting(ItemBusca::id).startsWith(acucar);
        List<ItemBusca> pelaBalanca = doGrupo(busca.buscar(admin, "98765"), GrupoBusca.PRODUTOS);
        assertThat(pelaBalanca).extracting(ItemBusca::id).containsExactly(queijo);
        assertThat(pelaBalanca.get(0).detalhe()).contains(" · PLU 98765 · R$ 39,90 / KG");
    }

    @Test
    void clientePeloNomeEPeloCpfSemOsAnonimizados() {
        assertThat(doGrupo(busca.buscar(admin, "maria"), GrupoBusca.CLIENTES)).extracting(ItemBusca::id)
                .containsExactly(maria);
        assertThat(doGrupo(busca.buscar(admin, "529.982"), GrupoBusca.CLIENTES)).extracting(ItemBusca::id)
                .containsExactly(maria);
        List<ItemBusca> peloCpf = doGrupo(busca.buscar(admin, CPF_MARIA), GrupoBusca.CLIENTES);
        assertThat(peloCpf).extracting(ItemBusca::id).containsExactly(maria);
        assertThat(peloCpf.get(0).detalhe()).as("CPF oculto, como no PDV")
                .isEqualTo("Código " + maria + " · ***.982.247-** · Ribeirão Preto/SP");
        assertThat(doGrupo(busca.buscar(admin, "anonim"), GrupoBusca.CLIENTES)).isEmpty();
        assertThat(doGrupo(busca.buscar(admin, "11144477735"), GrupoBusca.CLIENTES)).isEmpty();
    }

    @Test
    void vendaPeloNumero() {
        List<ItemBusca> achadas = doGrupo(busca.buscar(admin, String.valueOf(venda)), GrupoBusca.VENDAS);

        assertThat(achadas).hasSize(1);
        assertThat(achadas.get(0).id()).isEqualTo(venda);
        assertThat(achadas.get(0).titulo()).isEqualTo("Venda nº " + venda);
        assertThat(achadas.get(0).detalhe()).contains("R$ 9,49");
        assertThat(doGrupo(busca.buscar(admin, "999999999"), GrupoBusca.VENDAS)).isEmpty();
        assertThat(doGrupo(busca.buscar(admin, "refri"), GrupoBusca.VENDAS)).isEmpty();
    }

    /** F21.5: um número puro procura a venda a partir de 1 dígito; os outros grupos continuam pedindo 2. */
    @Test
    void vendaNumeroSeteComUmDigito() {
        assertThat(BuscaGlobalService.procuravel("7")).isTrue();
        assertThat(BuscaGlobalService.procuravel(" 7 ")).isTrue();
        assertThat(BuscaGlobalService.procuravel("a")).isFalse();
        assertThat(BuscaGlobalService.procuravel("")).isFalse();

        List<ItemBusca> achados = busca.buscar(admin, "7");
        assertThat(achados).extracting(ItemBusca::grupo).containsExactly(GrupoBusca.VENDAS);
        assertThat(achados.get(0).id()).isEqualTo(7L);
        assertThat(achados.get(0).titulo()).isEqualTo("Venda nº 7");
        assertThat(busca.buscar(admin, "c")).isEmpty();
        assertThat(busca.buscar(usuario("semvenda1", Permissao.CLIENTES), "7")).isEmpty();
    }

    @Test
    void fornecedorPelaRazaoSocialPeloFantasiaEPeloCnpj() {
        for (String termo : List.of("bebidas", "disbeb", "11.222.333", "11222333000181")) {
            assertThat(doGrupo(busca.buscar(admin, termo), GrupoBusca.FORNECEDORES)).as(termo)
                    .extracting(ItemBusca::id).containsExactly(distribuidora);
        }
    }

    /** Os grupos saem na ordem: produtos, clientes, vendas, fornecedores. */
    @Test
    void gruposNaOrdemProdutosClientesVendasFornecedores() {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE fornecedor SET nome_fantasia = 'Silva Bebidas' WHERE id = " + distribuidora);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(grupos(busca.buscar(admin, "silva"))).containsExactly(GrupoBusca.CLIENTES,
                GrupoBusca.FORNECEDORES);
    }

    /** Aceite da parte B: não mostra o que o usuário não pode abrir. */
    @Test
    void cadaGrupoSoParaQuemPodeAbrir() {
        String numero = String.valueOf(venda);

        Sessao caixa = usuario("caixa", Permissao.PDV);
        assertThat(grupos(busca.buscar(caixa, "refri"))).as("sem PRODUTOS nem ESTOQUE").isEmpty();
        assertThat(grupos(busca.buscar(caixa, numero))).containsExactly(GrupoBusca.VENDAS);
        assertThat(grupos(busca.buscar(caixa, "maria"))).as("sem CLIENTES").isEmpty();

        Sessao estoquista = usuario("estoque", Permissao.ESTOQUE);
        assertThat(grupos(busca.buscar(estoquista, "refri"))).containsExactly(GrupoBusca.PRODUTOS);
        assertThat(grupos(busca.buscar(estoquista, numero))).doesNotContain(GrupoBusca.VENDAS);
        assertThat(grupos(busca.buscar(estoquista, "bebidas"))).as("sem FORNECEDORES").isEmpty();

        Sessao atendente = usuario("atende", Permissao.CLIENTES);
        assertThat(grupos(busca.buscar(atendente, "silva"))).containsExactly(GrupoBusca.CLIENTES);
        assertThat(grupos(busca.buscar(atendente, CAIXA))).isEmpty();

        assertThat(grupos(busca.buscar(usuario("gerente", Permissao.RELATORIOS), numero)))
                .containsExactly(GrupoBusca.VENDAS);
        assertThat(grupos(busca.buscar(usuario("trocas", Permissao.DEVOLUCAO), numero)))
                .containsExactly(GrupoBusca.VENDAS);
        assertThat(grupos(busca.buscar(usuario("compras", Permissao.FORNECEDORES), "bebidas")))
                .containsExactly(GrupoBusca.FORNECEDORES);
    }

    @Test
    void ateOitoPorGrupoEAPartirDeDoisCaracteres() {
        for (int i = 1; i <= 10; i++) {
            t.produtos.criar(admin, new DadosProduto("Biscoito sabor " + i, null, "UN", v("3.00"), v("2.00"), null,
                    null, List.of(), null));
        }
        assertThat(doGrupo(busca.buscar(admin, "biscoito"), GrupoBusca.PRODUTOS)).hasSize(BuscaGlobalService.POR_GRUPO);
        assertThat(busca.buscar(admin, "b")).isEmpty();
        assertThat(busca.buscar(admin, "  b  ")).isEmpty();
        assertThat(busca.buscar(admin, "")).isEmpty();
        assertThat(busca.buscar(admin, "%_")).as("% e _ valem como texto").isEmpty();
    }

    /** A venda achada abre a segunda via: PDV, RELATORIOS ou DEVOLUCAO. */
    @Test
    void segundaViaParaQuemTemPdvRelatoriosOuDevolucao() {
        assertThat(t.vendas.segundaVia(usuario("gerente", Permissao.RELATORIOS), venda).texto())
                .contains("NÃO É DOCUMENTO FISCAL").contains("Refrigerante cola 2 L");
        assertThat(t.vendas.segundaVia(usuario("trocas", Permissao.DEVOLUCAO), venda).texto()).contains("9,49");
        Sessao atendente = usuario("atende", Permissao.CLIENTES);
        assertThatThrownBy(() -> t.vendas.segundaVia(atendente, venda))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Você não tem permissão para usar a rotina PDV. Procure o supervisor.");
        assertThatThrownBy(() -> t.vendas.segundaVia(admin, 999_999_999))
                .isInstanceOf(NegocioException.class).hasMessage("Venda nº 999999999 não encontrada.");
    }
}
