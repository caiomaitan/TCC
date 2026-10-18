package br.com.varesync.report;

import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Linhas fictícias de cada relatório, para testar tabela, CSV e PDF sem banco. */
final class DadosFicticios {

    static final OffsetDateTime EMISSAO = OffsetDateTime.of(2026, 10, 17, 14, 30, 0, 0, ZoneOffset.ofHours(-3));
    static final Filtros FILTROS = Filtros.vazios()
            .comPeriodo(new Periodo(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
            .comCategoria(new Opcao(2, "Bebidas"));

    private DadosFicticios() {
    }

    static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    static ResultadoRelatorio<RelVendasPeriodo.Linha> r01(boolean detalhar) {
        RelVendasPeriodo r = new RelVendasPeriodo();
        Filtros f = FILTROS.comDetalhe(detalhar);
        OffsetDateTime hora = OffsetDateTime.of(2026, 9, 1, 11, 5, 0, 0, ZoneOffset.ofHours(-3));
        List<RelVendasPeriodo.Linha> linhas = List.of(
                new RelVendasPeriodo.Linha(LocalDate.of(2026, 9, 1), 101L, hora, "Maria Oliveira", 1,
                        d("100.00"), d("2.50"), d("50.00"), d("50.00")),
                new RelVendasPeriodo.Linha(LocalDate.of(2026, 9, 2), 102L, hora.plusDays(1), "José Santos", 1,
                        d("1000.00"), d("0.00"), d("900.00"), d("100.00")));
        return new ResultadoRelatorio<>(r, f, r.colunas(f), linhas, "Mercado Bom Preço", "Ana Admin", EMISSAO);
    }

    static ResultadoRelatorio<RelMaisVendidos.Linha> r02() {
        RelMaisVendidos r = new RelMaisVendidos();
        List<RelMaisVendidos.Linha> linhas = RelMaisVendidos.ranquear(List.of(
                new RelMaisVendidos.Linha(0, 1, "Refrigerante cola PET 2 L", "UN", false, d("30"), d("284.70"),
                        d("101.70"), null),
                new RelMaisVendidos.Linha(0, 2, "Pão francês", "KG", true, d("12.345"), d("208.63"), d("91.36"),
                        null),
                new RelMaisVendidos.Linha(0, 3, "Leite UHT integral 1 L", "UN", false, d("40"), d("191.60"),
                        d("-4.00"), null)), Criterio.RECEITA, 2);
        return new ResultadoRelatorio<>(r, FILTROS, r.colunas(FILTROS), linhas, "Mercado Bom Preço", "Ana Admin",
                EMISSAO);
    }

    static ResultadoRelatorio<RelEstoqueAtual.Linha> r03() {
        RelEstoqueAtual r = new RelEstoqueAtual();
        List<RelEstoqueAtual.Linha> linhas = List.of(
                new RelEstoqueAtual.Linha(1, "Açúcar refinado 1 kg", "Mercearia", "UN", false, true, d("10"),
                        d("15"), d("3.6000"), d("36.00"), true),
                new RelEstoqueAtual.Linha(2, "Banana prata", "Hortifruti", "KG", true, true, d("-2.500"), d("5"),
                        d("3.4000"), d("-8.50"), true),
                new RelEstoqueAtual.Linha(3, "Café; \"especial\" 500 g", null, "UN", false, false, d("4"), d("0"),
                        d("14.3000"), d("57.20"), false));
        return new ResultadoRelatorio<>(r, FILTROS, r.colunas(FILTROS), linhas, "Mercado Bom Preço", "Ana Admin",
                EMISSAO);
    }

    static ResultadoRelatorio<RelMovimentacao.Linha> r04() {
        RelMovimentacao r = new RelMovimentacao();
        List<RelMovimentacao.Linha> linhas = List.of(
                new RelMovimentacao.Linha(EMISSAO.minusDays(3), 1, "Açúcar refinado 1 kg", "UN", false,
                        TipoMovimento.AJUSTE_ENTRADA, d("20"), d("20"), d("3.6000"), "Ana Admin", "Estoque inicial"),
                new RelMovimentacao.Linha(EMISSAO.minusDays(2), 1, "Açúcar refinado 1 kg", "UN", false,
                        TipoMovimento.VENDA, d("-3"), d("17"), d("3.6000"), "Maria Oliveira", "Venda nº 7"));
        return new ResultadoRelatorio<>(r, FILTROS, r.colunas(FILTROS), linhas, "Mercado Bom Preço", "Ana Admin",
                EMISSAO);
    }

    static ResultadoRelatorio<RelRentabilidadeProduto.Linha> r05() {
        RelRentabilidadeProduto r = new RelRentabilidadeProduto();
        List<RelRentabilidadeProduto.Linha> linhas = List.of(
                new RelRentabilidadeProduto.Linha(1, "Produto A", "Mercearia", "UN", false, d("10"), d("100.00"),
                        d("50.00"), d("50.00"), RelRentabilidadeProduto.OK),
                new RelRentabilidadeProduto.Linha(2, "Produto B", "Bebidas", "KG", true, d("2.500"), d("1000.00"),
                        d("900.00"), d("100.00"), RelRentabilidadeProduto.BAIXA));
        return new ResultadoRelatorio<>(r, FILTROS, r.colunas(FILTROS), linhas, "Mercado Bom Preço", "Ana Admin",
                EMISSAO);
    }

    static List<ResultadoRelatorio<?>> todos() {
        return List.of(r01(false), r01(true), r02(), r03(), r04(), r05());
    }
}
