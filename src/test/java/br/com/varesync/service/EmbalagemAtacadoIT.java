package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelRentabilidadeProduto;
import br.com.varesync.report.RelVendasPeriodo;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Embalagens de venda e preço de atacado (F20.2), com os critérios de aceite da F20 que dependem da parte A: embalagem
 * com preço próprio (e com a promoção), faixa por quantidade (e com a promoção), devolução pelo preço pago, NF-e pelo
 * EAN da caixa e a consistência da venda (CHECKs, R01 = R05). As views de consistência do estoque são conferidas ao
 * fim de cada teste ({@code IntegracaoBase}).
 */
class EmbalagemAtacadoIT extends IntegracaoBase {

    private static final String UNIDADE = "7891000315507";
    private static final String CAIXA = "17891000315504";
    private static final String FARDO_SEM_PRECO = "7896006716112";

    private AcessoTeste t;
    private VendaService vendas;
    private Sessao admin;
    private long refri;
    private long faixaSeis;
    private FormaPagamento dinheiro;

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        vendas = t.vendas;
        admin = t.supervisor();
        refri = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 2 L", null, "UN", v("5.49"), v("3.20"),
                null, null, List.of(new CodigoBarras(UNIDADE, true), new CodigoBarras(CAIXA, false),
                new CodigoBarras(FARDO_SEM_PRECO, false)), v("100")));
        // O cadastro das embalagens e das faixas é da parte B (F20.3): aqui, direto no banco.
        executar("UPDATE produto_codigo_barras SET quantidade = 12, preco_embalagem = 59.90 WHERE codigo = ?", CAIXA);
        executar("UPDATE produto_codigo_barras SET quantidade = 6 WHERE codigo = ?", FARDO_SEM_PRECO);
        dinheiro = vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow();
        t.caixa.abrir(admin, v("100.00"));
    }

    private void executar(String sql, Object... parametros) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            ps.executeUpdate();
        }
    }

    private long criarFaixa(String quantidade, String preco) throws SQLException {
        executar("INSERT INTO produto_preco_quantidade (produto_id, quantidade_minima, preco) VALUES (?, ?, ?)",
                refri, v(quantidade), v(preco));
        return t.contar("SELECT max(id) FROM produto_preco_quantidade");
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private void promocao() {
        t.promocoes.criar(admin, new DadosPromocao(refri, v("4.79"), hoje(), hoje().plusDays(2), true));
    }

    /** Lê o código como no PDV: {@code multiplicador*código} ou só o código. */
    private VendaService.Inclusao ler(Carrinho carrinho, String digitado) {
        EntradaPdv lida = EntradaPdv.interpretar(digitado);
        VendaService.LeituraProduto leitura = vendas.lerProduto(admin, lida.codigo());
        if (leitura.embalagem() != null) {
            return vendas.adicionarEmbalagem(admin, carrinho, leitura, lida.quantidade());
        }
        return vendas.adicionarItem(admin, carrinho, leitura.produto(),
                lida.quantidade() == null ? BigDecimal.ONE : lida.quantidade());
    }

    private VendaService.VendaFinalizada pagar(Carrinho carrinho) {
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return vendas.finalizar(admin, carrinho, r);
    }

    private BigDecimal saldo() {
        return t.estoque.saldo(admin, refri).saldo();
    }

    // ----- Embalagem -----

    @Test
    void caixaComPrecoProprioRegistra12PorR5990EBaixa12() throws Exception {
        Carrinho carrinho = new Carrinho();
        ItemCarrinho caixa = ler(carrinho, CAIXA).item();
        assertThat(caixa.quantidade()).isEqualByComparingTo("12");
        assertThat(caixa.valorBruto()).isEqualByComparingTo("59.90");
        assertThat(caixa.precoUnitario()).isEqualByComparingTo("4.99"); // 59,90 ÷ 12 = 4,9917
        assertThat(caixa.promocaoId()).isNull();
        assertThat(caixa.participaFaixa()).isFalse();

        ItemCarrinho duas = ler(carrinho, "2*" + CAIXA).item();
        assertThat(duas.quantidade()).isEqualByComparingTo("24");
        assertThat(duas.valorBruto()).isEqualByComparingTo("119.80");

        long venda = pagar(carrinho).vendaId();
        assertThat(saldo()).as("100 − 12 − 24").isEqualByComparingTo("64");
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'VENDA' AND quantidade = -12"))
                .isEqualTo(1);
        assertThat(t.contar("SELECT valor_bruto * 100 FROM venda WHERE id = " + venda)).isEqualTo(17970);
        consistencia();
    }

    @Test
    void comAPromocaoACaixaSaiPelasUnidadesQuandoForMaisBarato() {
        promocao();
        Carrinho carrinho = new Carrinho();
        ItemCarrinho caixa = ler(carrinho, CAIXA).item();
        assertThat(caixa.valorBruto()).as("12 × 4,79 < 59,90").isEqualByComparingTo("57.48");
        assertThat(caixa.precoUnitario()).isEqualByComparingTo("4.79");
        assertThat(caixa.promocaoId()).isNotNull();
    }

    @Test
    void multiplicadorDaEmbalagemTemDeSerInteiro() {
        Carrinho carrinho = new Carrinho();
        assertThatThrownBy(() -> ler(carrinho, "1,5*" + CAIXA)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("número inteiro de embalagens");
        assertThat(carrinho.vazio()).isTrue();
    }

    @Test
    void embalagemSemPrecoEntraComoUnidadesEParticipaDaFaixa() throws Exception {
        long faixa = criarFaixa("6", "4.99");
        Carrinho carrinho = new Carrinho();
        ItemCarrinho fardo = ler(carrinho, FARDO_SEM_PRECO).item();
        assertThat(fardo.quantidade()).isEqualByComparingTo("6");
        assertThat(fardo.faixa().id()).isEqualTo(faixa);
        assertThat(fardo.valorBruto()).isEqualByComparingTo("29.94");
        pagar(carrinho);
        assertThat(saldo()).isEqualByComparingTo("94");
        assertThat(t.contar("SELECT preco_quantidade_id FROM venda_item")).isEqualTo(faixa);
    }

    @Test
    void caixaComPrecoProprioNaoContaParaAFaixa() throws Exception {
        criarFaixa("6", "4.99");
        Carrinho carrinho = new Carrinho();
        ler(carrinho, CAIXA);
        ler(carrinho, "5*" + UNIDADE);
        assertThat(carrinho.itens().get(1).faixa()).isNull();
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("87.35"); // 59,90 + 27,45
    }

    // ----- Faixa por quantidade -----

    @Test
    void faixaDoAceiteNaSextaUnidadeECancelarUmaVolta() throws Exception {
        faixaSeis = criarFaixa("6", "4.99");
        Carrinho carrinho = new Carrinho();
        for (int i = 0; i < 5; i++) {
            ler(carrinho, UNIDADE);
        }
        assertThat(carrinho.total()).isEqualByComparingTo("27.45");
        ler(carrinho, UNIDADE);
        assertThat(carrinho.total()).isEqualByComparingTo("29.94");
        assertThat(carrinho.itens()).allSatisfy(i -> assertThat(i.precoUnitario()).isEqualByComparingTo("4.99"));

        assertThat(vendas.cancelarItem(admin, carrinho, 6, null)).isNull();
        assertThat(carrinho.total()).isEqualByComparingTo("27.45");
        assertThat(carrinho.itens().subList(0, 5)).allSatisfy(i -> assertThat(i.precoUnitario())
                .isEqualByComparingTo("5.49"));

        ler(carrinho, UNIDADE);
        long venda = pagar(carrinho).vendaId();
        assertThat(t.contar("SELECT count(*) FROM venda_item WHERE NOT cancelado AND preco_quantidade_id = "
                + faixaSeis)).isEqualTo(6);
        assertThat(t.contar("SELECT valor_total * 100 FROM venda WHERE id = " + venda)).isEqualTo(2994);
        // O comprovante mostra a faixa nos itens no preço de atacado (o cancelado fica sem ela).
        String comprovante = vendas.comprovante(admin, venda).texto();
        assertThat(comprovante.split("Preço de atacado \\(a partir de 6 un\\)", -1)).hasSize(7);
        consistencia();
    }

    @Test
    void comAPromocaoAsSeisUnidadesSaemA479() throws Exception {
        criarFaixa("6", "4.99");
        promocao();
        Carrinho carrinho = new Carrinho();
        for (int i = 0; i < 6; i++) {
            ler(carrinho, UNIDADE);
        }
        assertThat(carrinho.total()).isEqualByComparingTo("28.74");
        pagar(carrinho);
        assertThat(t.contar("SELECT count(*) FROM venda_item WHERE preco_quantidade_id IS NULL "
                + "AND promocao_id IS NOT NULL")).isEqualTo(6);
    }

    @Test
    void valeOMenorEntreNormalPromocaoEFaixa() throws Exception {
        // Promoção acima do preço normal não vale; a faixa vale quando é a menor.
        t.promocoes.criar(admin, new DadosPromocao(refri, v("5.99"), hoje(), hoje().plusDays(2), true));
        criarFaixa("6", "4.99");
        Carrinho carrinho = new Carrinho();
        ItemCarrinho primeiro = ler(carrinho, UNIDADE).item();
        assertThat(primeiro.precoUnitario()).isEqualByComparingTo("5.49");
        assertThat(primeiro.promocaoId()).isNull();
        ler(carrinho, "5*" + UNIDADE);
        assertThat(carrinho.total()).isEqualByComparingTo("29.94");
    }

    @Test
    void descontoQueNaoCabeNoPrecoDaFaixaERetiradoComAviso() throws Exception {
        criarFaixa("6", "4.99");
        Carrinho carrinho = new Carrinho();
        ler(carrinho, UNIDADE);
        vendas.aplicarDescontoItem(admin, carrinho, 1, TipoDesconto.VALOR, v("5.00"), null);
        String aviso = ler(carrinho, "5*" + UNIDADE).aviso();
        assertThat(aviso).contains("desconto do item 1").contains("R$ 4,99");
        assertThat(carrinho.item(1).orElseThrow().desconto()).isEqualByComparingTo("0.00");
        assertThat(carrinho.total()).isEqualByComparingTo("29.94");
    }

    // ----- Devolução -----

    @Test
    void devolverUmaDasSeisCompradasNaFaixaDevolve499() throws Exception {
        criarFaixa("6", "4.99");
        Carrinho carrinho = new Carrinho();
        for (int i = 0; i < 6; i++) {
            ler(carrinho, UNIDADE);
        }
        long venda = pagar(carrinho).vendaId();
        ItemDevolucao primeiro = t.devolucoes.venda(admin, venda).itens().get(0);
        t.relogio.avancar(Duration.ofMinutes(1));
        DevolucaoService.DevolucaoRegistrada d = t.devolucoes.registrar(admin, new DadosDevolucao(venda,
                List.of(new DadosDevolucao.Item(primeiro.vendaItemId(), v("1"), false)), "Cliente desistiu",
                FormaReembolso.DINHEIRO, null, null, t.contaDoReembolso(FormaReembolso.DINHEIRO)), null);
        assertThat(d.valor()).isEqualByComparingTo("4.99");
        assertThat(saldo()).isEqualByComparingTo("95");
    }

    @Test
    void devolverUmaDeUmItemComSeisNaFaixaDevolve499() throws Exception {
        criarFaixa("6", "4.99");
        Carrinho carrinho = new Carrinho();
        ler(carrinho, "6*" + UNIDADE);
        long venda = pagar(carrinho).vendaId();
        ItemDevolucao item = t.devolucoes.venda(admin, venda).itens().get(0);
        t.relogio.avancar(Duration.ofMinutes(1));
        DevolucaoService.DevolucaoRegistrada d = t.devolucoes.registrar(admin, new DadosDevolucao(venda,
                List.of(new DadosDevolucao.Item(item.vendaItemId(), v("1"), false)), "Cliente desistiu",
                FormaReembolso.DINHEIRO, null, null, t.contaDoReembolso(FormaReembolso.DINHEIRO)), null);
        assertThat(d.valor()).as("29,94 ÷ 6, sem recalcular a faixa para as 5 que ficaram").isEqualByComparingTo("4.99");
    }

    // ----- NF-e -----

    @Test
    void notaComCincoCaixasPeloEanDaCaixaEntraComo60UnidadesComOCustoDaCaixaDivididoPor12() {
        byte[] xml = LeitorNfeTest.recurso("nfe-embalagem.xml");
        ImportacaoNfeService.Previa previa = t.nfe.ler(admin, xml);
        ImportacaoNfeService.ItemPrevia item = previa.itens().get(0);
        assertThat(item.produto().id()).isEqualTo(refri);
        assertThat(item.embalagem()).isTrue();
        assertThat(item.peloFornecedor()).isFalse();
        assertThat(item.fator()).isEqualByComparingTo("12");

        long entradaId = t.nfe.gerarEntrada(admin, xml, Map.of());
        ItemEntrada linha = t.compras.itens(admin, entradaId).get(0);
        assertThat(linha.quantidade()).isEqualByComparingTo("60");
        assertThat(linha.custoUnitario()).isEqualByComparingTo("3.2500"); // 39,00 ÷ 12
        assertThat(linha.valorTotal()).isEqualByComparingTo("195.00");
        t.compras.confirmar(admin, entradaId);
        assertThat(saldo()).isEqualByComparingTo("160");
    }

    @Test
    void notaPeloEanDaUnidadeNaoConverte() {
        String xml = new String(LeitorNfeTest.recurso("nfe-embalagem.xml"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("<cEAN>" + CAIXA + "</cEAN>", "<cEAN>" + UNIDADE + "</cEAN>");
        ImportacaoNfeService.ItemPrevia item = t.nfe.ler(admin, xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .itens().get(0);
        assertThat(item.embalagem()).isFalse();
        assertThat(item.fator()).isNull();
    }

    // ----- Cadastro de hoje -----

    @Test
    void salvarOProdutoNoCadastroMantemAsEmbalagens() throws Exception {
        var produto = t.produtos.buscar(admin, refri);
        t.relogio.avancar(Duration.ofMinutes(1));
        t.produtos.alterar(admin, refri, new DadosProduto(produto.nome(), null, "UN", v("5.59"), v("3.20"), null,
                null, produto.codigosBarras(), null));
        assertThat(t.contar("SELECT quantidade * 100 + coalesce(preco_embalagem, 0) * 100 FROM produto_codigo_barras "
                + "WHERE codigo = '" + CAIXA + "'")).as("12 un e R$ 59,90").isEqualTo(1200 + 5990);
        assertThatThrownBy(() -> executar("UPDATE produto_codigo_barras SET principal = (codigo = ?) "
                + "WHERE produto_id = ?", CAIXA, refri)).hasMessageContaining("ck_pcb_principal_unitario");
    }

    // ----- Consistência -----

    /** {@code ck_venda_total} e {@code ck_vi_liquido} valem (o banco recusaria) e o R01 bate com o R05 no dia. */
    private void consistencia() throws Exception {
        assertThat(t.contar("SELECT count(*) FROM venda WHERE valor_total <> valor_bruto - desconto_itens "
                + "- desconto_venda")).isZero();
        assertThat(t.contar("SELECT count(*) FROM venda_item WHERE valor_liquido <> valor_bruto - desconto "
                + "- desconto_rateado")).isZero();
        Filtros filtros = Filtros.vazios().comPeriodo(new Periodo(hoje(), hoje()));
        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(), filtros);
        ResultadoRelatorio<RelRentabilidadeProduto.Linha> r05 = t.relatorios.gerar(admin,
                new RelRentabilidadeProduto(), filtros);
        BigDecimal receita01 = r01.linhas().stream().map(RelVendasPeriodo.Linha::receita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal receita05 = r05.linhas().stream().map(RelRentabilidadeProduto.Linha::receita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lucro01 = r01.linhas().stream().map(RelVendasPeriodo.Linha::lucro)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lucro05 = r05.linhas().stream().map(RelRentabilidadeProduto.Linha::lucro)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receita01).isPositive().isEqualByComparingTo(receita05);
        assertThat(lucro01).isEqualByComparingTo(lucro05);
    }
}
