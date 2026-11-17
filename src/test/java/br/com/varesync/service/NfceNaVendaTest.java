package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.Configuracoes;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** F23.6: o CPF ou CNPJ na nota, conferido pelo dígito, e os limites de valor (identificação e NF-e). */
class NfceNaVendaTest {

    private static final Configuracoes.Nfce PADRAO = Configuracoes.Nfce.PADRAO; // 10.000 e 200.000

    @Test
    void documentoOpcionalAbaixoDoValorDeIdentificacao() {
        assertThat(NfceNaVenda.consumidor("", new BigDecimal("9999.99"), PADRAO)).isNull();
        assertThat(NfceNaVenda.consumidor(null, new BigDecimal("10.98"), PADRAO)).isNull();
        assertThat(NfceNaVenda.consumidor("529.982.247-25", new BigDecimal("10.98"), PADRAO))
                .isEqualTo("52998224725");
        assertThat(NfceNaVenda.consumidor("11.222.333/0001-81", new BigDecimal("10.98"), PADRAO))
                .isEqualTo("11222333000181");
    }

    @Test
    void cpfInvalidoERecusado() {
        assertThatThrownBy(() -> NfceNaVenda.consumidor("529.982.247-24", new BigDecimal("10.98"), PADRAO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("CPF/CNPJ na nota")
                .hasMessageContaining("dígito verificador");
        assertThatThrownBy(() -> NfceNaVenda.consumidor("123", new BigDecimal("10.98"), PADRAO))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> NfceNaVenda.consumidor("12ABC34501DE35", new BigDecimal("10.98"), PADRAO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("letras");
    }

    @Test
    void aPartirDoValorDeIdentificacaoODocumentoEObrigatorio() {
        assertThatThrownBy(() -> NfceNaVenda.consumidor("", new BigDecimal("10000.00"), PADRAO))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Informe o CPF ou o CNPJ na nota: a partir de R$ 10.000,00 a NFC-e exige a identificação "
                        + "do consumidor.");
        assertThat(NfceNaVenda.consumidor("52998224725", new BigDecimal("10000.00"), PADRAO))
                .isEqualTo("52998224725");
    }

    @Test
    void aPartirDoValorMaximoAVendaNaoFecha() {
        assertThatThrownBy(() -> NfceNaVenda.consumidor("52998224725", new BigDecimal("200000.00"), PADRAO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("exige NF-e");
    }
}
