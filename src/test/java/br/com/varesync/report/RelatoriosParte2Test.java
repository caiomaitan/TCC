package br.com.varesync.report;

import static br.com.varesync.report.DadosFicticios.d;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.TipoMovimento;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fórmulas, colunas e totais de R06 a R10, R12 a R16 (7.1, 7.3), sem banco. */
class RelatoriosParte2Test {

    @Test
    void r06PercentualDoLucroTotalEMargemAgregada() {
        ResultadoRelatorio<RelRentabilidadeCategoria.Linha> r06 = DadosFicticios.r06();
        assertThat(r06.linhas()).extracting(RelRentabilidadeCategoria.Linha::percentualLucro)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(d("75.00"), d("25.00"), d("0.00"));
        assertThat(r06.linhas().get(0).margem()).isEqualByComparingTo("30.00");
        assertThat(r06.totais()).containsExactly("Total (3 linhas)", "R$ 1.520,00", "R$ 1.120,00", "R$ 400,00",
                "26,32%", "100,00%");
    }

    @Test
    void r06SemLucroPositivoNaoTemPercentual() {
        List<RelRentabilidadeCategoria.Linha> linhas = RelRentabilidadeCategoria.comPercentual(List.of(
                new RelRentabilidadeCategoria.Linha("Bebidas", d("10.00"), d("12.00"), d("-2.00"), null)));
        assertThat(linhas.get(0).percentualLucro()).isNull();
    }

    @Test
    void r07CurvaAbcDoApendiceA9() {
        // Receitas 500 · 300 · 100 · 60 · 40: acumulado antes 0 · 50 · 80 · 90 · 96 → A · A · B · B · C.
        ResultadoRelatorio<RelCurvaAbc.Linha> r07 = DadosFicticios.r07();
        assertThat(r07.linhas()).extracting(RelCurvaAbc.Linha::produto)
                .containsExactly("Produto A", "Produto B", "Produto C", "Produto D", "Produto E");
        assertThat(r07.linhas()).extracting(RelCurvaAbc.Linha::classe).containsExactly("A", "A", "B", "B", "C");
        assertThat(r07.linhas()).extracting(RelCurvaAbc.Linha::percentualAcumulado)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(d("50"), d("80"), d("90"), d("96"), d("100"));
        assertThat(r07.colunas()).extracting(Coluna::titulo)
                .containsExactly("Classe", "Código", "Produto", "Receita", "%", "% acumulado");
        assertThat(r07.totais()).containsExactly("Total (5 linhas)", "", "", "R$ 1.000,00", "100,00%", "");
    }

    @Test
    void r07PorLucroDeixaLucroZeroOuNegativoNaClasseCSemAcumulado() {
        List<RelCurvaAbc.Linha> linhas = RelCurvaAbc.classificar(List.of(
                new RelCurvaAbc.Valor(1, "A", d("50")), new RelCurvaAbc.Valor(2, "B", d("30")),
                new RelCurvaAbc.Valor(3, "C", d("20")), new RelCurvaAbc.Valor(4, "Prejuízo", d("-10")),
                new RelCurvaAbc.Valor(5, "Zero", d("0"))));
        assertThat(linhas).extracting(RelCurvaAbc.Linha::produto)
                .containsExactly("A", "B", "C", "Zero", "Prejuízo");
        assertThat(linhas).extracting(RelCurvaAbc.Linha::classe).containsExactly("A", "A", "B", "C", "C");
        assertThat(linhas.get(2).percentualAcumulado()).isEqualByComparingTo("100");
        assertThat(linhas.get(4).percentual()).isEqualByComparingTo("-10.00");
        assertThat(linhas.get(4).percentualAcumulado()).isNull();
    }

    @Test
    void r07EsconderQuantidadeDosCriterios() {
        assertThat(new RelCurvaAbc().criterios()).containsExactly(Criterio.RECEITA, Criterio.LUCRO);
        assertThat(new RelVendasHoraDia().criterios()).containsExactly(Criterio.RECEITA, Criterio.VENDAS);
        assertThat(new RelMaisVendidos().criterios())
                .containsExactly(Criterio.QUANTIDADE, Criterio.RECEITA, Criterio.LUCRO);
    }

