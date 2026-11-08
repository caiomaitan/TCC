package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Layout do ITENSMGV.TXT (F18.2): a linha de amostra mostrada ao usuário, conferida campo a campo; quebra da descrição,
 * acentos e limites. Sem banco.
 */
class LayoutMgvTest {

    /**
     * A linha do layout mostrado ao usuário campo a campo (queijo, PLU 123, R$ 39,90 o kg, 5 dias): posições 1 a 68
     * com os dados, 69 a 84 zeradas (16), DV e DE = 1 (85 e 86) e 87 a 150 zeradas (64).
     */
    static final String AMOSTRA = "010000123003990005QUEIJO MUCARELA FATIADO  ESPECIAL KG              "
            + "0000000000000000" + "11" + "0".repeat(64);

    private static String amostra() {
        return LayoutMgv.linha("Queijo Muçarela Fatiado Especial Kg", 123, new BigDecimal("39.90"), 5);
    }

    @Test
    void linhaDeAmostraInteira() {
        assertThat(AMOSTRA).hasSize(150);
        assertThat(amostra()).hasSize(150).isEqualTo(AMOSTRA);
    }

    @Test
    void linhaDeAmostraCampoACampo() {
        String l = amostra();
        assertThat(campo(l, 1, 2)).as("DD departamento").isEqualTo("01");
        assertThat(campo(l, 3, 3)).as("T tipo (0 = peso)").isEqualTo("0");
        assertThat(campo(l, 4, 9)).as("CCCCCC código = PLU").isEqualTo("000123");
        assertThat(campo(l, 10, 15)).as("PPPPPP preço por kg em centavos").isEqualTo("003990");
        assertThat(campo(l, 16, 18)).as("VVV dias de validade").isEqualTo("005");
        assertThat(campo(l, 19, 43)).as("D1 descrição, linha 1").isEqualTo("QUEIJO MUCARELA FATIADO  ");
        assertThat(campo(l, 44, 68)).as("D2 descrição, linha 2").isEqualTo("ESPECIAL KG              ");
        assertThat(campo(l, 69, 74)).as("RRRRRR informação extra").isEqualTo("000000");
        assertThat(campo(l, 75, 78)).as("FFFF imagem").isEqualTo("0000");
        assertThat(campo(l, 79, 84)).as("IIIIII informação nutricional").isEqualTo("000000");
        assertThat(campo(l, 85, 85)).as("DV imprime validade").isEqualTo("1");
        assertThat(campo(l, 86, 86)).as("DE imprime embalagem").isEqualTo("1");
        assertThat(campo(l, 87, 90)).as("CF fornecedor").isEqualTo("0000");
        assertThat(campo(l, 91, 102)).as("L lote").isEqualTo("000000000000");
        assertThat(campo(l, 103, 113)).as("G EAN-13 especial").isEqualTo("00000000000");
        assertThat(campo(l, 114, 114)).as("Z versão do preço").isEqualTo("0");
        assertThat(campo(l, 115, 118)).as("CS som").isEqualTo("0000");
        assertThat(campo(l, 119, 122)).as("CT tara").isEqualTo("0000");
        assertThat(campo(l, 123, 126)).as("FR fracionador").isEqualTo("0000");
        assertThat(campo(l, 127, 130)).as("CE1").isEqualTo("0000");
        assertThat(campo(l, 131, 134)).as("CE2").isEqualTo("0000");
        assertThat(campo(l, 135, 138)).as("CON conservação").isEqualTo("0000");
        assertThat(campo(l, 139, 150)).as("EAN do fornecedor").isEqualTo("000000000000");
    }

    /** Posições como no manual: a primeira é 1, as duas pontas entram. */
    private static String campo(String linha, int de, int ate) {
        return linha.substring(de - 1, ate);
    }

    @Test
    void semValidadeSai000eNaoImprimeDatas() {
        String l = LayoutMgv.linha("Picanha", 7, new BigDecimal("89.9"), null);
        assertThat(campo(l, 4, 9)).isEqualTo("000007");
        assertThat(campo(l, 10, 15)).isEqualTo("008990");
        assertThat(campo(l, 16, 18)).isEqualTo("000");
        assertThat(campo(l, 19, 43)).isEqualTo("PICANHA                  ");
        assertThat(campo(l, 44, 68)).isBlank();
        assertThat(campo(l, 85, 86)).isEqualTo("00");
        assertThat(LayoutMgv.linha("Picanha", 7, new BigDecimal("89.90"), 0)).isEqualTo(l);
    }

