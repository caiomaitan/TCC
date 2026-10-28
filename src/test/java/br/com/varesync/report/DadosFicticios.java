package br.com.varesync.report;

import br.com.varesync.model.Cliente;
import br.com.varesync.model.ExtratoCredito;
import br.com.varesync.model.HistoricoCliente;
import br.com.varesync.model.InventarioResumo;
import br.com.varesync.model.ItemDivergencia;
import br.com.varesync.model.MovimentoCredito;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.ResultadoInventario;
import br.com.varesync.model.StatusInventario;
import br.com.varesync.model.TipoCreditoCliente;
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

    private static <T> ResultadoRelatorio<T> resultado(Relatorio<T> r, Filtros f, List<T> linhas) {
        return new ResultadoRelatorio<>(r, f, r.colunas(f), linhas, "Mercado Bom Preço", "Ana Admin", EMISSAO);
    }

    static ResultadoRelatorio<RelRentabilidadeCategoria.Linha> r06() {
        return resultado(new RelRentabilidadeCategoria(), FILTROS, RelRentabilidadeCategoria.comPercentual(List.of(
                new RelRentabilidadeCategoria.Linha("Bebidas", d("1000.00"), d("700.00"), d("300.00"), null),
                new RelRentabilidadeCategoria.Linha("Mercearia", d("500.00"), d("400.00"), d("100.00"), null),
                new RelRentabilidadeCategoria.Linha(RelRentabilidadeCategoria.SEM_CATEGORIA, d("20.00"),
                        d("20.00"), d("0.00"), null))));
    }

    /** A.9: receitas 500 · 300 · 100 · 60 · 40. */
    static ResultadoRelatorio<RelCurvaAbc.Linha> r07() {
        return resultado(new RelCurvaAbc(), FILTROS.comCriterio(Criterio.RECEITA), RelCurvaAbc.classificar(List.of(
                new RelCurvaAbc.Valor(4, "Produto D", d("60.00")),
                new RelCurvaAbc.Valor(1, "Produto A", d("500.00")),
                new RelCurvaAbc.Valor(5, "Produto E", d("40.00")),
                new RelCurvaAbc.Valor(2, "Produto B", d("300.00")),
                new RelCurvaAbc.Valor(3, "Produto C", d("100.00")))));
    }

    /** 10 vendas no período; a venda paga em dinheiro e PIX conta nas duas formas. */
    static ResultadoRelatorio<RelFormasPagamento.Linha> r08() {
        return resultado(new RelFormasPagamento(), FILTROS, RelFormasPagamento.comPercentual(List.of(
                new RelFormasPagamento.Linha("Dinheiro", 6, d("300.00"), null, 10),
                new RelFormasPagamento.Linha("PIX", 5, d("100.00"), null, 10))));
    }

    static ResultadoRelatorio<RelVendasOperador.Linha> r09() {
        return resultado(new RelVendasOperador(), FILTROS, List.of(
                new RelVendasOperador.Linha("Maria Oliveira", 3, d("90.00"), d("4.50"), 1, 2),
                new RelVendasOperador.Linha("José Santos", 1, d("10.00"), d("0.00"), 0, 0)));
    }

    static ResultadoRelatorio<RelVendasHoraDia.Linha> r10(Criterio criterio) {
        ZoneOffset fuso = ZoneOffset.ofHours(-3);
        // 01/09/2026 é terça-feira.
        List<RelVendasHoraDia.Venda> vendas = List.of(
                new RelVendasHoraDia.Venda(OffsetDateTime.of(2026, 9, 1, 11, 10, 0, 0, fuso), d("10.00")),
                new RelVendasHoraDia.Venda(OffsetDateTime.of(2026, 9, 1, 11, 50, 0, 0, fuso), d("5.50")),
                new RelVendasHoraDia.Venda(OffsetDateTime.of(2026, 9, 6, 13, 0, 0, 0, fuso), d("20.00")));
        return resultado(new RelVendasHoraDia(), FILTROS.comCriterio(criterio), RelVendasHoraDia.grade(vendas, fuso));
    }

    static ResultadoRelatorio<RelSemGiro.Linha> r12() {
        return resultado(new RelSemGiro(), FILTROS.comDias(30), List.of(
                new RelSemGiro.Linha(7, "Vinho tinto seco 750 ml", "Bebidas", "UN", false, d("12"), d("358.80"),
                        EMISSAO.minusDays(45), 45L),
                new RelSemGiro.Linha(8, "Fermento químico 100 g", "Mercearia", "UN", false, d("30"), d("54.00"),
                        null, null)));
    }

    static ResultadoRelatorio<RelPerdasAjustes.Linha> r14() {
        return resultado(new RelPerdasAjustes(), FILTROS, RelPerdasAjustes.agrupar(List.of(
                new RelPerdasAjustes.Movimento(TipoMovimento.PERDA, "Vencimento", 1, "Iogurte morango", "UN", false,
                        d("-3"), d("2.1000")),
                new RelPerdasAjustes.Movimento(TipoMovimento.PERDA, "Vencimento", 1, "Iogurte morango", "UN", false,
                        d("-2"), d("2.1000")),
                new RelPerdasAjustes.Movimento(TipoMovimento.INVENTARIO, "Inventário nº 4", 2, "Banana prata", "KG",
                        true, d("1.250"), d("3.4000")))));
    }

    static ResultadoRelatorio<RelAlteracoesPreco.Linha> r15() {
        return resultado(new RelAlteracoesPreco(), FILTROS, List.of(
                new RelAlteracoesPreco.Linha(EMISSAO.minusDays(5), 1, "7891000315507", "Refrigerante cola 2 L",
                        d("5.49"), d("5.99"), "Ana Admin"),
                new RelAlteracoesPreco.Linha(EMISSAO.minusDays(2), 2, null, "Pão francês", d("0.00"), d("15.90"),
                        "Ana Admin")));
    }

    static ResultadoRelatorio<RelCancelamentos.Linha> r16() {
        return resultado(new RelCancelamentos(), FILTROS, List.of(
                new RelCancelamentos.Linha(EMISSAO.minusDays(1), RelCancelamentos.ITEM, 7,
                        "Item 2: 1 UN x Leite UHT 1 L", d("4.79"), "Maria Oliveira", RelCancelamentos.MOTIVO_ITEM,
                        "Maria Oliveira", "Carlos Pereira (carlos)"),
                new RelCancelamentos.Linha(EMISSAO, RelCancelamentos.VENDA, 9, "Venda inteira (2 itens)",
                        d("20.00"), "Maria Oliveira", "Cliente desistiu", "Maria Oliveira",
                        "Carlos Pereira (carlos)")));
    }

    static ResultadoRelatorio<RelComprasFornecedor.Linha> r17() {
        return resultado(new RelComprasFornecedor(), FILTROS, List.of(
                new RelComprasFornecedor.Linha(5505, "Bebidas e Frios Sertãozinho Ltda", "12ABC34501DE35", 3, 25,
                        d("4520.30"), EMISSAO.minusDays(1)),
                new RelComprasFornecedor.Linha(2, "Produtor Rural José", "52998224725", 1, 2, d("80.00"),
                        EMISSAO.minusDays(9))));
    }

    /** Inventário nº 4 aplicado: sobra de 1 (R$ 3,60), falta de 2,5 kg (R$ 8,50) e um item não contado. */
    static ResultadoRelatorio<ItemDivergencia> r13() {
        OffsetDateTime contado = EMISSAO.minusHours(2);
        InventarioResumo inventario = new InventarioResumo(4, "Mercearia", StatusInventario.APLICADO,
                EMISSAO.minusDays(1), "Ana Admin", EMISSAO.minusHours(1), "Ana Admin", 3, 2);
        ResultadoInventario resultado = new ResultadoInventario(inventario, List.of(
                new ItemDivergencia(1, 1, "Açúcar refinado 1 kg", "UN", false, d("10"), d("11"), d("1"),
                        d("3.6000"), contado, "Ana Admin"),
                new ItemDivergencia(2, 2, "Banana prata", "KG", true, d("5.000"), d("2.500"), d("-2.500"),
                        d("3.4000"), contado, "Ana Admin"),
                new ItemDivergencia(3, 3, "Café 500 g", "UN", false, null, null, null, null, null, null)));
        return RelResultadoInventario.exportacao(resultado, new Emissao("Mercado Bom Preço", "Ana Admin", EMISSAO));
    }

    static ResultadoRelatorio<RelClientes.Linha> r18() {
        return resultado(new RelClientes(), FILTROS, List.of(
                new RelClientes.Linha(7, "Maria da Silva", "52998224725", 3, d("100.00"), EMISSAO.minusDays(2)),
                new RelClientes.Linha(8, "Padaria Boa Massa", "11222333000181", 1, d("10.00"),
                        EMISSAO.minusDays(9))));
    }

    /** Maria da Silva: duas compras de R$ 50,00, com consentimento de marketing. */
    static ResultadoRelatorio<RelDadosCliente.Linha> dadosCliente() {
        Cliente maria = new Cliente(7, "Maria da Silva", "52998224725", "16999998888", "maria@exemplo.com.br",
                LocalDate.of(1985, 3, 25), "14010000", "Rua das Flores", "120", "apto 3", "Centro", "Ribeirão Preto",
                "SP", "Prefere PIX", true, EMISSAO.minusDays(30), false, true, EMISSAO.minusDays(30), "Ana Admin",
                EMISSAO.minusDays(1), "Ana Admin");
        HistoricoCliente historico = new HistoricoCliente(List.of(
                new HistoricoCliente.Compra(12, EMISSAO.minusDays(1), 2, d("50.00")),
                new HistoricoCliente.Compra(9, EMISSAO.minusDays(5), 1, d("50.00"))), List.of(
                new HistoricoCliente.ProdutoComprado(1, "Refrigerante cola 2 L", "UN", false, 2, d("6"), d("32.94")),
                new HistoricoCliente.ProdutoComprado(2, "Queijo muçarela", "KG", true, 1, d("0.500"), d("19.95"))));
        ExtratoCredito extrato = new ExtratoCredito(d("0.50"), List.of(
                new MovimentoCredito(1, EMISSAO.minusDays(5), TipoCreditoCliente.CASHBACK, d("0.50"), d("0.00"),
                        d("0.50"), 9L, null, "Ana Admin"),
                new MovimentoCredito(2, EMISSAO.minusDays(1), TipoCreditoCliente.CASHBACK, d("0.50"), d("0.50"),
                        d("1.00"), 12L, null, "Ana Admin"),
                new MovimentoCredito(3, EMISSAO.minusDays(1), TipoCreditoCliente.AJUSTE, d("-0.50"), d("1.00"),
                        d("0.50"), null, "Correção de cashback", "Ana Admin")));
        return RelDadosCliente.exportacao(maria, historico, extrato,
                new Emissao("Mercado Bom Preço", "Ana Admin", EMISSAO));
    }

    /** R19: dois clientes com saldo, um deles negativo depois de um estorno. */
    static ResultadoRelatorio<RelSaldosCredito.Linha> r19() {
        return resultado(new RelSaldosCredito(), Filtros.vazios(), List.of(
                new RelSaldosCredito.Linha(7, "Maria da Silva", "52998224725", true, d("12.50"), EMISSAO.minusDays(2)),
                new RelSaldosCredito.Linha(9, "João Pereira", null, false, d("-0.30"), EMISSAO.minusDays(20))));
    }

    /** R20: uma devolução em dinheiro com item avariado e liberação, outra em vale-troca. */
    static ResultadoRelatorio<RelDevolucoes.Linha> r20() {
        return resultado(new RelDevolucoes(), Filtros.vazios().comPeriodo(FILTROS.periodo()), List.of(
                new RelDevolucoes.Linha(EMISSAO.minusDays(3), 4, 1201, "Maria Oliveira", "Maria da Silva (***.982.247-**)",
                        "2 UN x Refrigerante cola 2 L (avariado); 0,250 KG x Queijo minas", "Defeito",
                        FormaReembolso.DINHEIRO, d("14.97")),
                new RelDevolucoes.Linha(EMISSAO.minusDays(1), 5, 1250, "José Santos", "", "1 UN x Arroz 5 kg",
                        "Arrependimento", FormaReembolso.CREDITO_CLIENTE, d("24.90"))));
    }

    static List<ResultadoRelatorio<?>> todos() {
        return List.of(r01(false), r01(true), r02(), r03(), r04(), r05(), r06(), r07(), r08(), r09(),
                r10(Criterio.RECEITA), r10(Criterio.VENDAS), r12(), r13(), r14(), r15(), r16(), r17(), r18(),
                r19(), r20(), dadosCliente());
    }
}
