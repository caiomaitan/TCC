package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.Periodo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cálculos sem banco do fluxo de caixa (F15.4), do DRE (F15.5) e do R23. */
class FinanceiroRelatoriosTest {

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void agrupamentoPorDiaSemanaEMesComInicioDoPeriodo() {
        LocalDate quinta = LocalDate.of(2026, 10, 1);
        LocalDate sexta = LocalDate.of(2026, 10, 9);
        assertThat(Agrupamento.DIA.inicioDoGrupo(sexta, quinta)).isEqualTo(sexta);
        assertThat(Agrupamento.SEMANA.inicioDoGrupo(sexta, quinta)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(Agrupamento.SEMANA.inicioDoGrupo(LocalDate.of(2026, 10, 3), quinta)).as("não passa do início")
                .isEqualTo(quinta);
        assertThat(Agrupamento.MES.inicioDoGrupo(sexta, LocalDate.of(2026, 9, 20))).isEqualTo(quinta);
        assertThat(Agrupamento.DIA.rotuloDoGrupo(sexta)).isEqualTo("09/10/2026");
        assertThat(Agrupamento.SEMANA.rotuloDoGrupo(sexta)).isEqualTo("Semana de 09/10");
        assertThat(Agrupamento.MES.rotuloDoGrupo(quinta)).isEqualTo("10/2026");
    }

    @Test
    void gruposCobremOPeriodoComOSaldoAcumulado() {
        Periodo periodo = new Periodo(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 14));
        List<FluxoCaixa.Lancamento> lancamentos = List.of(
                new FluxoCaixa.Lancamento(LocalDate.of(2026, 9, 28), FluxoCaixa.Parte.REALIZADO, "Sangria",
                        d("100.00"), d("0.00")),
                new FluxoCaixa.Lancamento(LocalDate.of(2026, 10, 12), FluxoCaixa.Parte.PREVISTO,
                        FluxoCaixa.CONTAS_A_PAGAR, d("0.00"), d("30.00")));
        List<FluxoCaixa.Grupo> semanas = FluxoCaixa.grupos(periodo, Agrupamento.SEMANA, d("50.00"), lancamentos);
        assertThat(semanas).extracting(FluxoCaixa.Grupo::inicio).containsExactly(LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12));
        assertThat(semanas).extracting(g -> g.saldoFinal().toPlainString()).containsExactly("150.00", "150.00",
                "120.00");
        assertThat(FluxoCaixa.grupos(periodo, Agrupamento.MES, d("50.00"), lancamentos))
                .extracting(FluxoCaixa.Grupo::inicio).containsExactly(LocalDate.of(2026, 9, 28),
                        LocalDate.of(2026, 10, 1));
    }

    @Test
    void r24TemOSaldoDePartidaEOSaldoDepoisDeCadaLinha() {
        List<RelFluxoCaixa.Linha> linhas = DadosFicticios.r24().linhas();
        assertThat(linhas.get(0).lancamento()).isEqualTo(RelFluxoCaixa.SALDO_NO_INICIO);
        assertThat(linhas).extracting(l -> l.saldo().toPlainString()).containsExactly("5000.00", "5600.00",
                "2100.00", "2107.50", "1362.13", "3302.13");
        assertThat(linhas.get(2).entradas()).as("zero aparece como —").isNull();
    }

    @Test
    void dreJuntaOsMesesEOPercentualESobreAReceitaDeCadaMes() {
        List<RelDre.Linha> linhas = DadosFicticios.r25().linhas();
        assertThat(linhas).extracting(RelDre.Linha::chave).containsExactly("1", "2", "3", "4", "5", "6", "9",
                "9:Aluguel", "9:Energia", "14");
        RelDre.Linha energia = linhas.get(8);
        assertThat(energia.valor()).isNull();
        assertThat(energia.valorAnterior()).isEqualByComparingTo("-100.00");
        assertThat(energia.percentualAnterior()).isEqualByComparingTo("-12.50");
        assertThat(energia.descricaoComRecuo()).startsWith(" ").endsWith("Energia");
        assertThat(linhas.get(5).percentual()).as("margem bruta").isEqualByComparingTo("90.00");
        assertThat(linhas.get(9).percentual()).as("margem líquida").isEqualByComparingTo("80.00");
    }

    @Test
    void situacaoDaContaAPagar() {
        List<RelContasPagar.Linha> linhas = DadosFicticios.r23().linhas();
        assertThat(linhas).extracting(RelContasPagar.Linha::situacao).containsExactly("Vencida", "Paga", "A vencer");
        assertThat(linhas.get(0).competenciaTexto()).isEqualTo("10/2026");
    }
}