    @Test
    void r08TotalContaCadaVendaUmaVez() {
        ResultadoRelatorio<RelFormasPagamento.Linha> r08 = DadosFicticios.r08();
        assertThat(r08.linhas().get(0).percentual()).isEqualByComparingTo("75.00");
        // 6 + 5 = 11 nas formas, mas são 10 vendas: uma foi paga em dinheiro e PIX.
        assertThat(r08.totais()).containsExactly("Total (2 linhas)", "10", "R$ 400,00", "100,00%");
    }

    @Test
    void r09TicketMedioDoTotalESomaDaReceitaSobreAsVendas() {
        ResultadoRelatorio<RelVendasOperador.Linha> r09 = DadosFicticios.r09();
        assertThat(r09.linhas().get(0).ticketMedio()).isEqualByComparingTo("30.00");
        assertThat(r09.totais()).containsExactly("Total (2 linhas)", "4", "R$ 100,00", "R$ 25,00", "R$ 4,50", "1",
                "2");
    }

    @Test
    void r10GradeHoraPorDiaDaSemana() {
        ResultadoRelatorio<RelVendasHoraDia.Linha> receita = DadosFicticios.r10(Criterio.RECEITA);
        assertThat(receita.linhas()).extracting(RelVendasHoraDia.Linha::faixa)
                .containsExactly("11h–12h", "12h–13h", "13h–14h");
        assertThat(receita.colunas()).extracting(Coluna::titulo)
                .containsExactly("Hora", "Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Total");
        assertThat(receita.linhas().get(0).receitas().get(2)).isEqualByComparingTo("15.50"); // terça
        assertThat(receita.linhas().get(1).receitaTotal()).isEqualByComparingTo("0");
        assertThat(receita.totais()).containsExactly("Total (3 linhas)", "R$ 20,00", "R$ 0,00", "R$ 15,50",
                "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 0,00", "R$ 35,50");

        ResultadoRelatorio<RelVendasHoraDia.Linha> vendas = DadosFicticios.r10(Criterio.VENDAS);
        assertThat(vendas.totais()).containsExactly("Total (3 linhas)", "1", "0", "2", "0", "0", "0", "0", "3");
    }

    @Test
    void r10SemVendasNaoTemLinhas() {
        assertThat(RelVendasHoraDia.grade(List.of(), java.time.ZoneOffset.UTC)).isEmpty();
    }

    @Test
    void r12NuncaVendidoApareceSemDataETotalDoValorParado() {
        ResultadoRelatorio<RelSemGiro.Linha> r12 = DadosFicticios.r12();
        assertThat(r12.colunas().get(6).formatar(r12.linhas().get(1))).isEqualTo("—");
        assertThat(r12.totais()).contains("R$ 412,80");
        assertThat(r12.descricaoFiltros()).isEqualTo("Categoria: Bebidas · Sem venda há 30 dias ou mais");
    }

    @Test
    void r14AgrupaPorTipoMotivoEProdutoComValorACusto() {
        ResultadoRelatorio<RelPerdasAjustes.Linha> r14 = DadosFicticios.r14();
        assertThat(r14.linhas()).hasSize(2);
        RelPerdasAjustes.Linha perda = r14.linhas().get(0);
        assertThat(perda.tipo()).isEqualTo(TipoMovimento.PERDA);
        assertThat(perda.movimentos()).isEqualTo(2);
        assertThat(perda.quantidade()).isEqualByComparingTo("-5");
        assertThat(perda.valor()).isEqualByComparingTo("-10.50");
        assertThat(r14.linhas().get(1).valor()).isEqualByComparingTo("4.25"); // 1,250 kg × 3,40
        assertThat(r14.totais()).containsExactly("Total (2 linhas)", "", "", "3", "", "", "-R$ 6,25");
        assertThat(new RelPerdasAjustes().tiposMovimento()).containsExactly(TipoMovimento.PERDA,
                TipoMovimento.AJUSTE_SAIDA, TipoMovimento.AJUSTE_ENTRADA, TipoMovimento.INVENTARIO);
    }

    @Test
    void r14MotivoPrincipalEOTextoAntesDosDoisPontos() {
        assertThat(RelPerdasAjustes.motivoPrincipal("Vencimento: lote 12")).isEqualTo("Vencimento");
        assertThat(RelPerdasAjustes.motivoPrincipal("Estoque inicial")).isEqualTo("Estoque inicial");
        assertThat(RelPerdasAjustes.motivoPrincipal(null)).isEqualTo("(sem motivo)");
    }

