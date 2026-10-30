package br.com.varesync.report;

import static br.com.varesync.report.DadosFicticios.d;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Definição de colunas, totais e fórmulas dos relatórios R01 a R05 (7.1, 7.4), sem banco. */
class RelatoriosTest {

    @Test
    void margemAgregadaESomaDeLucroSobreSomaDeReceitaA6() {
        ResultadoRelatorio<RelRentabilidadeProduto.Linha> r05 = DadosFicticios.r05();
        // A: receita 100, lucro 50 (50%); B: receita 1.000, lucro 100 (10%) → 150 ÷ 1.100 = 13,64%, não 30%.
        assertThat(r05.linhas().get(0).margem()).isEqualByComparingTo("50.00");
        assertThat(r05.linhas().get(1).margem()).isEqualByComparingTo("10.00");
        assertThat(r05.totais()).containsExactly("Total (2 linhas)", "", "", "", "R$ 1.100,00", "R$ 950,00",
                "R$ 150,00", "13,64%", "15,79%", "");
    }

    @Test
    void r01TotaisUsamAsFormulasDoAgregado() {
        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = DadosFicticios.r01(false);
        assertThat(r01.colunas()).extracting(Coluna::titulo).containsExactly("Dia", "Nº vendas", "Vendas",
                "Devoluções", "Receita líquida", "Descontos", "CMV", "Lucro", "Margem %", "Ticket médio");
        // Margem = 145 ÷ 1.090 (receita líquida); ticket médio = 1.100 ÷ 2 (só as vendas, F13.3).
        assertThat(r01.totais()).containsExactly("Total (2 linhas)", "2", "R$ 1.100,00", "R$ 10,00",
                "R$ 1.090,00", "R$ 2,50", "R$ 945,00", "R$ 145,00", "13,30%", "R$ 550,00");

        ResultadoRelatorio<RelVendasPeriodo.Linha> detalhe = DadosFicticios.r01(true);
        assertThat(detalhe.colunas()).extracting(Coluna::titulo).containsExactly("Venda nº", "Devolução nº",
                "Data/hora", "Operador", "Vendas", "Devoluções", "Receita líquida", "Descontos", "CMV", "Lucro",
                "Margem %");
        assertThat(detalhe.linhas()).hasSize(3);
    }

    @Test
    void r01AgrupaAsVendasEDevolucoesPorDia() {
        RelVendasPeriodo.Linha a = new RelVendasPeriodo.Linha(LocalDate.of(2026, 9, 1), 1L, null, null, "Maria", 1,
                d("10.00"), d("0.00"), d("1.00"), d("6.00"), d("4.00"));
        RelVendasPeriodo.Linha b = new RelVendasPeriodo.Linha(LocalDate.of(2026, 9, 1), 2L, null, null, "José", 1,
                d("30.00"), d("0.00"), d("0.00"), d("20.00"), d("10.00"));
        RelVendasPeriodo.Linha c = new RelVendasPeriodo.Linha(LocalDate.of(2026, 9, 3), 3L, null, null, "Maria", 1,
                d("5.00"), d("0.00"), d("0.00"), d("5.50"), d("-0.50"));
        RelVendasPeriodo.Linha devolucao = new RelVendasPeriodo.Linha(LocalDate.of(2026, 9, 3), 1L, 7L, null,
                "Maria", 0, d("0.00"), d("2.00"), d("0.00"), d("-1.00"), d("-1.00"));

        List<RelVendasPeriodo.Linha> dias = RelVendasPeriodo.porDia(List.of(a, b, c, devolucao));

        assertThat(dias).hasSize(2);
        assertThat(dias.get(0).vendaId()).isNull();
        assertThat(dias.get(0).vendas()).isEqualTo(2);
        assertThat(dias.get(0).receita()).isEqualByComparingTo("40.00");
        assertThat(dias.get(0).ticketMedio()).isEqualByComparingTo("20.00");
        assertThat(dias.get(0).margem()).isEqualByComparingTo("35.00");
        RelVendasPeriodo.Linha dia3 = dias.get(1);
        assertThat(dia3.vendaId()).isNull();
        assertThat(dia3.devolucaoId()).isNull();
        assertThat(dia3.vendas()).isEqualTo(1);
        assertThat(dia3.vendido()).isEqualByComparingTo("5.00");
        assertThat(dia3.devolvido()).isEqualByComparingTo("2.00");
        assertThat(dia3.receita()).isEqualByComparingTo("3.00");
        assertThat(dia3.lucro()).isEqualByComparingTo("-1.50");
        assertThat(dia3.margem()).isEqualByComparingTo("-50.00");
        assertThat(dia3.ticketMedio()).as("só sobre as vendas").isEqualByComparingTo("5.00");
    }

    @Test
    void r01DiaSoComDevolucaoNaoTemTicketMedio() {
        RelVendasPeriodo.Linha devolucao = new RelVendasPeriodo.Linha(LocalDate.of(2026, 9, 4), 1L, 8L, null,
                "Maria", 0, d("0.00"), d("5.49"), d("0.00"), d("-3.20"), d("-2.29"));
        RelVendasPeriodo.Linha dia = RelVendasPeriodo.porDia(List.of(devolucao)).get(0);
        assertThat(dia.vendas()).isZero();
        assertThat(dia.receita()).isEqualByComparingTo("-5.49");
        assertThat(dia.ticketMedio()).isNull();
    }

