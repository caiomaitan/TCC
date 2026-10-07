package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DinheiroTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void escalasPadraoDoSistema() {
        assertThat(Dinheiro.dinheiro(bd("1.005"))).isEqualByComparingTo("1.01").hasScaleOf(2);
        assertThat(Dinheiro.custo(bd("0.83333"))).isEqualByComparingTo("0.8333").hasScaleOf(4);
        assertThat(Dinheiro.quantidade(bd("0.3455"))).isEqualByComparingTo("0.346").hasScaleOf(3);
        assertThat(Dinheiro.percentual(bd("41.7122"))).isEqualByComparingTo("41.71").hasScaleOf(2);
    }

    @ParameterizedTest
    @CsvSource({
            "2.345, 2.35",   // HALF_UP, como o round() do PostgreSQL
            "2.344, 2.34",
            "2.355, 2.36",
            "0.005, 0.01",
            "-2.345, -2.35"
    })
    void arredondaMeioParaCima(String entrada, String esperado) {
        assertThat(Dinheiro.dinheiro(bd(entrada))).isEqualByComparingTo(esperado);
    }

    @Test
    void custoUnitarioDeCaixaFechadaTemQuatroCasas() {
        // Caixa com 12 por R$ 10,00 = 0,8333 (5.1, item 6)
        assertThat(Dinheiro.dividir(bd("10.00"), bd("12"), Dinheiro.ESCALA_CUSTO)).hasValue(bd("0.8333"));
    }

    @Test
    void quantidadeFracionadaVezesPreco_A11() {
        // 0,345 kg × R$ 39,90 = 13,7655 → 13,77
        assertThat(Dinheiro.multiplicar(bd("0.345"), bd("39.90"))).isEqualByComparingTo("13.77").hasScaleOf(2);
    }

    @Test
    void percentuaisDoApendiceA6() {
        BigDecimal lucro = bd("5.49").subtract(bd("3.20"));
        assertThat(lucro).isEqualByComparingTo("2.29");
        assertThat(Dinheiro.percentualDe(lucro, bd("5.49"))).hasValue(bd("41.71"));    // margem
        assertThat(Dinheiro.percentualDe(lucro, bd("3.20"))).hasValue(bd("71.56"));    // markup
        // Margem agregada = Σ lucro ÷ Σ receita (150 ÷ 1.100), nunca a média de 50% e 10%
        assertThat(Dinheiro.percentualDe(bd("150"), bd("1100"))).hasValue(bd("13.64"));
    }

    @Test
    void divisaoPorZeroNaoTemValor() {
        assertThat(Dinheiro.dividir(bd("10"), BigDecimal.ZERO, 2)).isEmpty();
        assertThat(Dinheiro.dividir(bd("10"), bd("0.00"), 2)).isEmpty();
        assertThat(Dinheiro.percentualDe(bd("10"), bd("0.0000"))).isEmpty();
    }

    @Test
    void comparacaoIgnoraEscala() {
        assertThat(Dinheiro.ehZero(bd("0.000"))).isTrue();
        assertThat(Dinheiro.ehNegativo(bd("-0.01"))).isTrue();
        assertThat(Dinheiro.ehNegativo(BigDecimal.ZERO)).isFalse();
        assertThat(Dinheiro.ZERO).hasScaleOf(2);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1.234,56     | 1234.56",
            "1234,56      | 1234.56",
            "1234.56      | 1234.56",
            "1234         | 1234",
            "0,345        | 0.345",
            "1.5          | 1.5",
            "1.234.567    | 1234567",
            "1.234.567,89 | 1234567.89",
            "R$ 5,49      | 5.49",
            "R$ 1.234,56  | 1234.56",
            "-3,20        | -3.20",
            "'  12,50  '  | 12.50"
    })
    void leValoresNoPadraoBrasileiro(String texto, String esperado) {
        assertThat(Dinheiro.ler(texto)).isEqualByComparingTo(esperado);
    }

    @Test
    void leValorComEspacoInseparavel() {
        assertThat(Dinheiro.ler("R$ 1.234,56")).isEqualByComparingTo("1234.56");
    }

    @Test
    void leituraNaoArredonda() {
        assertThat(Dinheiro.ler("0,3455")).isEqualByComparingTo("0.3455").hasScaleOf(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "1,2,3", "12.34,5", "1.23.4", ",5", "5,", "1,234.56", "--1", "R$"})
    void recusaValoresInvalidos(String texto) {
        assertThatThrownBy(() -> Dinheiro.ler(texto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválido");
    }

    @Test
    void recusaNulo() {
        assertThatThrownBy(() -> Dinheiro.ler(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