    @Test
    void r15VariacaoDoPrecoESemLinhaDeTotais() {
        ResultadoRelatorio<RelAlteracoesPreco.Linha> r15 = DadosFicticios.r15();
        assertThat(r15.linhas().get(0).variacao()).isEqualByComparingTo("9.11");
        assertThat(r15.linhas().get(1).variacao()).isNull();
        assertThat(r15.temTotais()).isFalse();
    }

    @Test
    void r16LiberadoPorVemDoFimDaAuditoria() {
        assertThat(RelCancelamentos.liberadoPor("Item 2 cancelado durante a venda: 1 UN x Leite UHT 1 L (R$ 4,79) "
                + "(liberado por Carlos Pereira (carlos))")).isEqualTo("Carlos Pereira (carlos)");
        assertThat(RelCancelamentos.liberadoPor("Item 2 cancelado durante a venda: 1 UN x Leite (R$ 4,79)"))
                .isEmpty();
        assertThat(RelCancelamentos.liberadoPor(null)).isEmpty();
        assertThat(DadosFicticios.r16().totais()).contains("R$ 24,79");
    }

    @Test
    void r13SobraEFaltaSeparadasComTotaisDoInventario() {
        ResultadoRelatorio<?> r13 = DadosFicticios.r13();
        assertThat(r13.titulo()).isEqualTo("R13 — Resultado de inventário");
        assertThat(r13.totais()).containsExactly("Total (3 linhas)", "", "", "", "", "", "", "R$ 3,60",
                "R$ 8,50");
        assertThat(r13.descricaoFiltros()).startsWith("Inventário nº 4 — Mercearia · Aplicado em ")
                .endsWith(" por Ana Admin · Resultado: falta de R$ 4,90 · 1 item não contado (ignorado ao aplicar)");
    }

    @Test
    void r17ComprasPorFornecedorComDocumentoMascaradoECodigoSemSeparador() {
        ResultadoRelatorio<RelComprasFornecedor.Linha> r17 = DadosFicticios.r17();
        assertThat(r17.titulo()).isEqualTo("R17 — Compras por fornecedor");
        assertThat(r17.colunas()).extracting(Coluna::titulo).containsExactly("Código", "Fornecedor", "CPF/CNPJ",
                "Entradas", "Itens", "Valor", "Última entrada");
        RelComprasFornecedor.Linha primeira = r17.linhas().get(0);
        assertThat(r17.colunas().get(0).formatar(primeira)).isEqualTo("5505");
        assertThat(r17.colunas().get(2).formatar(primeira)).isEqualTo("12.ABC.345/01DE-35");
        assertThat(r17.colunas().get(2).formatar(r17.linhas().get(1))).isEqualTo("529.982.247-25");
        assertThat(r17.totais()).containsExactly("Total (2 linhas)", "", "", "4", "27", "R$ 4.600,30", "");
        assertThat(r17.descricaoFiltros()).contains("Fornecedor: todos");
    }

    @Test
    void colunaCodigoSemSeparadorDeMilharNaTelaNoPdfENoCsv() {
        ResultadoRelatorio<RelEstoqueAtual.Linha> r03 = DadosFicticios.r03();
        Coluna<RelEstoqueAtual.Linha> codigo = r03.colunas().get(0);
        assertThat(codigo.tipo()).isEqualTo(TipoColuna.CODIGO);
        assertThat(codigo.formatar(5505L, null)).isEqualTo("5505");
        assertThat(codigo.csv(5505L, null)).isEqualTo("5505");
        assertThat(codigo.totalizavel()).isFalse();
        assertThat(DadosFicticios.r01(true).colunas().get(0).formatar(1974L, null)).as("Venda nº")
                .isEqualTo("1974");
    }

    @Test
    void catalogoTemOsRelatoriosNaOrdemDosCodigos() {
        // R11 e R13 ficam nas telas Caixa e Inventário.
        assertThat(Relatorios.todos()).extracting(Relatorio::codigo).containsExactly("R01", "R02", "R03", "R04",
                "R05", "R06", "R07", "R08", "R09", "R10", "R12", "R14", "R15", "R16", "R17");
    }
}
