package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Custo médio ponderado: vetores de A.5. */
class CustoMedioTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void saldoPositivoPonderaOSaldoEAEntrada() {
        assertThat(CustoMedio.calcular(bd("10"), bd("2.5000"), bd("20"), bd("3.1000"))).isEqualTo(bd("2.9000"));
        // (7 × 3,2 + 12 × 3,5) ÷ 19 = 64,4 ÷ 19 = 3,38947… → 3,3895
        assertThat(CustoMedio.calcular(bd("7"), bd("3.2000"), bd("12"), bd("3.5000"))).isEqualTo(bd("3.3895"));
    }

    @Test
    void saldoZeroOuNegativoUsaOCustoDaEntrada() {
        assertThat(CustoMedio.calcular(bd("-3"), bd("3.2000"), bd("12"), bd("3.5000"))).isEqualTo(bd("3.5000"));
        assertThat(CustoMedio.calcular(bd("0"), bd("3.2000"), bd("12"), bd("3.5"))).isEqualTo(bd("3.5000"));
    }

    @Test
    void quantidadeFracionadaETresCasas() {
        // (1,5 × 20 + 0,75 × 23) ÷ 2,25 = 47,25 ÷ 2,25 = 21
        assertThat(CustoMedio.calcular(bd("1.500"), bd("20.0000"), bd("0.750"), bd("23.0000")))
                .isEqualTo(bd("21.0000"));
    }

    @Test
    void quantidadeDaEntradaTemDeSerPositiva() {
        assertThatThrownBy(() -> CustoMedio.calcular(bd("1"), bd("1"), bd("0"), bd("1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
