package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.util.Gtin;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Leitura de etiqueta de balança no PDV (6.6 item 3, 6.9): vetores de A.7 pela venda completa (item, movimento e
 * totais gravados), ordem de busca, PLU sem produto e balança desligada.
 */
class EtiquetaBalancaIT extends IntegracaoBase {

    private AcessoTeste t;
    private VendaService vendas;
    private Sessao admin;
    private long queijo;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        vendas = t.vendas;
        admin = t.supervisor();
        balanca(true, 5, TipoValorBalanca.PRECO);
        queijo = t.produtos.criar(admin, new DadosProduto("Queijo muçarela", null, "KG", v("39.90"), v("28.00"), null,
                123, List.of(), v("5")));
        t.caixa.abrir(admin, v("0"));
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private void balanca(boolean ativa, int digitos, TipoValorBalanca tipo) {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comBalanca(new Configuracoes.Balanca(ativa, 2, digitos, tipo)));
    }

    /** O que o PDV faz com uma etiqueta: lê e inclui pela etiqueta. */
    private ItemCarrinho lerEtiqueta(Carrinho carrinho, String codigo) {
        VendaService.LeituraProduto leitura = vendas.lerProduto(admin, codigo);
        assertThat(leitura.etiqueta()).as("etiqueta reconhecida").isNotNull();
        return vendas.adicionarEtiqueta(admin, carrinho, leitura).item();
    }

    private long finalizar(Carrinho carrinho) {
        Recebimento r = new Recebimento(carrinho.total());
        r.adicionar(vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), carrinho.total());
        return vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private List<String> gravado(long vendaId) throws Exception {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement("""
                     SELECT vi.quantidade, vi.preco_unitario, vi.valor_bruto, me.quantidade, v.valor_total
                       FROM venda_item vi
                       JOIN venda v ON v.id = vi.venda_id
                       JOIN movimento_estoque me ON me.venda_item_id = vi.id
                      WHERE vi.venda_id = ?
                     """)) {
            ps.setLong(1, vendaId);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            return List.of(rs.getBigDecimal(1).toPlainString(), rs.getBigDecimal(2).toPlainString(),
                    rs.getBigDecimal(3).toPlainString(), rs.getBigDecimal(4).toPlainString(),
                    rs.getBigDecimal(5).toPlainString());
        }
    }

    @Test
    void precoComCincoDigitosA7PelaVendaCompleta() throws Exception {
        Carrinho carrinho = new Carrinho();
        ItemCarrinho item = lerEtiqueta(carrinho, "2001230013776");
        assertThat(item.produto().id()).isEqualTo(queijo);
        assertThat(item.produto().codigo()).isEqualTo("2001230013776"); // a etiqueta aparece como código do item
        assertThat(item.quantidade()).isEqualByComparingTo("0.345");
        assertThat(item.valorBruto()).isEqualByComparingTo("13.77");
        // item, preço por kg, valor da etiqueta, baixa no estoque e total da venda
        assertThat(gravado(finalizar(carrinho))).containsExactly("0.345", "39.90", "13.77", "-0.345", "13.77");
    }

    @Test
    void pesoComCincoDigitosA7() throws Exception {
        balanca(true, 5, TipoValorBalanca.PESO);
        Carrinho carrinho = new Carrinho();
        ItemCarrinho item = lerEtiqueta(carrinho, "2001230003456");
        assertThat(item.quantidade()).isEqualByComparingTo("0.345");
        assertThat(item.valorBruto()).isEqualByComparingTo("13.77"); // 13,7655 arredondado
        assertThat(gravado(finalizar(carrinho))).containsExactly("0.345", "39.90", "13.77", "-0.345", "13.77");
    }

    @Test
    void precoComQuatroDigitosA7() {
        balanca(true, 4, TipoValorBalanca.PRECO);
        ItemCarrinho item = lerEtiqueta(new Carrinho(), "2012300013770");
        assertThat(item.produto().id()).isEqualTo(queijo);
        assertThat(item.valorBruto()).isEqualByComparingTo("13.77");
    }

    @Test
    void valorDaEtiquetaNaoERecalculadoA7() throws Exception {
        long carne = t.produtos.criar(admin, new DadosProduto("Carne moída", null, "KG", v("29.99"), v("18.00"), null,
                124, List.of(), v("5")));
        Carrinho carrinho = new Carrinho();
        ItemCarrinho item = lerEtiqueta(carrinho, "200124" + "001000" + Gtin.digitoVerificador("200124001000"));
        assertThat(item.produto().id()).isEqualTo(carne);
        assertThat(item.quantidade()).isEqualByComparingTo("0.333");
        assertThat(item.valorBruto()).isEqualByComparingTo("10.00"); // 0,333 × 29,99 daria 9,99
        assertThat(gravado(finalizar(carrinho))).containsExactly("0.333", "29.99", "10.00", "-0.333", "10.00");
    }

    @Test
    void etiquetaUsaOPrecoDaPromocaoVigente() {
        balanca(true, 5, TipoValorBalanca.PESO);
        long promocao = t.promocoes.criar(admin, new DadosPromocao(queijo, v("35.90"), t.promocoes.hoje(),
                t.promocoes.hoje(), true));
        ItemCarrinho item = lerEtiqueta(new Carrinho(), "2001230003456");
        assertThat(item.precoUnitario()).isEqualByComparingTo("35.90");
        assertThat(item.promocaoId()).isEqualTo(promocao);
        assertThat(item.valorBruto()).isEqualByComparingTo("12.39"); // 0,345 × 35,90 = 12,3855
    }

    @Test
    void pluSemProdutoBalancaDesligadaEOrdemDeBusca() {
        String semProduto = "200999001377" + Gtin.digitoVerificador("200999001377");
        assertThatThrownBy(() -> vendas.lerProduto(admin, semProduto))
                .hasMessage("Produto de balança não encontrado (PLU 999).");
        assertThatThrownBy(() -> vendas.lerProduto(admin, "2001230013777")) // DV errado: não é etiqueta
                .hasMessage("Produto não encontrado (código 2001230013777).");

        // Um código de barras cadastrado vence a etiqueta (ordem de 6.6).
        long brinde = t.produtos.criar(admin, new DadosProduto("Brinde pesado", null, "UN", v("1.00"), v("0.50"),
                null, null, List.of(new CodigoBarras("2001230013776", true)), null));
        VendaService.LeituraProduto leitura = vendas.lerProduto(admin, "2001230013776");
        assertThat(leitura.produto().id()).isEqualTo(brinde);
        assertThat(leitura.etiqueta()).isNull();

        balanca(false, 5, TipoValorBalanca.PRECO);
        assertThatThrownBy(() -> vendas.lerProduto(admin, "2001230003456"))
                .hasMessage("Produto não encontrado (código 2001230003456).");
    }

    @Test
    void semSaldoEComEstoqueNegativoBloqueadoEtiquetaERecusada() {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comVenda(new br.com.varesync.model.ParametrosVenda(false,
                c.venda().descontoMaximoPct(), false)));
        // 5 kg de saldo; etiqueta de R$ 239,40 = 6 kg
        String seisQuilos = "200123023940" + Gtin.digitoVerificador("200123023940");
        assertThatThrownBy(() -> lerEtiqueta(new Carrinho(), seisQuilos))
                .hasMessageContaining("Estoque insuficiente de \"Queijo muçarela\"");
    }
}
