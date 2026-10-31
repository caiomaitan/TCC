package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalculoCrediarioTest {

    private static final LocalDate PRIMEIRA = LocalDate.of(2026, 11, 5);

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void parcelasIguaisComVencimentosDeTrintaEmTrintaDias() {
        List<CalculoCrediario.Parcela> p = CalculoCrediario.parcelar(v("300.00"), 3, PRIMEIRA);
        assertThat(p).extracting(CalculoCrediario.Parcela::numero).containsExactly(1, 2, 3);
        assertThat(p).allSatisfy(x -> assertThat(x.valor()).isEqualByComparingTo("100.00"));
        assertThat(p).extracting(CalculoCrediario.Parcela::vencimento)
                .containsExactly(PRIMEIRA, PRIMEIRA.plusDays(30), PRIMEIRA.plusDays(60));
    }

    @Test
    void centavosQueSobramFicamNaPrimeira() {
        List<CalculoCrediario.Parcela> p = CalculoCrediario.parcelar(v("100.00"), 3, PRIMEIRA);
        assertThat(p.get(0).valor()).isEqualByComparingTo("33.34");
        assertThat(p.get(1).valor()).isEqualByComparingTo("33.33");
        assertThat(p.get(2).valor()).isEqualByComparingTo("33.33");
    }

    @Test
    void valorQueNaoDaUmCentavoPorParcelaERecusado() {
        assertThat(CalculoCrediario.parcelar(v("0.03"), 3, PRIMEIRA)).hasSize(3);
        assertThatThrownBy(() -> CalculoCrediario.parcelar(v("0.02"), 3, PRIMEIRA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abateDaUltimaParcelaParaAPrimeira() {
        List<CalculoCrediario.Abatimento> a = CalculoCrediario.abater(v("120.00"),
                List.of(v("100.00"), v("100.00"), v("100.00")));
        assertThat(a).extracting(CalculoCrediario.Abatimento::indice).containsExactly(2, 1);
        assertThat(a.get(0).valor()).isEqualByComparingTo("100.00");
        assertThat(a.get(1).valor()).isEqualByComparingTo("20.00");
    }

    @Test
    void pulaParcelaSemSaldoENaoPassaDoEmAberto() {
        List<CalculoCrediario.Abatimento> a = CalculoCrediario.abater(v("100.00"),
                List.of(v("30.00"), v("0.00"), v("10.00")));
        assertThat(a).extracting(CalculoCrediario.Abatimento::indice).containsExactly(2, 0);
        assertThat(a.stream().map(CalculoCrediario.Abatimento::valor).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("40.00");
        assertThat(CalculoCrediario.abater(v("0.00"), List.of(v("10.00")))).isEmpty();
    }
}
