package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validações do cadastro de produto (6.3), sem banco. */
class ValidacaoProdutoTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void nomeNormalizadoCom2A120Caracteres() {
        assertThat(ValidacaoProduto.nome("  Açúcar   cristal  1 kg ")).isEqualTo("Açúcar cristal 1 kg");
        assertThat(ValidacaoProduto.nome("Ab")).isEqualTo("Ab");
        assertThat(ValidacaoProduto.nome("x".repeat(120))).hasSize(120);
        assertThatThrownBy(() -> ValidacaoProduto.nome("   ")).hasMessage("Informe o nome do produto.");
        assertThatThrownBy(() -> ValidacaoProduto.nome(" A ")).hasMessage("O nome do produto deve ter ao menos 2 caracteres.");
        assertThatThrownBy(() -> ValidacaoProduto.nome("x".repeat(121)))
                .hasMessage("O nome do produto pode ter no máximo 120 caracteres.");
    }

    @Test
    void precoECustoObrigatoriosNaoNegativosEComCasasLimitadas() {
        assertThat(ValidacaoProduto.preco(bd("5.5"))).isEqualByComparingTo("5.50").hasScaleOf(2);
        assertThat(ValidacaoProduto.preco(bd("0"))).isEqualByComparingTo("0");
        assertThat(ValidacaoProduto.custo(bd("0.8333"))).hasScaleOf(4);
        assertThatThrownBy(() -> ValidacaoProduto.preco(null)).hasMessage("Informe o preço de venda.");
        assertThatThrownBy(() -> ValidacaoProduto.preco(bd("-0.01"))).hasMessage("O preço de venda não pode ser negativo.");
        assertThatThrownBy(() -> ValidacaoProduto.preco(bd("5.499")))
                .hasMessage("O preço de venda pode ter no máximo 2 casas decimais.");
        assertThatThrownBy(() -> ValidacaoProduto.custo(null)).hasMessage("Informe o custo.");
        assertThatThrownBy(() -> ValidacaoProduto.custo(bd("-1"))).hasMessage("O custo não pode ser negativo.");
        assertThatThrownBy(() -> ValidacaoProduto.custo(bd("0.83333")))
                .hasMessage("O custo pode ter no máximo 4 casas decimais.");
        assertThatThrownBy(() -> ValidacaoProduto.preco(bd("10000000000")))
                .hasMessage("O preço de venda está acima do limite permitido.");
    }

    @Test
    void quantidadeInteiraParaUnidadeNaoFracionada() {
        assertThat(ValidacaoProduto.quantidade(bd("10"), "O estoque inicial", false)).isEqualByComparingTo("10");
        assertThat(ValidacaoProduto.quantidade(bd("10.000"), "O estoque inicial", false)).isEqualByComparingTo("10");
        assertThat(ValidacaoProduto.quantidade(bd("0.345"), "O estoque inicial", true)).hasScaleOf(3);
        assertThatThrownBy(() -> ValidacaoProduto.quantidade(bd("1.5"), "O estoque inicial", false))
                .hasMessage("O estoque inicial deve ser um número inteiro: a unidade do produto não aceita "
                        + "quantidade fracionada (1,5).");
        assertThatThrownBy(() -> ValidacaoProduto.quantidade(bd("0.3455"), "O estoque inicial", true))
                .hasMessage("O estoque inicial pode ter no máximo 3 casas decimais.");
        assertThatThrownBy(() -> ValidacaoProduto.quantidade(bd("-1"), "O estoque mínimo", false))
                .hasMessage("O estoque mínimo não pode ser negativo.");
    }

    @Test
    void codigosDeBarrasValidosSemRepeticaoEComUmPrincipal() {
        List<CodigoBarras> semPrincipal = ValidacaoProduto.codigos(List.of(
                new CodigoBarras(" 7891000315507 ", false), new CodigoBarras("12345670", false)));
        assertThat(semPrincipal).containsExactly(
                new CodigoBarras("7891000315507", true), new CodigoBarras("12345670", false));

        List<CodigoBarras> principalNoFim = ValidacaoProduto.codigos(List.of(
                new CodigoBarras("7891000315507", false), new CodigoBarras("12345670", true)));
        assertThat(principalNoFim).containsExactly(
                new CodigoBarras("12345670", true), new CodigoBarras("7891000315507", false));

        assertThat(ValidacaoProduto.codigos(List.of())).isEmpty();
        assertThatThrownBy(() -> ValidacaoProduto.codigos(List.of(new CodigoBarras("7891234567890", false))))
                .hasMessage("Código de barras 7891234567890 inválido: o dígito verificador não confere.");
        assertThatThrownBy(() -> ValidacaoProduto.codigos(List.of(
                new CodigoBarras("7891000315507", false), new CodigoBarras("7891000315507", false))))
                .hasMessage("O código de barras 7891000315507 foi informado mais de uma vez.");
        assertThatThrownBy(() -> ValidacaoProduto.codigos(List.of(
                new CodigoBarras("7891000315507", true), new CodigoBarras("12345670", true))))
                .hasMessage("Marque só um código de barras como principal.");
    }

    @Test
    void pluSoComBalancaAtivaEUnidadeFracionada() {
        ValidacaoProduto.plu(null, false, false, false);
        ValidacaoProduto.plu(123, true, true, false);
        ValidacaoProduto.plu(123, true, false, true); // já gravado: continua mesmo com a balança desligada
        assertThatThrownBy(() -> ValidacaoProduto.plu(123, true, false, false))
                .hasMessageContaining("balança ativa");
        assertThatThrownBy(() -> ValidacaoProduto.plu(123, false, true, false))
                .hasMessageContaining("unidade fracionada");
        assertThatThrownBy(() -> ValidacaoProduto.plu(0, true, true, false))
                .hasMessage("O PLU deve estar entre 1 e 999999.");
    }

    @Test
    void pluCabeNosDigitosDaEtiqueta() {
        ValidacaoProduto.plu(9999, true, true, false, 9999);
        assertThatThrownBy(() -> ValidacaoProduto.plu(12345, true, true, false, 9999))
                .hasMessage("O PLU deve estar entre 1 e 9999 (a etiqueta da balança tem 4 dígitos de código).");
    }

    @Test
    void sinalEOrigemDoMovimentoPorTipo() {
        assertThat(TipoMovimento.AJUSTE_ENTRADA.aceitaQuantidade(1)).isTrue();
        assertThat(TipoMovimento.AJUSTE_ENTRADA.aceitaQuantidade(-1)).isFalse();
        assertThat(TipoMovimento.PERDA.aceitaQuantidade(-1)).isTrue();
        assertThat(TipoMovimento.INVENTARIO.aceitaQuantidade(-1)).isTrue();
        assertThat(TipoMovimento.INVENTARIO.aceitaQuantidade(0)).isFalse();
        assertThat(TipoMovimento.AJUSTE_SAIDA.exigeMotivo()).isTrue();
        assertThat(TipoMovimento.VENDA.exigeMotivo()).isFalse();

        assertThat(OrigemMovimento.NENHUMA.compativelCom(TipoMovimento.AJUSTE_ENTRADA)).isTrue();
        assertThat(OrigemMovimento.NENHUMA.compativelCom(TipoMovimento.VENDA)).isFalse();
        assertThat(OrigemMovimento.vendaItem(1).compativelCom(TipoMovimento.CANCELAMENTO_VENDA)).isTrue();
        assertThat(OrigemMovimento.inventarioItem(1).compativelCom(TipoMovimento.INVENTARIO)).isTrue();
        assertThat(OrigemMovimento.entradaItem(1).compativelCom(TipoMovimento.ENTRADA_COMPRA)).isTrue();
        assertThat(OrigemMovimento.entradaItem(1).compativelCom(TipoMovimento.AJUSTE_SAIDA)).isFalse();
    }
}
