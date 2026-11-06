package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Conteúdo líquido e preço por kg ou litro da etiqueta (F18.1, aceite "Preço por kg"). Sem banco. */
class ConteudoLiquidoTest {

    private static ConteudoLiquido c(String quantidade, UnidadeConteudo unidade) {
        return new ConteudoLiquido(new BigDecimal(quantidade), unidade);
    }

    @Test
    void precoPorKgDoAceite() {
        assertThat(c("500", UnidadeConteudo.G).precoPorMedida(new BigDecimal("8.99"))).isEqualByComparingTo("17.98");
    }

    @Test
    void outrasUnidades() {
        assertThat(c("2", UnidadeConteudo.KG).precoPorMedida(new BigDecimal("25.90"))).isEqualByComparingTo("12.95");
        assertThat(c("350", UnidadeConteudo.ML).precoPorMedida(new BigDecimal("3.49"))).isEqualByComparingTo("9.97");
        assertThat(c("1.5", UnidadeConteudo.L).precoPorMedida(new BigDecimal("6.99"))).isEqualByComparingTo("4.66");
        assertThat(c("1.5", UnidadeConteudo.L).precoPorMedida(new BigDecimal("6.99")).scale()).isEqualTo(2);
    }

    @Test
    void descricaoEComparacao() {
        assertThat(c("500.000", UnidadeConteudo.G).descricao()).isEqualTo("500 g");
        assertThat(c("1.500", UnidadeConteudo.L).descricao()).isEqualTo("1,5 L");
        assertThat(ConteudoLiquido.SEM.descricao()).isEmpty();
        assertThat(c("500.000", UnidadeConteudo.G).igual(c("500", UnidadeConteudo.G))).isTrue();
        assertThat(c("500", UnidadeConteudo.G).igual(c("500", UnidadeConteudo.ML))).isFalse();
        assertThat(ConteudoLiquido.SEM.igual(c("500", UnidadeConteudo.G))).isFalse();
        assertThat(ConteudoLiquido.SEM.igual(ConteudoLiquido.SEM)).isTrue();
    }

    @Test
    void produtoVendidoPorKgNaoRepeteOPrecoPorMedida() {
        LocalDate hoje = LocalDate.of(2026, 11, 6);
        Etiqueta kg = new Etiqueta(1, "Queijo", "KG", new BigDecimal("39.90"), null, null, null, null, 1,
                c("1", UnidadeConteudo.KG), hoje, null, true, 1);
        assertThat(kg.precoPorMedida()).isNull();
        Etiqueta un = new Etiqueta(2, "Biscoito", "UN", new BigDecimal("8.99"), null, null, null, null, null,
                c("500", UnidadeConteudo.G), hoje, null, true, 1);
        assertThat(un.precoPorMedida()).isEqualByComparingTo("17.98");
        Etiqueta sem = un.comQuantidade(2);
        assertThat(sem.quantidade()).isEqualTo(2);
        assertThat(new Etiqueta(3, "X", "UN", BigDecimal.ONE, null, null, null, null, null, null, hoje, null, true, 1)
                .precoPorMedida()).isNull();
    }

    @Test
    void tiposDeCodigoDeBarrasDaEtiqueta() {
        assertThat(TipoCodigoBarras.de("7891000315507")).contains(TipoCodigoBarras.EAN_13);
        assertThat(TipoCodigoBarras.de("12345670")).contains(TipoCodigoBarras.EAN_8);
        assertThat(TipoCodigoBarras.de("036000291452")).contains(TipoCodigoBarras.UPC_A);
        assertThat(TipoCodigoBarras.de("17891000315504")).as("GTIN-14 não vai desenhado").isEmpty();
        assertThat(TipoCodigoBarras.de("7891234567890")).as("dígito errado").isEmpty();
        assertThat(TipoCodigoBarras.de(null)).isEmpty();
    }
}
