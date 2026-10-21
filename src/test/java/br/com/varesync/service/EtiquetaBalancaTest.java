package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.util.Gtin;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Etiqueta de balança (6.9): vetores de A.7 e reconhecimento. Sem banco. */
class EtiquetaBalancaTest {

    private static final BigDecimal PRECO_KG = new BigDecimal("39.90");
    private static final Configuracoes.Balanca CINCO_PRECO =
            new Configuracoes.Balanca(true, 2, 5, TipoValorBalanca.PRECO);
    private static final Configuracoes.Balanca CINCO_PESO =
            new Configuracoes.Balanca(true, 2, 5, TipoValorBalanca.PESO);
    private static final Configuracoes.Balanca QUATRO_PRECO =
            new Configuracoes.Balanca(true, 2, 4, TipoValorBalanca.PRECO);

    private static String comDv(String semDv) {
        return semDv + Gtin.digitoVerificador(semDv);
    }

    @Test
    void cincoDigitosPrecoA7() {
        EtiquetaBalanca etiqueta = EtiquetaBalanca.reconhecer("2001230013776", CINCO_PRECO).orElseThrow();
        assertThat(etiqueta.plu()).isEqualTo(123);
        assertThat(etiqueta.valor()).isEqualTo(1377);
        EtiquetaBalanca.Item item = etiqueta.item(PRECO_KG);
        assertThat(item.quantidade()).isEqualByComparingTo("0.345");
        assertThat(item.quantidade().scale()).isEqualTo(3);
        assertThat(item.valorBruto()).isEqualByComparingTo("13.77");
    }

    @Test
    void cincoDigitosPesoA7() {
        EtiquetaBalanca etiqueta = EtiquetaBalanca.reconhecer("2001230003456", CINCO_PESO).orElseThrow();
        assertThat(etiqueta.plu()).isEqualTo(123);
        assertThat(etiqueta.valor()).isEqualTo(345); // gramas
        EtiquetaBalanca.Item item = etiqueta.item(PRECO_KG);
        assertThat(item.quantidade()).isEqualByComparingTo("0.345");
        assertThat(item.valorBruto()).isEqualByComparingTo("13.77"); // 0,345 × 39,90 = 13,7655
    }

    @Test
    void quatroDigitosPrecoA7() {
        EtiquetaBalanca etiqueta = EtiquetaBalanca.reconhecer("2012300013770", QUATRO_PRECO).orElseThrow();
        assertThat(etiqueta.plu()).isEqualTo(123);
        assertThat(etiqueta.valor()).isEqualTo(1377);
        assertThat(etiqueta.item(PRECO_KG).valorBruto()).isEqualByComparingTo("13.77");
    }

    @Test
    void emPrecoValeOValorImpressoSemRecalcularA7() {
        // R$ 29,99/kg com etiqueta de R$ 10,00 → 0,333 kg; 0,333 × 29,99 daria 9,99, mas vale o valor da etiqueta.
        EtiquetaBalanca.Item item = EtiquetaBalanca.reconhecer("2001230010003", CINCO_PRECO).orElseThrow()
                .item(new BigDecimal("29.99"));
        assertThat(item.quantidade()).isEqualByComparingTo("0.333");
        assertThat(item.valorBruto()).isEqualByComparingTo("10.00");
    }

    @Test
    void naoEEtiquetaComOutroPrefixoTamanhoOuDigitoVerificador() {
        assertThat(EtiquetaBalanca.reconhecer("7891000315507", CINCO_PRECO)).isEmpty(); // outro prefixo
        assertThat(EtiquetaBalanca.reconhecer("2001230013777", CINCO_PRECO)).isEmpty(); // DV errado
        assertThat(EtiquetaBalanca.reconhecer("200123001377", CINCO_PRECO)).isEmpty(); // 12 dígitos
        assertThat(EtiquetaBalanca.reconhecer("20012300137766", CINCO_PRECO)).isEmpty();
        assertThat(EtiquetaBalanca.reconhecer("2001230013776",
                new Configuracoes.Balanca(true, 3, 5, TipoValorBalanca.PRECO))).isEmpty();
        assertThat(EtiquetaBalanca.reconhecer(null, CINCO_PRECO)).isEmpty();
    }

    @Test
    void comSeisDigitosOPluVaiAte999999() {
        EtiquetaBalanca etiqueta = EtiquetaBalanca.reconhecer(comDv("212345601377"),
                new Configuracoes.Balanca(true, 2, 6, TipoValorBalanca.PRECO)).orElseThrow();
        assertThat(etiqueta.plu()).isEqualTo(123456);
        assertThat(etiqueta.valor()).isEqualTo(1377);
    }

    @Test
    void valorZeroOuPequenoDemaisERecusado() {
        EtiquetaBalanca zero = EtiquetaBalanca.reconhecer(comDv("200123000000"), CINCO_PRECO).orElseThrow();
        assertThatThrownBy(() -> zero.item(PRECO_KG)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("zero");
        EtiquetaBalanca pequena = EtiquetaBalanca.reconhecer(comDv("200123000001"), CINCO_PRECO).orElseThrow();
        assertThatThrownBy(() -> pequena.item(PRECO_KG)).hasMessageContaining("pequeno demais");
    }

    @Test
    void itemDeEtiquetaComPrecoGuardaOValorDaEtiquetaNoCarrinho() {
        Carrinho carrinho = new Carrinho();
        ItemCarrinho item = carrinho.adicionar(CarrinhoTest.QUEIJO, new BigDecimal("0.333"), new BigDecimal("29.99"),
                null, new BigDecimal("10.00"));
        assertThat(item.valorBruto()).isEqualByComparingTo("10.00");
        assertThat(carrinho.total()).isEqualByComparingTo("10.00");
    }
}
