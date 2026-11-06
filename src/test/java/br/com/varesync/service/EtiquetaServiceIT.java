package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.ConteudoLiquido;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.model.OrigemEtiqueta;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Produto;
import br.com.varesync.model.UnidadeConteudo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Etiquetas de gôndola (F18.1): pendências, "Marcar como em dia", promoção DE/POR que volta a ficar pendente no fim,
 * critérios de seleção, conteúdo líquido no cadastro, auditoria e permissão. O PDF e a térmica têm testes próprios
 * ({@code EtiquetasPdfTest}, {@code ModeloTermicaTest}); aqui as etiquetas "impressas" são as que o service preparou.
 */
class EtiquetaServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private EtiquetaService etiquetas;
    private Sessao admin;
    private long cafe;
    private long arroz;
    private long feijao;
    private long acucar;
    private long semEstoque;
    private long inativo;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        etiquetas = t.etiquetas;
        admin = t.supervisor();
        cafe = produto("Café torrado 500 g", "18.90", "7891000315507", "10");
        arroz = produto("Arroz tipo 1 5 kg", "27.90", "7891234567895", "10");
        feijao = produto("Feijão carioca 1 kg", "8.49", null, "10");
        acucar = produto("Açúcar cristal 1 kg", "4.79", "12345670", "10");
        semEstoque = produto("Sal refinado 1 kg", "2.99", null, null);
        inativo = produto("Produto antigo", "1.00", null, "5");
        t.produtos.alterarSituacao(admin, inativo, false);
    }

    private long produto(String nome, String preco, String codigo, String estoque) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, "UN", v(preco), v("1.00"), null, null,
                codigo == null ? List.of() : List.of(new CodigoBarras(codigo, true)),
                estoque == null ? null : v(estoque)));
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private void mudarPreco(long id, String preco) {
        Produto p = t.produtos.buscar(admin, id);
        t.produtos.alterar(admin, id, new DadosProduto(p.nome(), p.categoriaId(), p.unidade(), v(preco), p.custo(),
                p.estoqueMinimo(), p.plu(), p.codigosBarras(), null));
    }

    private static List<Long> ids(List<Etiqueta> lista) {
        return lista.stream().map(Etiqueta::produtoId).toList();
    }

    @Test
    void pendenciasDoAceite() throws Exception {
        assertThat(ids(etiquetas.pendentes(admin))).as("nunca impressas, com estoque e ativas")
                .containsExactlyInAnyOrder(cafe, arroz, feijao, acucar);
        assertThat(etiquetas.contarPendentes(admin)).isEqualTo(4);
        // Com as etiquetas em dia, alterar o preço de 3 produtos gera 3 pendentes.
        etiquetas.marcarEmDia(admin, ids(etiquetas.pendentes(admin)));
        assertThat(etiquetas.pendentes(admin)).isEmpty();
        t.relogio.avancar(Duration.ofMinutes(5));
        mudarPreco(cafe, "19.90");
        mudarPreco(arroz, "26.90");
        mudarPreco(feijao, "8.99");
        List<Etiqueta> pendentes = etiquetas.pendentes(admin);
        assertThat(ids(pendentes)).containsExactlyInAnyOrder(cafe, arroz, feijao);
        assertThat(pendentes).allMatch(Etiqueta::pendente);
        assertThat(pendentes.stream().filter(e -> e.produtoId() == cafe).findFirst().orElseThrow().preco())
                .isEqualByComparingTo("19.90");

        // Imprimir em PDF zera as pendências.
        t.relogio.avancar(Duration.ofMinutes(5));
        etiquetas.registrarImpressao(admin, List.of(pendentes.get(0).comQuantidade(3), pendentes.get(1),
                pendentes.get(2)), OrigemEtiqueta.PDF);
        assertThat(etiquetas.pendentes(admin)).isEmpty();
        assertThat(t.auditoria(AcaoAuditoria.ETIQUETAS_IMPRESSAS))
                .containsExactly("Etiquetas impressas (PDF): 3 produtos, 5 etiquetas");

        // A reimpressão continua possível: o produto em dia aparece pela lista manual e registra de novo.
        Etiqueta deNovo = etiquetas.produto(admin, cafe);
        assertThat(deNovo.pendente()).isFalse();
        assertThat(deNovo.ultimaImpressao()).isNotNull();
        etiquetas.registrarImpressao(admin, List.of(deNovo), OrigemEtiqueta.TERMICA);
        assertThat(t.contar("SELECT count(*) FROM etiqueta_impressao WHERE produto_id = " + cafe)).isEqualTo(3);
        assertThat(t.contar("SELECT count(*) FROM etiqueta_impressao WHERE origem = 'TERMICA'")).isEqualTo(1);
        assertThat(etiquetas.pendentes(admin)).isEmpty();
    }

    @Test
    void marcarComoEmDiaZeraSemImprimir() throws Exception {
        int marcados = etiquetas.marcarEmDia(admin, List.of(cafe, arroz, feijao, acucar, inativo, cafe));
        assertThat(marcados).as("o inativo fica de fora; o repetido conta uma vez").isEqualTo(4);
        assertThat(etiquetas.pendentes(admin)).isEmpty();
        assertThat(t.contar("SELECT count(*) FROM etiqueta_impressao WHERE origem = 'EM_DIA'")).isEqualTo(4);
        assertThat(t.contar("SELECT count(*) FROM etiqueta_impressao WHERE origem <> 'EM_DIA'")).isZero();
        assertThat(t.auditoria(AcaoAuditoria.ETIQUETAS_EM_DIA))
                .containsExactly("Etiquetas marcadas como em dia, sem imprimir: 4 produtos");
        assertThatThrownBy(() -> etiquetas.marcarEmDia(admin, List.of())).isInstanceOf(NegocioException.class);
    }

    @Test
    void promocaoSaiComoDePorEVoltaAFicarPendenteNoFim() {
        LocalDate hoje = etiquetas.hoje();
        etiquetas.marcarEmDia(admin, List.of(cafe, arroz, feijao, acucar));
        long promo = t.promocoes.criar(admin, new DadosPromocao(cafe, v("15.90"), hoje, hoje.plusDays(2), true));

        Etiqueta naPromocao = etiquetas.pendentes(admin).get(0);
        assertThat(naPromocao.produtoId()).isEqualTo(cafe);
        assertThat(naPromocao.preco()).isEqualByComparingTo("15.90");
        assertThat(naPromocao.precoDe()).isEqualByComparingTo("18.90");
        assertThat(naPromocao.promocaoId()).isEqualTo(promo);
        assertThat(naPromocao.fimPromocao()).isEqualTo(hoje.plusDays(2));
        etiquetas.registrarImpressao(admin, List.of(naPromocao), OrigemEtiqueta.PDF);
        assertThat(etiquetas.pendentes(admin)).isEmpty();

        t.relogio.avancar(Duration.ofDays(2));
        assertThat(etiquetas.pendentes(admin)).as("último dia da promoção ainda vale").isEmpty();
        t.relogio.avancar(Duration.ofDays(1));
        Etiqueta depois = etiquetas.pendentes(admin).get(0);
        assertThat(depois.produtoId()).isEqualTo(cafe);
        assertThat(depois.preco()).isEqualByComparingTo("18.90");
        assertThat(depois.precoDe()).isNull();
        assertThat(depois.promocional()).isFalse();
    }

    @Test
    void outraPromocaoComOMesmoPrecoTambemFicaPendente() {
        LocalDate hoje = etiquetas.hoje();
        long primeira = t.promocoes.criar(admin, new DadosPromocao(arroz, v("24.90"), hoje, hoje, true));
        etiquetas.marcarEmDia(admin, List.of(cafe, arroz, feijao, acucar));
        t.promocoes.criar(admin, new DadosPromocao(arroz, v("24.90"), hoje.plusDays(1), hoje.plusDays(5), true));
        t.relogio.avancar(Duration.ofDays(1));
        List<Etiqueta> pendentes = etiquetas.pendentes(admin);
        assertThat(ids(pendentes)).as("mesmo preço, promoção diferente (a validade na etiqueta mudou)")
                .containsExactly(arroz);
        assertThat(pendentes.get(0).promocaoId()).isNotEqualTo(primeira);
    }

    @Test
    void criteriosDeSelecao() throws Exception {
        long categoria = t.categorias.listar(admin, false).get(0).id();
        Produto p = t.produtos.buscar(admin, acucar);
        t.produtos.alterar(admin, acucar, new DadosProduto(p.nome(), categoria, p.unidade(), p.precoVenda(),
                p.custo(), p.estoqueMinimo(), null, p.codigosBarras(), null));
        assertThat(ids(etiquetas.daCategoria(admin, categoria))).containsExactly(acucar);

        LocalDate hoje = etiquetas.hoje();
        t.relogio.avancar(Duration.ofMinutes(1)); // mesmo instante do cadastro: o trigger usaria now() (V3)
        mudarPreco(feijao, "8.79");
        t.relogio.avancar(Duration.ofDays(3));
        mudarPreco(arroz, "29.90");
        assertThat(ids(etiquetas.alteradosDesde(admin, hoje))).containsExactlyInAnyOrder(feijao, arroz);
        assertThat(ids(etiquetas.alteradosDesde(admin, hoje.plusDays(1)))).containsExactly(arroz);
        assertThatThrownBy(() -> etiquetas.alteradosDesde(admin, hoje.plusDays(10)))
                .hasMessageContaining("depois de hoje");

        long fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Teste Ltda", "Teste",
                "12ABC34501DE35", null, null, null, null, null, null));
        long entrada = t.compras.criar(admin, new DadosEntrada(fornecedor, "123", hoje, null));
        t.compras.adicionarItem(admin, entrada, cafe, v("6"), v("10.00"), null);
        t.compras.adicionarItem(admin, entrada, semEstoque, v("12"), v("1.00"), null);
        assertThat(ids(etiquetas.daEntrada(admin, entrada))).as("na ordem do nome")
                .containsExactly(cafe, semEstoque);
        assertThatThrownBy(() -> etiquetas.daEntrada(admin, entrada + 100)).hasMessageContaining("não foi encontrada");

        assertThat(etiquetas.produto(admin, semEstoque).pendente()).as("sem estoque não fica pendente").isFalse();
        assertThatThrownBy(() -> etiquetas.produto(admin, inativo)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("inativo");
        assertThat(ids(etiquetas.produtos(admin, List.of(inativo, feijao, cafe)))).containsExactly(cafe, feijao);
    }

    @Test
    void codigoDeBarrasPluEConteudoNaEtiqueta() throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE configuracao SET valor = 'true' WHERE chave = 'balanca.ativo'");
        }
        long queijo = t.produtos.criar(admin, new DadosProduto("Queijo mussarela", null, "KG", v("39.90"), v("20"),
                null, 123, List.of(new CodigoBarras("7896004000015", true)),
                v("2.5")));
        long fardo = t.produtos.criar(admin, new DadosProduto("Água mineral fardo", null, "UN", v("12.00"), v("6"),
                null, null, List.of(new CodigoBarras("17891000315504", true)), v("3"), null, null,
                new ConteudoLiquido(v("500"), UnidadeConteudo.ML)));
        long biscoito = t.produtos.criar(admin, new DadosProduto("Biscoito recheado", null, "UN", v("8.99"), v("4"),
                null, null, List.of(), v("3"), null, null, new ConteudoLiquido(v("500"), UnidadeConteudo.G)));

        Etiqueta q = etiquetas.produto(admin, queijo);
        assertThat(q.plu()).isEqualTo(123);
        assertThat(q.codigoBarras()).as("pesável mostra o PLU, sem código de barras").isNull();
        Etiqueta f = etiquetas.produto(admin, fardo);
        assertThat(f.codigoBarras()).as("GTIN-14 não vai desenhado").isNull();
        assertThat(f.conteudo().descricao()).isEqualTo("500 ml");
        Etiqueta b = etiquetas.produto(admin, biscoito);
        assertThat(b.precoPorMedida()).as("aceite: 500 g a R$ 8,99 → R$ 17,98 o kg").isEqualByComparingTo("17.98");
        assertThat(etiquetas.produto(admin, cafe).codigoBarras()).isEqualTo("7891000315507");
        assertThat(etiquetas.produto(admin, acucar).codigoBarras()).isEqualTo("12345670");
    }

    @Test
    void conteudoLiquidoNoCadastroDoProduto() throws Exception {
        long id = t.produtos.criar(admin, new DadosProduto("Leite integral 1 L", null, "UN", v("5.49"), v("3"),
                null, null, List.of(), null, null, null, new ConteudoLiquido(v("1"), UnidadeConteudo.L)));
        assertThat(t.produtos.buscar(admin, id).conteudo().descricao()).isEqualTo("1 L");
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_CRIADO).get(6)).contains("conteúdo líquido 1 L");

        Produto p = t.produtos.buscar(admin, id);
        // Sem informar o conteúdo (construtor antigo, como o gerador e a importação), ele não muda.
        t.produtos.alterar(admin, id, new DadosProduto(p.nome(), null, "UN", v("5.29"), p.custo(), null, null,
                List.of(), null));
        assertThat(t.produtos.buscar(admin, id).conteudo().descricao()).isEqualTo("1 L");
        t.produtos.alterar(admin, id, new DadosProduto(p.nome(), null, "UN", v("5.29"), p.custo(), null, null,
                List.of(), null, null, null, new ConteudoLiquido(v("900"), UnidadeConteudo.ML)));
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_ALTERADO)).last().asString()
                .contains("conteúdo líquido alterado de 1 L para 900 ml");
        t.produtos.alterar(admin, id, new DadosProduto(p.nome(), null, "UN", v("5.29"), p.custo(), null, null,
                List.of(), null, null, null, ConteudoLiquido.SEM));
        assertThat(t.produtos.buscar(admin, id).conteudo().vazio()).isTrue();

        assertThatThrownBy(() -> t.produtos.alterar(admin, id, new DadosProduto(p.nome(), null, "UN", v("5.29"),
                p.custo(), null, null, List.of(), null, null, null, new ConteudoLiquido(v("500"), null))))
                .hasMessageContaining("unidade do conteúdo");
        assertThatThrownBy(() -> t.produtos.alterar(admin, id, new DadosProduto(p.nome(), null, "UN", v("5.29"),
                p.custo(), null, null, List.of(), null, null, null, new ConteudoLiquido(v("0"), UnidadeConteudo.G))))
                .hasMessageContaining("maior que zero");
        assertThatThrownBy(() -> t.produtos.alterar(admin, id, new DadosProduto(p.nome(), null, "UN", v("5.29"),
                p.custo(), null, null, List.of(), null, null, null,
                new ConteudoLiquido(v("1.2345"), UnidadeConteudo.G)))).hasMessageContaining("3 casas");
    }

    @Test
    void unidadesDoConteudoIguaisAoCheckEOrigensIguaisAoCheck() throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                     + "WHERE conname IN ('ck_produto_conteudo', 'ck_ei_origem') ORDER BY conname")) {
            rs.next();
            String origem = rs.getString(2);
            for (OrigemEtiqueta o : OrigemEtiqueta.values()) {
                assertThat(origem).contains("'" + o.name() + "'");
            }
            assertThat(origem.split("'").length / 2).isEqualTo(OrigemEtiqueta.values().length);
            rs.next();
            String conteudo = rs.getString(2);
            for (UnidadeConteudo u : UnidadeConteudo.values()) {
                assertThat(conteudo).contains("'" + u.sigla() + "'");
            }
            assertThat(Arrays.stream(UnidadeConteudo.values()).map(UnidadeConteudo::sigla))
                    .containsExactly("g", "kg", "ml", "L");
        }
    }

    @Test
    void configuracaoEPermissao() {
        assertThat(etiquetas.configuracao(admin).porFolha()).isEqualTo(24);
        assertThatThrownBy(() -> etiquetas.registrarImpressao(admin, List.of(), OrigemEtiqueta.PDF))
                .isInstanceOf(NegocioException.class);
        t.usuarios.criar(admin, "caixa1", "Caixa", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao caixa = t.acesso.entrar("caixa1", "inicial1");
        assertThatThrownBy(() -> etiquetas.pendentes(caixa)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> etiquetas.marcarEmDia(caixa, List.of(cafe)))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> etiquetas.registrarImpressao(caixa, etiquetas.pendentes(admin), OrigemEtiqueta.PDF))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> etiquetas.configuracao(caixa)).isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void produtoExcluidoLevaAsImpressoes() throws Exception {
        long novo = produto("Produto sem vínculo", "3.00", null, null);
        etiquetas.registrarImpressao(admin, List.of(etiquetas.produto(admin, novo)), OrigemEtiqueta.PDF);
        t.produtos.excluir(admin, novo);
        assertThat(t.contar("SELECT count(*) FROM etiqueta_impressao WHERE produto_id = " + novo)).isZero();
    }
}
