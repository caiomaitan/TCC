package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.dao.PainelDAO;
import br.com.varesync.model.DiaPainel;
import br.com.varesync.model.IndicadoresDia;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Cálculos do painel (7.5) sem banco: indicadores do dia, variação e os 30 dias do gráfico. */
class PainelCalculoTest {

    private static final ZoneOffset FUSO = ZoneOffset.ofHours(-3);
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 19);

    private static PainelDAO.VendaPainel venda(LocalDate dia, int hora, String receita, String lucro) {
        return new PainelDAO.VendaPainel(dia.atTime(hora, 0).atOffset(FUSO), new BigDecimal(receita),
                new BigDecimal(lucro));
    }

    @Test
    void indicadoresDoDiaComTicketEMargemAgregada() {
        IndicadoresDia dia = IndicadoresDia.VAZIO.somar(new BigDecimal("100.00"), new BigDecimal("50.00"))
                .somar(new BigDecimal("1000.00"), new BigDecimal("100.00"));
        assertThat(dia.vendas()).isEqualTo(2);
        assertThat(dia.ticketMedio()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("550.00"));
        assertThat(dia.margem()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("13.64")); // A.6
        assertThat(IndicadoresDia.VAZIO.ticketMedio()).isEmpty();
        assertThat(IndicadoresDia.VAZIO.margem()).isEmpty();
    }

    @Test
    void variacaoPercentualSobreOValorAnterior() {
        assertThat(IndicadoresDia.variacao(new BigDecimal("150"), new BigDecimal("120")))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("25.00"));
        assertThat(IndicadoresDia.variacao(new BigDecimal("90"), new BigDecimal("120")))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("-25.00"));
        // Lucro de −10 para +10: subiu 200% (divide pelo valor absoluto do anterior).
        assertThat(IndicadoresDia.variacao(new BigDecimal("10"), new BigDecimal("-10")))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("200.00"));
        assertThat(IndicadoresDia.variacao(new BigDecimal("10"), BigDecimal.ZERO)).isEmpty();
        assertThat(IndicadoresDia.variacao(Optional.of(BigDecimal.ONE), Optional.empty())).isEmpty();
    }

    @Test
    void margemVariaEmPontosPercentuais() {
        assertThat(IndicadoresDia.variacaoEmPontos(Optional.of(new BigDecimal("31.50")),
                Optional.of(new BigDecimal("29.25")))).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("2.25"));
        assertThat(IndicadoresDia.variacaoEmPontos(Optional.empty(), Optional.of(BigDecimal.TEN))).isEmpty();
    }

    @Test
    void somaSoAsVendasDoIntervalo() {
        List<PainelDAO.VendaPainel> vendas = List.of(venda(HOJE, 9, "10.00", "3.00"), venda(HOJE, 15, "20.00", "5.00"),
                venda(HOJE.minusDays(1), 9, "99.00", "9.00"));
        OffsetDateTime inicio = HOJE.atStartOfDay().atOffset(FUSO);
        IndicadoresDia ateMeioDia = PainelService.somar(vendas, inicio, HOJE.atTime(12, 0).atOffset(FUSO));
        assertThat(ateMeioDia.vendas()).isEqualTo(1);
        assertThat(ateMeioDia.receita()).isEqualByComparingTo("10.00");
        assertThat(PainelService.somar(vendas, inicio, inicio.plusDays(1)).lucro()).isEqualByComparingTo("8.00");
    }

    /** F13.3: a devolução tira do faturamento e do lucro, mas não do nº de vendas nem do ticket médio. */
    @Test
    void devolucaoEntraNaReceitaLiquidaSemContarComoVenda() {
        List<PainelDAO.VendaPainel> vendas = List.of(venda(HOJE, 9, "16.47", "6.87"), venda(HOJE, 10, "4.00", "1.00"),
                new PainelDAO.VendaPainel(HOJE.atTime(11, 0).atOffset(FUSO), new BigDecimal("-5.49"),
                        new BigDecimal("-2.29"), false));
        OffsetDateTime inicio = HOJE.atStartOfDay().atOffset(FUSO);
        IndicadoresDia dia = PainelService.somar(vendas, inicio, inicio.plusDays(1));
        assertThat(dia.receita()).isEqualByComparingTo("14.98");
        assertThat(dia.lucro()).isEqualByComparingTo("5.58");
        assertThat(dia.vendas()).isEqualTo(2);
        assertThat(dia.ticketMedio()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("10.24"));
        assertThat(PainelService.porDia(vendas, HOJE, FUSO).get(29).receita()).isEqualByComparingTo("14.98");
    }

    @Test
    void graficoTemOsTrintaDiasAteHojeComZeroNosDiasSemVenda() {
        List<DiaPainel> dias = PainelService.porDia(List.of(venda(HOJE, 10, "10.00", "4.00"),
                venda(HOJE, 23, "5.00", "1.00"), venda(HOJE.minusDays(29), 8, "7.00", "2.00"),
                venda(HOJE.minusDays(30), 8, "100.00", "50.00")), HOJE, FUSO);
        assertThat(dias).hasSize(30);
        assertThat(dias.get(0).dia()).isEqualTo(HOJE.minusDays(29));
        assertThat(dias.get(0).receita()).isEqualByComparingTo("7.00");
        assertThat(dias.get(29).dia()).isEqualTo(HOJE);
        assertThat(dias.get(29).receita()).isEqualByComparingTo("15.00");
        assertThat(dias.get(29).lucro()).isEqualByComparingTo("5.00");
        assertThat(dias.get(15).receita()).isEqualByComparingTo("0");
    }
}
