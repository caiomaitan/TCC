package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Valores proporcionais da devolução (F13.2) e os vetores do aceite da F13. */
class CalculoDevolucaoTest {

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void descontoRateadoDevolveOProporcionalEORestanteNaUltima() {
        // 3 × R$ 5,49 com rateio de R$ 0,82: líquido R$ 15,65.
        BigDecimal primeira = CalculoDevolucao.parte(v("15.65"), v("3"), v("0"), v("0"), v("1"));
        assertThat(primeira).isEqualByComparingTo("5.22");
        BigDecimal resto = CalculoDevolucao.parte(v("15.65"), v("3"), v("1"), primeira, v("2"));
        assertThat(resto).isEqualByComparingTo("10.43");
        assertThat(primeira.add(resto)).isEqualByComparingTo("15.65");
    }

    @Test
    void devolucoesUmaAUmaSomamOValorDoItem() {
        BigDecimal devolvido = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            devolvido = devolvido.add(CalculoDevolucao.parte(v("10.00"), v("3"), BigDecimal.valueOf(i), devolvido,
                    v("1")));
        }
        assertThat(devolvido).as("3,33 + 3,33 + 3,34").isEqualByComparingTo("10.00");
    }

    @Test
    void quantidadeFracionada() {
        // 0,345 kg por R$ 13,77; devolver 0,100 kg.
        assertThat(CalculoDevolucao.parte(v("13.77"), v("0.345"), v("0"), v("0"), v("0.100")))
                .isEqualByComparingTo("3.99");
    }

    @Test
    void cashbackNaProporcaoDoValorDevolvidoEORestanteNaUltima() {
        assertThat(CalculoDevolucao.estornoCashback(v("1.00"), v("0"), v("100.00"), v("40.00"), false))
                .isEqualByComparingTo("0.40");
        assertThat(CalculoDevolucao.estornoCashback(v("1.00"), v("0.40"), v("100.00"), v("60.00"), true))
                .isEqualByComparingTo("0.60");
    }

    @Test
    void cashbackNuncaPassaDoQueFaltaEstornarNemDividePorZero() {
        assertThat(CalculoDevolucao.estornoCashback(v("0.03"), v("0.03"), v("6.00"), v("1.00"), false)).isZero();
        assertThat(CalculoDevolucao.estornoCashback(v("0.03"), v("0.02"), v("6.00"), v("3.00"), false))
                .as("0,015 → 0,02, limitado ao 0,01 que resta").isEqualByComparingTo("0.01");
        assertThat(CalculoDevolucao.estornoCashback(v("0.00"), v("0"), v("0.00"), v("0.00"), true)).isZero();
        assertThat(CalculoDevolucao.estornoCashback(v("1.00"), v("0"), v("0.00"), v("0.00"), false)).isZero();
    }
}
