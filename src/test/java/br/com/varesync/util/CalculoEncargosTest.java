package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contas do F14.3, com os valores exatos do aceite da F14. */
class CalculoEncargosTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 30);
    private static final BigDecimal DOIS = new BigDecimal("2");

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private static CalculoEncargos.Encargos encargos(String emAberto, int diasAtraso, int carencia) {
        return CalculoEncargos.calcular(d(emAberto), HOJE.minusDays(diasAtraso), HOJE, DOIS, DOIS, carencia);
    }

    @Test
    void tituloDeCemVencidoHaDezDias() {
        CalculoEncargos.Encargos e = encargos("100.00", 10, 0);

        assertThat(e.multa()).isEqualByComparingTo("2.00");
        assertThat(e.juros()).isEqualByComparingTo("0.67");
        assertThat(e.paraQuitar()).isEqualByComparingTo("102.67");
    }

    @Test
    void parcialComAtrasoAbateCinquenta() {
        CalculoEncargos.Divisao d = CalculoEncargos.dividir(d("51.34"), encargos("100.00", 10, 0));

        assertThat(d.principal()).isEqualByComparingTo("50.00");
        assertThat(d.multa()).isEqualByComparingTo("1.00");
        assertThat(d.juros()).isEqualByComparingTo("0.34");
    }

    @Test
    void parcialSemAtrasoSoAbatePrincipal() {
        CalculoEncargos.Divisao d = CalculoEncargos.dividir(d("50.00"), encargos("100.00", -5, 0));

        assertThat(d.principal()).isEqualByComparingTo("50.00");
        assertThat(d.multa()).isEqualByComparingTo("0.00");
        assertThat(d.juros()).isEqualByComparingTo("0.00");
    }

    @Test
    void semEncargosAteACarenciaEComEncargosDepoisDela() {
        assertThat(encargos("100.00", 5, 5).paraQuitar()).isEqualByComparingTo("100.00");
        assertThat(encargos("100.00", 0, 0).paraQuitar()).isEqualByComparingTo("100.00");
        // Passada a carência, os juros contam todos os dias de atraso.
        assertThat(encargos("100.00", 6, 5).juros()).isEqualByComparingTo("0.40");
        assertThat(encargos("100.00", 6, 5).multa()).isEqualByComparingTo("2.00");
    }

    @Test
    void quitarPagaOsEncargosInteiros() {
        CalculoEncargos.Divisao d = CalculoEncargos.dividir(d("102.67"), encargos("100.00", 10, 0));

        assertThat(d.principal()).isEqualByComparingTo("100.00");
        assertThat(d.multa()).isEqualByComparingTo("2.00");
        assertThat(d.juros()).isEqualByComparingTo("0.67");
    }

    @Test
    void valorVaiDoMaisAntigoParaOMaisNovo() {
        List<CalculoEncargos.Encargos> titulos = List.of(encargos("100.00", 10, 0), encargos("100.00", -20, 0));

        List<CalculoEncargos.Aplicacao> a = CalculoEncargos.aplicarValor(d("152.67"), titulos);

        assertThat(a).hasSize(2);
        assertThat(a.get(0).total()).isEqualByComparingTo("102.67");
        assertThat(a.get(1).principal()).isEqualByComparingTo("50.00");
        assertThat(a.stream().map(CalculoEncargos.Aplicacao::total).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("152.67");
    }

    @Test
    void valorQueNaoChegaAoSegundoTituloNaoOToca() {
        List<CalculoEncargos.Aplicacao> a = CalculoEncargos.aplicarValor(d("51.34"),
                List.of(encargos("100.00", 10, 0), encargos("100.00", -20, 0)));

        assertThat(a).hasSize(1);
        assertThat(a.get(0).indice()).isZero();
    }

    @Test
    void descontoFicaNosEncargosDoMaisAntigoPrimeiro() {
        List<CalculoEncargos.Aplicacao> a = CalculoEncargos.quitar(
                List.of(encargos("100.00", 10, 0), encargos("100.00", 5, 0)), d("3.00"));

        assertThat(a.get(0).desconto()).isEqualByComparingTo("2.67");
        assertThat(a.get(1).desconto()).isEqualByComparingTo("0.33");
        assertThat(a.get(0).total()).isEqualByComparingTo("100.00");
    }

    @Test
    void diasDeAtrasoNaTelaNaoFicamNegativos() {
        assertThat(CalculoEncargos.diasDeAtraso(HOJE.plusDays(3), HOJE)).isZero();
        assertThat(CalculoEncargos.diasDeAtraso(HOJE.minusDays(3), HOJE)).isEqualTo(3);
    }
}