    @Test
    void r02OrdenaPeloCriterioLimitaAoTopNECalculaOPercentualSobreAReceitaTotal() {
        ResultadoRelatorio<RelMaisVendidos.Linha> r02 = DadosFicticios.r02();
        assertThat(r02.linhas()).extracting(RelMaisVendidos.Linha::posicao).containsExactly(1, 2);
        assertThat(r02.linhas()).extracting(RelMaisVendidos.Linha::produto)
                .containsExactly("Refrigerante cola PET 2 L", "Pão francês");
        // receita total dos 3 produtos = 684,93; 284,70 ÷ 684,93 = 41,57%
        assertThat(r02.linhas().get(0).percentualReceita()).isEqualByComparingTo("41.57");
        assertThat(r02.colunas().get(4).formatar(r02.linhas().get(1))).isEqualTo("12,345");
        assertThat(r02.colunas().get(4).formatar(r02.linhas().get(0))).isEqualTo("30");
        assertThat(r02.colunas().get(4).totalizavel()).as("quantidade de unidades diferentes não se soma").isFalse();

        List<RelMaisVendidos.Linha> porQuantidade = RelMaisVendidos.ranquear(List.of(
                r02.linhas().get(1), r02.linhas().get(0)), Criterio.QUANTIDADE, null);
        assertThat(porQuantidade).extracting(RelMaisVendidos.Linha::produto)
                .containsExactly("Refrigerante cola PET 2 L", "Pão francês");
    }

    @Test
    void r03TotalDoValorEmEstoqueContaSaldoNegativoComoZero() {
        ResultadoRelatorio<RelEstoqueAtual.Linha> r03 = DadosFicticios.r03();
        assertThat(RelEstoqueAtual.valorTotal(r03.linhas())).isEqualByComparingTo("93.20");
        assertThat(r03.totais().get(7)).isEqualTo("R$ 93,20");
        assertThat(r03.colunas().get(4).formatar(r03.linhas().get(1))).isEqualTo("-2,500");
        assertThat(r03.colunas().get(6).formatar(r03.linhas().get(0))).isEqualTo("R$ 3,6000");
    }

    @Test
    void r04ValorizaEntradasESaidasACustoESoSomaQuantidadeComUmProduto() {
        ResultadoRelatorio<RelMovimentacao.Linha> r04 = DadosFicticios.r04();
        assertThat(r04.linhas().get(0).entrada()).isEqualByComparingTo("72.00");
        assertThat(r04.linhas().get(0).saida()).isNull();
        assertThat(r04.linhas().get(1).saida()).isEqualByComparingTo("10.80");
        assertThat(r04.totais().get(6)).isEqualTo("R$ 72,00");
        assertThat(r04.totais().get(7)).isEqualTo("R$ 10,80");
        assertThat(r04.colunas().get(3).totalizavel()).isFalse();

        Filtros comProduto = Filtros.vazios().comProduto(new Opcao(1, "Açúcar refinado 1 kg"));
        Coluna<RelMovimentacao.Linha> quantidade = new RelMovimentacao().colunas(comProduto).get(3);
        assertThat(quantidade.formatarTotal(r04.linhas())).isEqualTo("17");
        assertThat(r04.linhas().get(1).tipo()).isEqualTo(TipoMovimento.VENDA);
    }

    @Test
    void r05SituacaoNegativaBaixaOuOk() {
        BigDecimal minima = BigDecimal.valueOf(15);
        assertThat(RelRentabilidadeProduto.situacao(d("-0.01"), d("10.00"), minima)).isEqualTo("Negativa");
        assertThat(RelRentabilidadeProduto.situacao(d("14.99"), d("100.00"), minima)).isEqualTo("Baixa");
        assertThat(RelRentabilidadeProduto.situacao(d("0.00"), d("100.00"), minima)).isEqualTo("Baixa");
        assertThat(RelRentabilidadeProduto.situacao(d("15.00"), d("100.00"), minima)).isEqualTo("OK");
        assertThat(RelRentabilidadeProduto.situacao(d("0.00"), d("0.00"), minima)).isEqualTo("OK");
    }

    @Test
    void valorSemDivisorApareceComoTraco() {
        RelRentabilidadeProduto.Linha semReceita = new RelRentabilidadeProduto.Linha(1, "Brinde", null, "UN", false,
                d("1"), d("0.00"), d("0.00"), d("0.00"), "OK");
        Coluna<RelRentabilidadeProduto.Linha> margem = new RelRentabilidadeProduto().colunas(Filtros.vazios()).get(7);
        assertThat(margem.formatar(semReceita)).isEqualTo("—");
        assertThat(margem.csv(semReceita)).isEmpty();
    }

    @Test
    void colunasOrdenamPeloValorENaoPeloTexto() {
        Coluna<RelVendasPeriodo.Linha> receita = DadosFicticios.r01(false).colunas().get(2);
        assertThat(receita.comparador().compare(d("9.00"), d("10.00"))).isNegative();
        assertThat(receita.comparador().compare(null, d("10.00"))).isNegative();
        Coluna<RelVendasPeriodo.Linha> vendas = DadosFicticios.r01(false).colunas().get(1);
        assertThat(vendas.comparador().compare(9L, 10L)).isNegative();
    }

    @Test
    void filtrosDescritosNoCabecalho() {
        assertThat(DadosFicticios.r05().descricaoFiltros())
                .isEqualTo("Período: 01/09/2026 a 30/09/2026 · Categoria: Bebidas");
        assertThat(DadosFicticios.r01(true).descricaoFiltros())
                .isEqualTo("Período: 01/09/2026 a 30/09/2026 · Operador: todos · Detalhado por venda");
        assertThat(DadosFicticios.r05().emissao()).startsWith("Emitido por Ana Admin em 17/10/2026");
    }

    @Test
    void catalogoComecaPelosCincoRelatoriosDoTcc() {
        assertThat(Relatorios.todos()).extracting(Relatorio::codigo)
                .startsWith("R01", "R02", "R03", "R04", "R05");
    }
}
