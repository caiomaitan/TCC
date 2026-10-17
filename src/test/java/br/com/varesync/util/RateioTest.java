package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rateio do desconto da venda pelo maior resto em centavos (6.6). Vetores do Apêndice A.4. */
class RateioTest {

    private static List<BigDecimal> valores(String... textos) {
        return Arrays.stream(textos).map(BigDecimal::new).toList();
    }

    private static List<BigDecimal> ratear(String desconto, String... bases) {
        return Rateio.ratear(new BigDecimal(desconto), valores(bases));
    }

    @Test
    void tresBasesIguaisDezCentavos() {
        assertThat(ratear("0.10", "1.00", "1.00", "1.00")).containsExactlyElementsOf(valores("0.04", "0.03", "0.03"));
    }

    @Test
    void basesDiferentesCincoReais() {
        assertThat(ratear("5.00", "10.00", "20.00", "30.01"))
                .containsExactlyElementsOf(valores("0.83", "1.67", "2.50"));
    }

    @Test
    void doisCentavosEmQuatroItensVaoParaOsPrimeiros() {
        assertThat(ratear("0.02", "1.00", "1.00", "1.00", "1.00"))
                .containsExactlyElementsOf(valores("0.01", "0.01", "0.00", "0.00"));
    }

    @Test
    void umItemRecebeTudo() {
        assertThat(ratear("0.47", "16.47")).containsExactlyElementsOf(valores("0.47"));
    }

    @Test
    void empateNoRestoVaiParaAMaiorBase() {
        // 0,01 × 1,00 ÷ 3,00 e 0,01 × 2,00 ÷ 3,00: restos 100 e 200 → o centavo vai para a base 2,00.
        assertThat(ratear("0.01", "1.00", "2.00")).containsExactlyElementsOf(valores("0.00", "0.01"));
        // Restos iguais (bases iguais): vale a ordem do item.
        assertThat(ratear("0.01", "2.00", "2.00")).containsExactlyElementsOf(valores("0.01", "0.00"));
    }

    @Test
    void somaEhSempreExatamenteODesconto() {
        List<BigDecimal> partes = ratear("1.51", "16.47", "13.77");
        assertThat(partes).containsExactlyElementsOf(valores("0.82", "0.69"));
        assertThat(partes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1.51");
    }

    @Test
    void semDescontoTudoZeroEBaseZeroNaoRecebe() {
        assertThat(ratear("0.00", "5.00", "0.00")).containsExactlyElementsOf(valores("0.00", "0.00"));
        assertThat(ratear("1.00", "5.00", "0.00")).containsExactlyElementsOf(valores("1.00", "0.00"));
    }

    @Test
    void descontoMaiorQueAsBasesERecusado() {
        assertThatThrownBy(() -> ratear("10.01", "10.00")).isInstanceOf(IllegalArgumentException.class);
    }
}
