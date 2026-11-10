package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.Etiqueta;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.ItemCargaBalanca;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.OrigemEtiqueta;
import br.com.varesync.model.Produto;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoValorBalanca;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fase 20, parte B (F20.3): o cadastro da embalagem e das faixas pelo service (regras, alteração no lugar e
 * auditoria), o F7, a linha de atacado e a pendência da etiqueta, e os ajustes da parte A — a mesma regra de preço no
 * PDV, no F7, na etiqueta e na carga de balança, a faixa copiada no item da venda (segunda via), a caixa na entrada
 * manual e o aviso no inventário. As views de consistência do estoque são conferidas ao fim de cada teste.
 */
class AtacadoCadastroIT extends IntegracaoBase {

    private static final String UNIDADE = "7891000315507";
    private static final String CAIXA = "17891000315504";

    private AcessoTeste t;
    private Sessao admin;
    private long refri;
    private FormaPagamento dinheiro;

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private static FaixaPreco faixa(String quantidade, String preco) {
        return new FaixaPreco(0, v(quantidade), v(preco));
    }

    private static List<CodigoBarras> codigos() {
        return List.of(new CodigoBarras(UNIDADE, true), new CodigoBarras(CAIXA, false, v("12"), v("59.90")));
    }

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        refri = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 2 L", null, "UN", v("5.49"), v("3.20"),
                null, null, codigos(), v("100"), null, null, null, null, List.of(faixa("6", "4.99"))));
        dinheiro = t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow();
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private void alterar(List<CodigoBarras> codigos, String preco, List<FaixaPreco> faixas) {
        Produto p = t.produtos.buscar(admin, refri);
        t.relogio.avancar(Duration.ofMinutes(1));
        t.produtos.alterar(admin, refri, new DadosProduto(p.nome(), null, "UN", v(preco), p.custo(), null, null,
                codigos, null, null, null, null, null, faixas));
    }

    private void executar(String sql, Object... parametros) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            ps.executeUpdate();
        }
    }

    private long venderSeisUnidades() {
        t.caixa.abrir(admin, v("100.00"));
        Carrinho carrinho = new Carrinho();
        ProdutoVenda produto = t.vendas.lerProduto(admin, UNIDADE).produto();
        t.vendas.adicionarItem(admin, carrinho, produto, v("6"));
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private Etiqueta etiqueta() {
        return t.etiquetas.produto(admin, refri);
    }

    private void imprimirEtiqueta() {
        t.relogio.avancar(Duration.ofMinutes(1));
        t.etiquetas.registrarImpressao(admin, List.of(etiqueta()), OrigemEtiqueta.PDF);
    }

    // ----- Cadastro -----

    @Test
    void cadastroGravaAEmbalagemEAFaixaQueOPdvUsa() throws Exception {
        assertThat(t.produtos.buscar(admin, refri).codigosBarras()).containsExactly(
                new CodigoBarras(UNIDADE, true), new CodigoBarras(CAIXA, false, v("12"), v("59.90")));
        assertThat(t.produtos.faixas(admin, refri)).singleElement()
                .satisfies(f -> assertThat(f.preco()).isEqualByComparingTo("4.99"));
        Carrinho carrinho = new Carrinho();
        VendaService.LeituraProduto caixa = t.vendas.lerProduto(admin, CAIXA);
        t.caixa.abrir(admin, v("100.00"));
        assertThat(t.vendas.adicionarEmbalagem(admin, carrinho, caixa, null).item().valorBruto())
                .isEqualByComparingTo("59.90");
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = 'PRODUTO_CRIADO' AND descricao LIKE "
                + "'%embalagens " + CAIXA + ": 12 a R$ 59,90%' AND descricao LIKE "
                + "'%preço por quantidade a partir de 6: R$ 4,99%'")).isEqualTo(1);
    }

    @Test
    void faixasAlteradasNoLugarMantemOId() throws Exception {
        long id = t.produtos.faixas(admin, refri).get(0).id();
        alterar(codigos(), "5.49", List.of(new FaixaPreco(id, v("10"), v("4.89")), faixa("6", "5.19")));
        List<FaixaPreco> depois = t.produtos.faixas(admin, refri);
        assertThat(depois).extracting(FaixaPreco::quantidadeMinima).containsExactly(v("6.000"), v("10.000"));
        assertThat(depois.get(1).id()).as("a faixa alterada continua com o mesmo id").isEqualTo(id);
        // Trocar as quantidades das duas faixas num só salvamento não esbarra na quantidade única.
        long outra = depois.get(0).id();
        alterar(codigos(), "5.49", List.of(new FaixaPreco(outra, v("10"), v("4.89")),
                new FaixaPreco(id, v("6"), v("5.19"))));
        assertThat(t.produtos.faixas(admin, refri)).extracting(FaixaPreco::id).containsExactly(id, outra);
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = 'PRODUTO_ALTERADO' AND descricao LIKE "
                + "'%preço por quantidade alterado de a partir de 6: R$ 4,99 para%'")).isEqualTo(1);
        // Sem a lista (nulo), as faixas ficam; com a lista vazia, saem.
        alterar(codigos(), "5.59", null);
        assertThat(t.produtos.faixas(admin, refri)).hasSize(2);
        alterar(codigos(), "5.59", List.of());
        assertThat(t.produtos.faixas(admin, refri)).isEmpty();
    }

    @Test
    void regrasDoCadastro() {
        assertThatThrownBy(() -> alterar(codigos(), "5.49", List.of(faixa("6", "5.49"))))
                .hasMessageContaining("deve ser menor que o preço de venda");
        assertThatThrownBy(() -> alterar(codigos(), "5.49", List.of(faixa("6", "4.99"), faixa("12", "5.09"))))
                .hasMessageContaining("deve ser menor que o da faixa a partir de 6");
        assertThatThrownBy(() -> alterar(codigos(), "4.90", null))
                .as("baixar o preço abaixo da faixa gravada").hasMessageContaining("deve ser menor que o preço de venda");
        assertThatThrownBy(() -> alterar(List.of(new CodigoBarras(CAIXA, true, v("12"), null),
                new CodigoBarras(UNIDADE, false)), "5.49", null)).hasMessageContaining("O código principal é o da unidade");
        assertThatThrownBy(() -> alterar(List.of(new CodigoBarras(UNIDADE, true),
                new CodigoBarras(CAIXA, false, v("12.5"), null)), "5.49", null))
                .hasMessageContaining("deve ser um número inteiro");
        assertThat(t.produtos.buscar(admin, refri).precoVenda()).isEqualByComparingTo("5.49");
    }

    // ----- Segunda via -----

    /** Aceite "Segunda via": o comprovante continua com "a partir de 6 un" depois de a faixa mudar ou sair. */
    @Test
    void segundaViaContinuaComAFaixaDaVenda() throws Exception {
        long venda = venderSeisUnidades();
        assertThat(t.contar("SELECT count(*) FROM venda_item WHERE atacado_a_partir_de = 6")).isEqualTo(1);
        long id = t.produtos.faixas(admin, refri).get(0).id();
        alterar(codigos(), "5.49", List.of(new FaixaPreco(id, v("10"), v("4.99"))));
        assertThat(t.vendas.comprovante(admin, venda).texto()).contains("Preço de atacado (a partir de 6 un)")
                .doesNotContain("a partir de 10");
        alterar(codigos(), "5.49", List.of());
        assertThat(t.contar("SELECT count(*) FROM venda_item WHERE preco_quantidade_id IS NULL "
                + "AND atacado_a_partir_de = 6")).isEqualTo(1);
        assertThat(t.vendas.comprovante(admin, venda).texto()).contains("Preço de atacado (a partir de 6 un)");
    }

    @Test
    void bancoRecusaItemComFaixaSemAQuantidadeCopiada() throws Exception {
        venderSeisUnidades();
        assertThatThrownBy(() -> executar("UPDATE venda_item SET atacado_a_partir_de = NULL"))
                .hasMessageContaining("ck_vi_atacado");
    }

    // ----- Mesmo preço -----

    /** Aceite "Mesmo preço": com a promoção acima do preço normal, o PDV, o F7, a etiqueta e a balança usam o normal. */
    @Test
    void promocaoAcimaDoPrecoNormalNaoValeEmLugarNenhum() throws Exception {
        t.promocoes.criar(admin, new DadosPromocao(refri, v("5.99"), hoje(), hoje().plusDays(2), true));
        t.caixa.abrir(admin, v("100.00"));
        ItemCarrinho item = t.vendas.adicionarItem(admin, new Carrinho(),
                t.vendas.lerProduto(admin, UNIDADE).produto(), BigDecimal.ONE).item();
        assertThat(item.precoUnitario()).isEqualByComparingTo("5.49");
        assertThat(item.promocaoId()).isNull();
        VendaService.ConsultaPreco f7 = t.vendas.consultarPreco(admin, UNIDADE);
        assertThat(f7.preco().valor()).isEqualByComparingTo("5.49");
        assertThat(f7.preco().promocional()).isFalse();
        Etiqueta e = etiqueta();
        assertThat(e.preco()).isEqualByComparingTo("5.49");
        assertThat(e.promocional()).isFalse();

        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comBalanca(new Configuracoes.Balanca(true, 2, 5, TipoValorBalanca.PRECO,
                "")));
        long queijo = t.produtos.criar(admin, new DadosProduto("Queijo muçarela", null, "KG", v("39.90"), v("25.00"),
                null, 123, List.of(), v("5")));
        t.promocoes.criar(admin, new DadosPromocao(queijo, v("42.90"), hoje(), hoje().plusDays(2), true));
        ItemCargaBalanca naBalanca = t.cargaBalanca.itens(admin).stream().filter(i -> i.produtoId() == queijo)
                .findFirst().orElseThrow();
        assertThat(naBalanca.preco()).isEqualByComparingTo("39.90");
        assertThat(naBalanca.emPromocao()).isFalse();
        // A promoção menor que o preço normal continua valendo na balança.
        t.promocoes.criar(admin, new DadosPromocao(queijo, v("35.90"), hoje().plusDays(3), hoje().plusDays(5), true));
        t.relogio.avancar(Duration.ofDays(3));
        assertThat(t.cargaBalanca.itens(admin).stream().filter(i -> i.produtoId() == queijo).findFirst()
                .orElseThrow().preco()).isEqualByComparingTo("35.90");
    }

    // ----- Etiqueta e F7 -----

    /** Aceite "Etiqueta": "A partir de 6 un: R$ 4,99"; mudar a faixa deixa a etiqueta pendente. */
    @Test
    void etiquetaComALinhaDeAtacadoEPendenteQuandoAFaixaMuda() throws Exception {
        Etiqueta e = etiqueta();
        assertThat(e.textoAtacado()).isEqualTo("A partir de 6 un: R$ 4,99");
        imprimirEtiqueta();
        assertThat(t.contar("SELECT count(*) FROM etiqueta_impressao WHERE atacado_quantidade = 6 "
                + "AND atacado_preco = 4.99")).isEqualTo(1);
        assertThat(etiqueta().pendente()).isFalse();
        assertThat(t.etiquetas.contarPendentes(admin)).isZero();

        long id = t.produtos.faixas(admin, refri).get(0).id();
        alterar(codigos(), "5.49", List.of(new FaixaPreco(id, v("6"), v("4.89"))));
        assertThat(etiqueta().pendente()).as("faixa mudada").isTrue();
        assertThat(t.etiquetas.contarPendentes(admin)).isEqualTo(1);
        imprimirEtiqueta();
        alterar(codigos(), "5.49", List.of());
        assertThat(etiqueta().pendente()).as("faixa tirada").isTrue();
        assertThat(etiqueta().temAtacado()).isFalse();
        imprimirEtiqueta();
        alterar(codigos(), "5.49", List.of(faixa("12", "4.79")));
        assertThat(etiqueta().pendente()).as("faixa criada").isTrue();
    }

    @Test
    void etiquetaMostraAFaixaDeMenorQuantidadeSoSeForMenorQueOPrecoDaEtiqueta() throws Exception {
        alterar(codigos(), "5.49", List.of(faixa("12", "4.59"), faixa("6", "4.99")));
        assertThat(etiqueta().textoAtacado()).isEqualTo("A partir de 6 un: R$ 4,99");
        t.promocoes.criar(admin, new DadosPromocao(refri, v("4.79"), hoje(), hoje().plusDays(2), true));
        Etiqueta promocional = etiqueta();
        assertThat(promocional.preco()).isEqualByComparingTo("4.79");
        assertThat(promocional.temAtacado()).as("R$ 4,99 não é menor que a promoção").isFalse();
    }

    @Test
    void consultaDePrecoComFaixasEEmbalagens() {
        assertThat(t.vendas.consultarPreco(admin, CAIXA).texto()).contains("R$ 5,49 / UN",
                "Código lido: embalagem com 12 un", "A partir de 6 un: R$ 4,99 / UN",
                "Embalagem com 12 un (" + CAIXA + "): R$ 59,90");
    }

    // ----- Entrada manual e inventário -----

    /** Aceite "Entrada manual": ler o código da caixa de 12 e informar 5 caixas a R$ 39,00 lança 60 UN a R$ 3,2500. */
    @Test
    void entradaManualPelaCaixaLancaEmUnidades() {
        long fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Teste Ltda", null, null, null,
                null, null, null, null, null));
        long entrada = t.compras.criar(admin, new DadosEntrada(fornecedor, "777", hoje(), null));
        EntradaService.LeituraEntrada lida = t.compras.lerCodigo(admin, CAIXA);
        assertThat(lida.produto().id()).isEqualTo(refri);
        assertThat(lida.embalagem()).isEqualByComparingTo("12");
        assertThat(t.compras.lerCodigo(admin, UNIDADE).ehEmbalagem()).isFalse();
        assertThat(t.compras.lerCodigo(admin, String.valueOf(refri)).ehEmbalagem()).isFalse();

        t.compras.adicionarItem(admin, entrada, refri, v("5"), v("39.00"), null, lida.embalagem());
        ItemEntrada item = t.compras.itens(admin, entrada).get(0);
        assertThat(item.quantidade()).isEqualByComparingTo("60");
        assertThat(item.custoUnitario()).isEqualTo(v("3.2500"));
        assertThat(item.valorTotal()).isEqualByComparingTo("195.00");
        t.relogio.avancar(Duration.ofMinutes(1));
        t.compras.confirmar(admin, entrada);
        assertThat(t.estoque.saldo(admin, refri).saldo()).isEqualByComparingTo("160");
    }

    @Test
    void inventarioPelaCaixaPosicionaEAvisaQueAContagemEEmUnidades() {
        long inventario = t.inventarios.criar(admin, "Bebidas");
        t.inventarios.incluirProduto(admin, inventario, refri);
        InventarioService.LeituraContagem caixa = t.inventarios.localizarLeitura(admin, inventario, CAIXA);
        assertThat(caixa.item().produtoId()).isEqualTo(refri);
        assertThat(caixa.aviso()).isEqualTo("O código lido é de uma embalagem com 12 UN: a contagem é em unidades "
                + "(UN). Conte as unidades soltas e as das embalagens (a embalagem tem 12).");
        assertThat(t.inventarios.localizarLeitura(admin, inventario, UNIDADE).aviso()).isNull();
        List<String> avisos = new ArrayList<>();
        avisos.add(t.inventarios.localizarLeitura(admin, inventario, String.valueOf(refri)).aviso());
        assertThat(avisos).containsOnlyNulls();
    }
}
