package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Valores em dinheiro do caixa (fundo de troco, sangria/suprimento, contado): regras de {@code CaixaService.valor}. */
class CaixaValorTest {

    @Test
    void aceitaAteDuasCasasEDevolveComEscala2() {
        assertThat(CaixaService.valor(new BigDecimal("100"), "o valor", false).toPlainString()).isEqualTo("100.00");
        assertThat(CaixaService.valor(new BigDecimal("0.5"), "o valor", false).toPlainString()).isEqualTo("0.50");
        assertThat(CaixaService.valor(new BigDecimal("10.500"), "o valor", false).toPlainString())
                .isEqualTo("10.50");
        assertThat(CaixaService.valor(new BigDecimal("9999999999.99"), "o valor", false))
                .isEqualByComparingTo("9999999999.99");
    }

    @Test
    void zeroSoQuandoPermitido() {
        assertThat(CaixaService.valor(BigDecimal.ZERO, "o fundo de troco", true)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> CaixaService.valor(BigDecimal.ZERO, "o valor", false))
                .isInstanceOf(NegocioException.class).hasMessage("O valor deve ser maior que zero.");
    }

    @Test
    void recusaVazioNegativoTresCasasEAcimaDoLimite() {
        assertThatThrownBy(() -> CaixaService.valor(null, "o dinheiro contado", true))
                .hasMessage("Informe o dinheiro contado.");
        assertThatThrownBy(() -> CaixaService.valor(new BigDecimal("-1"), "o dinheiro contado", true))
                .hasMessage("O dinheiro contado não pode ser negativo.");
        assertThatThrownBy(() -> CaixaService.valor(new BigDecimal("1.005"), "o valor", false))
                .hasMessage("O valor pode ter no máximo 2 casas decimais.");
        assertThatThrownBy(() -> CaixaService.valor(new BigDecimal("10000000000"), "o valor", false))
                .hasMessage("O valor está acima do limite permitido.");
    }
}