    @Test
    void descricaoQuebraNoUltimoEspacoESemAcentos() {
        assertThat(LayoutMgv.descricao("  Pão   de queijo  ")).isEqualTo(new LayoutMgv.Descricao("PAO DE QUEIJO", ""));
        assertThat(LayoutMgv.descricao("Linguiça Toscana Açougue São João Coração"))
                .isEqualTo(new LayoutMgv.Descricao("LINGUICA TOSCANA ACOUGUE", "SAO JOAO CORACAO"));
        // exatamente 25 cabe numa linha só
        assertThat(LayoutMgv.descricao("ABCDEFGHIJ KLMNOPQRS TUVW")).isEqualTo(
                new LayoutMgv.Descricao("ABCDEFGHIJ KLMNOPQRS TUVW", ""));
        // espaço na 26ª posição: a linha 1 fica com 25
        assertThat(LayoutMgv.descricao("ABCDEFGHIJ KLMNOPQRS TUVW YZ")).isEqualTo(
                new LayoutMgv.Descricao("ABCDEFGHIJ KLMNOPQRS TUVW", "YZ"));
        // a palavra que passaria da 25ª posição vai inteira para a linha 2
        assertThat(LayoutMgv.descricao("ABCDEFGHIJ KLMNOPQRS TUVWX YZ")).isEqualTo(
                new LayoutMgv.Descricao("ABCDEFGHIJ KLMNOPQRS", "TUVWX YZ"));
        // palavra sem espaço: corta em 25
        assertThat(LayoutMgv.descricao("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123")).isEqualTo(
                new LayoutMgv.Descricao("ABCDEFGHIJKLMNOPQRSTUVWXY", "Z0123"));
        // resto maior que 25: a linha 2 é cortada
        LayoutMgv.Descricao longa = LayoutMgv.descricao("Queijo Prato Fatiado Tipo Lanche Selecionado Especial Da Casa");
        assertThat(longa.linha1()).isEqualTo("QUEIJO PRATO FATIADO TIPO");
        assertThat(longa.linha2()).isEqualTo("LANCHE SELECIONADO ESPECI");
        assertThat(longa.juntas()).isEqualTo("QUEIJO PRATO FATIADO TIPO LANCHE SELECIONADO ESPECI");
        // fora do ASCII visível vira espaço
        assertThat(LayoutMgv.descricao("Café ½ kg — moído").linha1()).isEqualTo("CAFE 1/2 KG MOIDO");
        assertThat(LayoutMgv.descricao("Ç°x").linha1()).isEqualTo("C X");
    }

    @Test
    void limitesDoLayout() {
        assertThat(campo(LayoutMgv.linha("Caro", 999_999, new BigDecimal("9999.99"), 990), 4, 18))
                .isEqualTo("999999999999990");
        assertThatThrownBy(() -> LayoutMgv.linha("Trufa", 1, new BigDecimal("10000.00"), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("\"Trufa\"")
                .hasMessageContaining("não cabe na balança");
        assertThatThrownBy(() -> LayoutMgv.linha("Grátis", 1, BigDecimal.ZERO, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("sem preço");
        assertThatThrownBy(() -> LayoutMgv.linha("X", 0, BigDecimal.ONE, null)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> LayoutMgv.linha("X", 1_000_000, BigDecimal.ONE, null))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> LayoutMgv.linha("X", 1, BigDecimal.ONE, 991))
                .isInstanceOf(NegocioException.class).hasMessageContaining("990");
    }

    @Test
    void validacaoDosDiasNoCadastro() {
        assertThat(ValidacaoProduto.validadeBalanca(null)).isNull();
        assertThat(ValidacaoProduto.validadeBalanca(0)).isZero();
        assertThat(ValidacaoProduto.validadeBalanca(990)).isEqualTo(990);
        assertThatThrownBy(() -> ValidacaoProduto.validadeBalanca(998)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("entre 1 e 990");
        assertThatThrownBy(() -> ValidacaoProduto.validadeBalanca(-1)).isInstanceOf(NegocioException.class);
    }
}
