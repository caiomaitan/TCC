package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.HistoricoCliente;
import br.com.varesync.model.IndicadoresDia;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelClientes;
import br.com.varesync.report.RelCurvaAbc;
import br.com.varesync.report.RelFormasPagamento;
import br.com.varesync.report.RelMaisVendidos;
import br.com.varesync.report.RelRentabilidadeCategoria;
import br.com.varesync.report.RelRentabilidadeProduto;
import br.com.varesync.report.RelVendasHoraDia;
import br.com.varesync.report.RelVendasOperador;
import br.com.varesync.report.RelVendasPeriodo;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Resultado comercial com as devoluções (F13.3): {@code vw_resultado_item} nos relatórios de venda e lucro, no R18,
 * na tela do cliente e no painel (receita = vendas − devoluções, na data da devolução). Inclui os critérios de aceite
 * da F13 que dependem da parte B: o R01 do dia da devolução em dinheiro, R01 e R05 batendo e o R08 batendo com as
 * colunas "Vendas" e "Devoluções" do R01.
 */
class ResultadoComercialIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private long refri;
    private long arroz;
    private long maria;
    private FormaPagamento dinheiro;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        long bebidas = t.categorias.criar(admin, "Refrigerantes");
        refri = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 2 L", bebidas, "UN",
                new BigDecimal("5.49"), new BigDecimal("3.20"), null, null,
                List.of(new CodigoBarras("7891000315507", true)), new BigDecimal("100")));
        arroz = t.produtos.criar(admin, new DadosProduto("Arroz 5 kg", null, "UN", new BigDecimal("10.00"),
                new BigDecimal("6.00"), null, null, List.of(new CodigoBarras("7896006716112", true)),
                new BigDecimal("100")));
        maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        dinheiro = t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow();
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    /** Vende 3 refrigerantes e, se pedido, 1 arroz, em dinheiro, com o cliente (ou sem). */
    private long vender(Long cliente, boolean comArroz) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, refri), new BigDecimal("3"));
        if (comArroz) {
            t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, arroz), BigDecimal.ONE);
        }
        if (cliente != null) {
            t.vendas.identificarCliente(admin, carrinho, cliente);
        }
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    /** Devolve {@code quantidade} do item na posição {@code item} da venda. */
    private long devolver(long vendaId, int item, String quantidade, FormaReembolso forma) {
        t.relogio.avancar(Duration.ofMinutes(1));
        ItemDevolucao i = t.devolucoes.venda(admin, vendaId).itens().get(item);
        return t.devolucoes.registrar(admin, new DadosDevolucao(vendaId, List.of(new DadosDevolucao.Item(
                i.vendaItemId(), new BigDecimal(quantidade), false)), "Cliente desistiu", forma, null, null, t.contaDoReembolso(forma)), null).id();
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private Filtros periodo(LocalDate inicio, LocalDate fim) {
        return Filtros.vazios().comPeriodo(new Periodo(inicio, fim));
    }

    private Filtros hojeSo() {
        return periodo(hoje(), hoje());
    }

    @Test
    void aceiteR01DoDiaComDevolucaoEmDinheiro() {
        long venda = vender(null, false);
        long devolucao = devolver(venda, 0, "1", FormaReembolso.DINHEIRO);

        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(), hojeSo());
        assertThat(r01.linhas()).hasSize(1);
        RelVendasPeriodo.Linha dia = r01.linhas().get(0);
        assertThat(dia.vendido()).as("vendas").isEqualByComparingTo("16.47");
        assertThat(dia.devolvido()).as("devoluções").isEqualByComparingTo("5.49");
        assertThat(dia.receita()).as("receita líquida").isEqualByComparingTo("10.98");
        // O nº de vendas e o ticket médio só contam as vendas; CMV e lucro descontam a devolução (6,40 = 9,60 − 3,20).
        assertThat(dia.vendas()).isEqualTo(1);
        assertThat(dia.ticketMedio()).isEqualByComparingTo("16.47");
        assertThat(dia.cmv()).isEqualByComparingTo("6.40");
        assertThat(dia.lucro()).isEqualByComparingTo("4.58");
        assertThat(r01.totais()).containsExactly("Total (1 linha)", "1", "R$ 16,47", "R$ 5,49", "R$ 10,98",
                "R$ 0,00", "R$ 6,40", "R$ 4,58", "41,71%", "R$ 16,47");

        ResultadoRelatorio<RelVendasPeriodo.Linha> detalhe = t.relatorios.gerar(admin, new RelVendasPeriodo(),
                hojeSo().comDetalhe(true));
        assertThat(detalhe.linhas()).extracting(RelVendasPeriodo.Linha::vendaId).containsExactly(venda, venda);
        assertThat(detalhe.linhas()).extracting(RelVendasPeriodo.Linha::devolucaoId)
                .containsExactly(null, devolucao);
        assertThat(detalhe.linhas().get(1).operador()).isEqualTo(admin.usuario().nome());

        // R05 bate com o R01 (receita, lucro e margem agregada).
        ResultadoRelatorio<RelRentabilidadeProduto.Linha> r05 = t.relatorios.gerar(admin,
                new RelRentabilidadeProduto(), hojeSo());
        assertThat(r05.linhas()).singleElement().satisfies(l -> {
            assertThat(l.quantidade()).isEqualByComparingTo("2");
            assertThat(l.receita()).isEqualByComparingTo("10.98");
            assertThat(l.lucro()).isEqualByComparingTo("4.58");
        });
        assertThat(r05.totais().get(7)).isEqualTo(r01.totais().get(8));

        // R08: vendas por forma = coluna "Vendas" do R01; reembolsos = coluna "Devoluções".
        ResultadoRelatorio<RelFormasPagamento.Linha> r08 = t.relatorios.gerar(admin, new RelFormasPagamento(),
                hojeSo());
        assertThat(r08.linhas()).extracting(RelFormasPagamento.Linha::forma)
                .containsExactly("Dinheiro", "Reembolso: Dinheiro");
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::valor)).isEqualByComparingTo(dia.vendido());
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::reembolsos))
                .isEqualByComparingTo(dia.devolvido());

        // Painel: o faturamento é a receita líquida (bate com o R01); a devolução não conta como venda. O painel vai
        // até agora (exclusive), e o relógio do teste parou no instante da devolução.
        t.relogio.avancar(Duration.ofMinutes(1));
        IndicadoresDia painel = t.painel.carregar(admin).hoje();
        assertThat(painel.receita()).isEqualByComparingTo("10.98");
        assertThat(painel.lucro()).isEqualByComparingTo("4.58");
        assertThat(painel.vendas()).isEqualTo(1);
        assertThat(painel.ticketMedio()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("16.47"));
    }

    @Test
    void devolucaoEntraNaDataDaDevolucaoENoOperadorDaVenda() {
        LocalDate diaDaVenda = hoje();
        long venda = vender(null, true);
        t.relogio.avancar(Duration.ofDays(2));
        devolver(venda, 1, "1", FormaReembolso.OUTRO);
        LocalDate diaDaDevolucao = hoje();

        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(),
                periodo(diaDaVenda, diaDaDevolucao));
        assertThat(r01.linhas()).extracting(RelVendasPeriodo.Linha::dia).containsExactly(diaDaVenda, diaDaDevolucao);
        RelVendasPeriodo.Linha soDevolucao = r01.linhas().get(1);
        assertThat(soDevolucao.vendas()).isZero();
        assertThat(soDevolucao.receita()).isEqualByComparingTo("-10.00");
        assertThat(soDevolucao.lucro()).isEqualByComparingTo("-4.00");
        assertThat(soDevolucao.ticketMedio()).as("sem venda no dia").isNull();
        assertThat(Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::receita)).isEqualByComparingTo("16.47");

        // O dia da venda continua igual; o período só do dia da devolução tem a receita negativa.
        assertThat(t.relatorios.gerar(admin, new RelVendasPeriodo(), periodo(diaDaVenda, diaDaVenda)).linhas()
                .get(0).receita()).isEqualByComparingTo("26.47");
        assertThat(t.relatorios.gerar(admin, new RelRentabilidadeProduto(),
                periodo(diaDaDevolucao, diaDaDevolucao)).linhas()).singleElement()
                .satisfies(l -> assertThat(l.produto()).isEqualTo("Arroz 5 kg"))
                .satisfies(l -> assertThat(l.situacao()).isEqualTo(RelRentabilidadeProduto.NEGATIVA));

        // R09: a devolução fica com quem fez a venda; o nº de vendas e o ticket só contam as vendas.
        ResultadoRelatorio<RelVendasOperador.Linha> r09 = t.relatorios.gerar(admin, new RelVendasOperador(),
                periodo(diaDaVenda, diaDaDevolucao));
        assertThat(r09.linhas()).singleElement().satisfies(l -> {
            assertThat(l.vendas()).isEqualTo(1);
            assertThat(l.vendido()).isEqualByComparingTo("26.47");
            assertThat(l.devolvido()).isEqualByComparingTo("10.00");
            assertThat(l.receita()).isEqualByComparingTo("16.47");
            assertThat(l.ticketMedio()).isEqualByComparingTo("26.47");
        });

        // R10: a devolução desconta a receita da hora dela, sem contar como venda.
        ResultadoRelatorio<RelVendasHoraDia.Linha> r10 = t.relatorios.gerar(admin, new RelVendasHoraDia(),
                periodo(diaDaVenda, diaDaDevolucao));
        assertThat(Coluna.soma(r10.linhas(), RelVendasHoraDia.Linha::receitaTotal)).isEqualByComparingTo("16.47");
        assertThat(r10.linhas().stream().mapToLong(RelVendasHoraDia.Linha::vendasTotal).sum()).isEqualTo(1);

        // R08 no dia da devolução: nenhuma venda, só o reembolso fora do sistema.
        ResultadoRelatorio<RelFormasPagamento.Linha> r08 = t.relatorios.gerar(admin, new RelFormasPagamento(),
                periodo(diaDaDevolucao, diaDaDevolucao));
        assertThat(r08.linhas()).singleElement().satisfies(l -> {
            assertThat(l.forma()).isEqualTo("Reembolso: Outro");
            assertThat(l.devolucoes()).isEqualTo(1);
            assertThat(l.reembolsos()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void relatoriosDeProdutoECategoriaBatemComOR01() {
        long venda = vender(maria, true);
        vender(null, false);
        devolver(venda, 0, "1", FormaReembolso.CREDITO_CLIENTE);

        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(), hojeSo());
        BigDecimal receita = Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::receita);
        BigDecimal lucro = Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::lucro);
        assertThat(receita).as("26,47 + 16,47 − 5,49").isEqualByComparingTo("37.45");

        ResultadoRelatorio<RelMaisVendidos.Linha> r02 = t.relatorios.gerar(admin, new RelMaisVendidos(), hojeSo());
        assertThat(r02.linhas()).filteredOn(l -> l.produtoId() == refri).singleElement()
                .satisfies(l -> assertThat(l.quantidade()).as("6 vendidos − 1 devolvido").isEqualByComparingTo("5"));
        assertThat(Coluna.soma(r02.linhas(), RelMaisVendidos.Linha::receita)).isEqualByComparingTo(receita);

        ResultadoRelatorio<RelRentabilidadeCategoria.Linha> r06 = t.relatorios.gerar(admin,
                new RelRentabilidadeCategoria(), hojeSo());
        assertThat(Coluna.soma(r06.linhas(), RelRentabilidadeCategoria.Linha::lucro)).isEqualByComparingTo(lucro);
        assertThat(r06.totais().get(4)).isEqualTo(r01.totais().get(8));

        ResultadoRelatorio<RelCurvaAbc.Linha> r07 = t.relatorios.gerar(admin, new RelCurvaAbc(), hojeSo());
        assertThat(Coluna.soma(r07.linhas(), RelCurvaAbc.Linha::valor)).isEqualByComparingTo(receita);
    }

    @Test
    void clienteR18EHistoricoDescontamAsDevolucoes() {
        long venda = vender(maria, true);
        devolver(venda, 0, "1", FormaReembolso.CREDITO_CLIENTE);

        ResultadoRelatorio<RelClientes.Linha> r18 = t.relatorios.gerar(admin, new RelClientes(), hojeSo());
        assertThat(r18.linhas()).singleElement().satisfies(l -> {
            assertThat(l.compras()).isEqualTo(1);
            assertThat(l.comprado()).isEqualByComparingTo("26.47");
            assertThat(l.devolvido()).isEqualByComparingTo("5.49");
            assertThat(l.valor()).isEqualByComparingTo("20.98");
            assertThat(l.ticketMedio()).isEqualByComparingTo("26.47");
        });

        HistoricoCliente h = t.clientes.historico(admin, maria);
        assertThat(h.compras()).singleElement().satisfies(c -> {
            assertThat(c.vendaId()).isEqualTo(venda);
            assertThat(c.itens()).isEqualTo(2);
            assertThat(c.devolvido()).isEqualByComparingTo("5.49");
            assertThat(c.valor()).isEqualByComparingTo("20.98");
        });
        assertThat(h.totalGasto()).isEqualByComparingTo("20.98");
        assertThat(h.ticketMedio()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("26.47"));
        assertThat(h.produtos()).filteredOn(p -> p.produtoId() == refri).singleElement().satisfies(p -> {
            assertThat(p.quantidade()).isEqualByComparingTo("2");
            assertThat(p.valor()).isEqualByComparingTo("10.98");
        });

        // O arroz devolvido por inteiro sai dos produtos mais comprados; a compra continua no histórico.
        devolver(venda, 1, "1", FormaReembolso.CREDITO_CLIENTE);
        HistoricoCliente depois = t.clientes.historico(admin, maria);
        assertThat(depois.produtos()).extracting(HistoricoCliente.ProdutoComprado::produtoId).containsExactly(refri);
        assertThat(depois.totalGasto()).isEqualByComparingTo("10.98");
        assertThat(depois.compras()).hasSize(1);
    }
}
