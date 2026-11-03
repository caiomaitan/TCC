package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Cálculo da sugestão de compra (F16.2) sem banco, com os vetores do aceite da F16: média 10 por dia, cobertura 15,
 * prazo 3, saldo 40, nada pendente e fator 12 → 144; com estoque mínimo de 200 → 168; e o pendente reduz a sugestão.
 */
class SugestaoCompraTest {

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    /** 300 vendidos em 30 dias = média 10 por dia. */
    private static SugestaoCompra.Calculo calcular(String saldo, String pendente, String minimo, String fator) {
        return SugestaoCompra.calcular(d("300"), 30, 15, 3, d(saldo), d(pendente), d(minimo), d(fator));
    }

    @Test
    void sugestaoBasicaDoAceite() {
        SugestaoCompra.Calculo c = calcular("40", "0", "0", "12");
        assertThat(c.mediaDia()).isEqualByComparingTo("10");
        assertThat(c.necessidade()).isEqualByComparingTo("180"); // 10 × (15 + 3)
        assertThat(c.sugestao()).isEqualByComparingTo("144"); // 140 → 12 caixas de 12
        assertThat(c.coberturaDias()).isEqualTo(4L); // 40 ÷ 10
    }

    @Test
    void estoqueMinimoDoAceite() {
        // 200 − 40 = 160 passa de 180 − 40 = 140 → 14 caixas de 12.
        assertThat(calcular("40", "0", "200", "12").sugestao()).isEqualByComparingTo("168");
    }

    @Test
    void pendenteReduzASugestao() {
        // 180 − 40 − 24 = 116 → 10 caixas de 12 = 120 (sem o pendente seriam 144).
        assertThat(calcular("40", "24", "0", "12").sugestao()).isEqualByComparingTo("120");
        // Com o pendente cobrindo tudo, nada a comprar.
        assertThat(calcular("40", "150", "0", "12").sugestao()).isEqualByComparingTo("0");
    }

    @Test
    void semFatorArredondaParaCimaNaUnidadeEFatorFracionado() {
        // 300 × 18 ÷ 30 = 180; saldo 40,5 → 139,5 → 140 (fator 1).
        assertThat(calcular("40.5", "0", "0", "1").sugestao()).isEqualByComparingTo("140");
        // Caixa de 2,5 kg: 139,5 ÷ 2,5 = 55,8 → 56 caixas = 140 kg.
        assertThat(calcular("40.5", "0", "0", "2.5").sugestao()).isEqualByComparingTo("140");
    }

    @Test
    void saldoQueCobreNaoGeraSugestaoNegativa() {
        SugestaoCompra.Calculo c = calcular("500", "0", "0", "12");
        assertThat(c.sugestao()).isEqualByComparingTo("0");
        assertThat(c.coberturaDias()).isEqualTo(50L);
    }

    @Test
    void saldoNegativoEntraNaContaECoberturaZero() {
        SugestaoCompra.Calculo c = calcular("-6", "0", "0", "1");
        assertThat(c.sugestao()).isEqualByComparingTo("186"); // 180 + 6
        assertThat(c.coberturaDias()).isZero();
    }

    @Test
    void semVendaOuMaisDevolucaoQueVendaNaoTemMediaNemCobertura() {
        SugestaoCompra.Calculo semVenda = SugestaoCompra.calcular(d("0"), 30, 15, 3, d("10"), d("0"), d("12"),
                d("1"));
        assertThat(semVenda.mediaDia()).isEqualByComparingTo("0");
        assertThat(semVenda.coberturaDias()).isNull();
        assertThat(semVenda.sugestao()).isEqualByComparingTo("2"); // só o estoque mínimo
        // Venda líquida negativa (devolveu mais do que vendeu no período) conta como zero.
        SugestaoCompra.Calculo negativa = SugestaoCompra.calcular(d("-5"), 30, 15, 3, d("10"), d("0"), d("0"),
                d("1"));
        assertThat(negativa.mediaDia()).isEqualByComparingTo("0");
        assertThat(negativa.sugestao()).isEqualByComparingTo("0");
    }

    @Test
    void necessidadeSemArredondarAMediaAntes() {
        // 10 em 3 dias: média 3,333; necessidade = 10 × 18 ÷ 3 = 60 (e não 3,333 × 18 = 59,994).
        SugestaoCompra.Calculo c = SugestaoCompra.calcular(d("10"), 3, 15, 3, d("0"), d("0"), d("0"), d("1"));
        assertThat(c.mediaDia()).isEqualByComparingTo("3.333");
        assertThat(c.necessidade()).isEqualByComparingTo("60");
        assertThat(c.sugestao()).isEqualByComparingTo("60");
    }
}
