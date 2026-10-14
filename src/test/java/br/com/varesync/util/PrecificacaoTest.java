package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Simulador do cadastro de produto: vetores de A.6. */
class PrecificacaoTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void lucroMargemEMarkupDoApendiceA6() {
        BigDecimal custo = bd("3.2000");
        BigDecimal preco = bd("5.49");
        assertThat(Precificacao.lucroUnitario(custo, preco)).isEqualByComparingTo("2.29");
        assertThat(Precificacao.margem(custo, preco)).hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo("41.71"));
        assertThat(Precificacao.markup(custo, preco)).hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo("71.56"));
    }

    @Test
    void precoSugeridoArredondaParaCima() {
        // 3,20 ÷ 0,70 = 4,5714… → 4,58 (4,57 daria margem de 29,98%)
        BigDecimal sugerido = Precificacao.precoSugerido(bd("3.20"), bd("30")).orElseThrow();
        assertThat(sugerido).isEqualByComparingTo("4.58");
        assertThat(sugerido.scale()).isEqualTo(2);
        assertThat(Precificacao.margem(bd("3.20"), bd("4.57")))
                .hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo("29.98"));
        assertThat(Precificacao.margem(bd("3.20"), sugerido))
                .hasValueSatisfying(m -> assertThat(m).isGreaterThanOrEqualTo(bd("30")));
    }

    @Test
    void precoSugeridoExatoNaoSobe() {
        assertThat(Precificacao.precoSugerido(bd("4.00"), bd("20"))).hasValueSatisfying(
                p -> assertThat(p).isEqualByComparingTo("5.00"));
        assertThat(Precificacao.precoSugerido(bd("3.20"), bd("0"))).hasValueSatisfying(
                p -> assertThat(p).isEqualByComparingTo("3.20"));
        // custo com 4 casas: 0,8333 ÷ 0,75 = 1,11106… → 1,12
        assertThat(Precificacao.precoSugerido(bd("0.8333"), bd("25"))).hasValueSatisfying(
                p -> assertThat(p).isEqualByComparingTo("1.12"));
    }

    @Test
    void semDivisorNaoHaValor() {
        assertThat(Precificacao.margem(bd("3.20"), bd("0"))).isEmpty();
        assertThat(Precificacao.markup(bd("0"), bd("5.49"))).isEmpty();
        assertThat(Precificacao.precoSugerido(bd("3.20"), bd("100"))).isEmpty();
        assertThat(Precificacao.precoSugerido(bd("3.20"), bd("120"))).isEmpty();
    }

    @Test
    void precoAbaixoDoCustoDaMargemNegativa() {
        assertThat(Precificacao.lucroUnitario(bd("5.00"), bd("4.00"))).isEqualByComparingTo("-1.00");
        assertThat(Precificacao.margem(bd("5.00"), bd("4.00")))
                .hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo("-25.00"));
        assertThat(Precificacao.markup(bd("5.00"), bd("4.00")))
                .hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo("-20.00"));
    }
}
