package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosVinculo;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.RelCoberturaRuptura;
import br.com.varesync.report.RelSugestaoCompra;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.SugestaoCompra;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sugestão de compra com o banco (F16.2): venda líquida de devoluções pela {@code vw_resultado_item}, fornecedor
 * principal com prazo e fator, estoque mínimo, filtros, a aba de Compras, o R26 e o R28. Com {@code compras.dias_media}
 * de 3 dias, 30 unidades vendidas hoje dão a média de 10 por dia do aceite.
 */
class SugestaoCompraIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private FormaPagamento dinheiro;
    private long laticinios;
    private long frios;
    private long fornecedor;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        t.configuracoes.salvar(admin, t.configuracoes.carregar(admin).comCompras(new Configuracoes.Compras(3, 15)));
        laticinios = t.categorias.criar(admin, "Laticínios");
        frios = t.categorias.criar(admin, "Frios");
        fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Laticínios Vale do Pardo Ltda", null,
                "AB12CD34EFGH83", null, null, null, null, null, null));
        dinheiro = t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow();
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    private long produto(String nome, Long categoria, String codigo, String minimo, String inicial) {
        return t.produtos.criar(admin, new DadosProduto(nome, categoria, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.20"), minimo == null ? null : new BigDecimal(minimo), null,
                List.of(new CodigoBarras(codigo, true)), new BigDecimal(inicial)));
    }

    /** Fornecedor principal com prazo de 3 dias e caixa de 12. */
    private void caixaDe12(long produto) {
        t.vinculos.salvar(admin, produto, null, new DadosVinculo(fornecedor, null, "CX", new BigDecimal("12"), 3,
                true));
    }

    private long vender(long produto, String quantidade) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), new BigDecimal(quantidade));
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private SugestaoCompra.Linha linha(long produto) {
        return t.sugestao.consultar(admin, null, null).linhas().stream().filter(l -> l.produtoId() == produto)
                .findFirst().orElseThrow();
    }

    /** Aceite F16 (sugestão básica): média 10, cobertura 15, prazo 3, saldo 40, nada pendente, fator 12 → 144. */
    @Test
    void sugestaoBasicaDoAceite() {
        long leite = produto("Leite integral 1 L", laticinios, "7891000315507", null, "70");
        caixaDe12(leite);
        vender(leite, "30");

        SugestaoCompra.Linha l = linha(leite);
        assertThat(l.saldo()).isEqualByComparingTo("40");
        assertThat(l.mediaDia()).isEqualByComparingTo("10");
        assertThat(l.coberturaDias()).isEqualTo(4L);
        assertThat(l.pendente()).isEqualByComparingTo("0");
        assertThat(l.prazoEntregaDias()).isEqualTo(3);
        assertThat(l.sugestao()).isEqualByComparingTo("144");
        assertThat(l.sugestaoNaUnidadeDeCompra()).isEqualByComparingTo("12");
        assertThat(l.unidadeCompra()).isEqualTo("CX");
        assertThat(l.fornecedor()).isEqualTo("Laticínios Vale do Pardo Ltda");
        // Sem entrada do fornecedor, o último custo é o custo do produto.
        assertThat(l.ultimoCusto()).isEqualByComparingTo("3.20");
        assertThat(l.valorEstimado()).isEqualByComparingTo("460.80");
        assertThat(l.ruptura()).isFalse();
        assertThat(t.sugestao.consultar(admin, null, null).parametros()).isEqualTo(new Configuracoes.Compras(3, 15));
    }

    /** Aceite F16 (estoque mínimo): o mesmo produto com estoque mínimo de 200 → 168. */
    @Test
    void estoqueMinimoDoAceite() {
        long leite = produto("Leite integral 1 L", laticinios, "7891000315507", "200", "70");
        caixaDe12(leite);
        vender(leite, "30");
        assertThat(linha(leite).sugestao()).isEqualByComparingTo("168");
    }

    /** A devolução sai da média: 30 vendidos − 6 devolvidos = 24 em 3 dias (8 por dia); saldo 46 → 108. */
    @Test
    void devolucaoReduzAMedia() {
        long leite = produto("Leite integral 1 L", laticinios, "7891000315507", null, "70");
        caixaDe12(leite);
        long venda = vender(leite, "30");
        t.relogio.avancar(Duration.ofMinutes(1));
        ItemDevolucao item = t.devolucoes.venda(admin, venda).itens().get(0);
        t.devolucoes.registrar(admin, new DadosDevolucao(venda, List.of(new DadosDevolucao.Item(item.vendaItemId(),
                new BigDecimal("6"), false)), "Cliente desistiu", FormaReembolso.DINHEIRO, null, null,
                t.contaDoReembolso(FormaReembolso.DINHEIRO)), null);

        SugestaoCompra.Linha l = linha(leite);
        assertThat(l.saldo()).isEqualByComparingTo("46");
        assertThat(l.mediaDia()).isEqualByComparingTo("8");
        // 24 × 18 ÷ 3 = 144; 144 − 46 = 98 → 9 caixas de 12.
        assertThat(l.sugestao()).isEqualByComparingTo("108");
    }

    @Test
    void semFornecedorPrincipalUsaPrazoZeroEFator1EOsFiltros() {
        long leite = produto("Leite integral 1 L", laticinios, "7891000315507", null, "70");
        caixaDe12(leite);
        long queijo = produto("Queijo prato 150 g", frios, "7896004000015", null, "10");
        vender(leite, "30");
        vender(queijo, "6");

        SugestaoCompra.Linha q = linha(queijo);
        assertThat(q.fornecedorId()).isNull();
        assertThat(q.prazoEntregaDias()).isZero();
        assertThat(q.fator()).isEqualByComparingTo("1");
        // Média 2 × 15 = 30 − saldo 4 = 26.
        assertThat(q.sugestao()).isEqualByComparingTo("26");

        assertThat(t.sugestao.consultar(admin, fornecedor, null).linhas()).extracting(SugestaoCompra.Linha::produtoId)
                .containsExactly(leite);
        assertThat(t.sugestao.consultar(admin, null, frios).linhas()).extracting(SugestaoCompra.Linha::produtoId)
                .containsExactly(queijo);
        assertThat(t.sugestao.fornecedores(admin)).extracting(Opcao::id).containsExactly(fornecedor);
    }

    /** R26 só com as sugestões; R28 com todos e a ruptura (saldo 0 com venda recente) primeiro. */
    @Test
    void relatoriosR26ER28() {
        long leite = produto("Leite integral 1 L", laticinios, "7891000315507", null, "70");
        caixaDe12(leite);
        long pao = produto("Pão de forma", null, "7896004000015", null, "5");
        long sobra = produto("Sal refinado 1 kg", null, "7891234567895", null, "100");
        vender(leite, "30");
        vender(pao, "5");

        ResultadoRelatorio<SugestaoCompra.Linha> r26 = t.relatorios.gerar(admin, new RelSugestaoCompra(),
                Filtros.vazios());
        assertThat(r26.linhas()).extracting(SugestaoCompra.Linha::produtoId).containsExactly(leite, pao);
        assertThat(r26.linhas().get(0).sugestao()).isEqualByComparingTo("144"); // compras.dias_media da tela vale aqui

        ResultadoRelatorio<SugestaoCompra.Linha> r28 = t.relatorios.gerar(admin, new RelCoberturaRuptura(),
                Filtros.vazios());
        assertThat(r28.linhas()).extracting(SugestaoCompra.Linha::produtoId).containsExactly(pao, leite, sobra);
        assertThat(r28.linhas().get(0).ruptura()).isTrue();
        assertThat(r28.linhas().get(2).coberturaDias()).isNull(); // sem venda na média
        assertThat(r28.colunas().get(r28.colunas().size() - 1).formatarTotal(r28.linhas())).isEqualTo("1 em ruptura");
    }

    @Test
    void precisaDaPermissaoDeCompras() {
        t.usuarios.criar(admin, "estoquista", "Estoquista", false, EnumSet.of(Permissao.ESTOQUE), "inicial1",
                "inicial1");
        Sessao estoquista = t.acesso.entrar("estoquista", "inicial1");
        assertThatThrownBy(() -> t.sugestao.consultar(estoquista, null, null))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
