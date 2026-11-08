package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Formato dos dados fiscais (F19.1 a F19.3): só o formato, sem as combinações (que são do R30). Sem banco. */
class ValidacaoFiscalTest {

    @Test
    void ufDaLojaEUmaDas27SiglasOuVazia() {
        assertThat(ValidacaoFiscal.uf(" sp ")).isEqualTo("SP");
        assertThat(ValidacaoFiscal.uf("")).isEmpty();
        assertThat(ValidacaoFiscal.uf(null)).isEmpty();
        assertThatThrownBy(() -> ValidacaoFiscal.uf("XX")).hasMessageContaining("UF da loja inválida");
        assertThatThrownBy(() -> ValidacaoFiscal.uf("São Paulo")).isInstanceOf(NegocioException.class);
    }

    @Test
    void ieSoDigitosOuIsento() {
        assertThat(ValidacaoFiscal.ie("123.456.789.110")).isEqualTo("123456789110");
        assertThat(ValidacaoFiscal.ie("isento")).isEqualTo("ISENTO");
        assertThat(ValidacaoFiscal.ie(" ")).isEmpty();
        assertThatThrownBy(() -> ValidacaoFiscal.ie("12A45")).hasMessageContaining("só dígitos");
        assertThatThrownBy(() -> ValidacaoFiscal.ie("123456789012345")).as("15 dígitos")
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void ncmTem8DigitosComOuSemPontos() {
        assertThat(ValidacaoFiscal.ncm("2203.00.00")).isEqualTo("22030000");
        assertThat(ValidacaoFiscal.ncm("22030000")).isEqualTo("22030000");
        assertThat(ValidacaoFiscal.ncm("  ")).isNull();
        assertThatThrownBy(() -> ValidacaoFiscal.ncm("2203")).hasMessageContaining("8 dígitos");
        assertThatThrownBy(() -> ValidacaoFiscal.ncm("2203.00.0A")).isInstanceOf(NegocioException.class);
    }

    @Test
    void cestTem7Digitos() {
        assertThat(ValidacaoFiscal.cest("03.001.00")).isEqualTo("0300100");
        assertThat(ValidacaoFiscal.cest(null)).isNull();
        assertThatThrownBy(() -> ValidacaoFiscal.cest("0300100-1")).hasMessageContaining("7 dígitos");
    }

    @Test
    void cfopCstCsosnEPisCofins() {
        assertThat(ValidacaoFiscal.cfop("5.102")).isEqualTo("5102");
        assertThatThrownBy(() -> ValidacaoFiscal.cfop("")).hasMessage("Informe o CFOP.");
        assertThatThrownBy(() -> ValidacaoFiscal.cfop("510")).hasMessageContaining("4 dígitos");
        assertThat(ValidacaoFiscal.cstIcms("00")).isEqualTo("00");
        assertThatThrownBy(() -> ValidacaoFiscal.cstIcms("0")).hasMessageContaining("2 dígitos");
        assertThat(ValidacaoFiscal.csosn("102")).isEqualTo("102");
        assertThatThrownBy(() -> ValidacaoFiscal.csosn("10")).hasMessageContaining("3 dígitos");
        assertThat(ValidacaoFiscal.cstPisCofins("49", "PIS")).isEqualTo("49");
        assertThatThrownBy(() -> ValidacaoFiscal.cstPisCofins(null, "COFINS")).hasMessage("Informe o CST do COFINS.");
    }

    @Test
    void reformaTributariaOpcionalSoNoFormato() {
        assertThat(ValidacaoFiscal.cstIbsCbs("")).isNull();
        assertThat(ValidacaoFiscal.cstIbsCbs("000")).isEqualTo("000");
        assertThatThrownBy(() -> ValidacaoFiscal.cstIbsCbs("00")).hasMessageContaining("3 dígitos");
        assertThat(ValidacaoFiscal.cclassTrib("000001")).isEqualTo("000001");
        // Começar pelo CST é combinação (aviso do R30), não formato.
        assertThat(ValidacaoFiscal.cclassTrib("200001")).isEqualTo("200001");
        assertThatThrownBy(() -> ValidacaoFiscal.cclassTrib("00001")).hasMessageContaining("6 dígitos");
    }

    @ParameterizedTest
    @CsvSource({"SP010360, SP010360", "sp010360, SP010360", "sem cbenef, SEM CBENEF", "' SEM  CBENEF ', SEM CBENEF",
            "RJ850001, RJ850001", "PRABC123, PRABC123"})
    void codigoDeBeneficioValido(String digitado, String gravado) {
        assertThat(ValidacaoFiscal.codigoBeneficio(digitado)).isEqualTo(gravado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SP01036", "SP0103600", "XX010360", "12345678", "SP-10360"})
    void codigoDeBeneficioInvalido(String digitado) {
        assertThatThrownBy(() -> ValidacaoFiscal.codigoBeneficio(digitado))
                .hasMessageContaining("começa pela sigla da UF");
    }

    @Test
    void codigoDeBeneficioVazioENulo() {
        assertThat(ValidacaoFiscal.codigoBeneficio(" ")).isNull();
    }

    @Test
    void aliquotaEReducao() {
        assertThat(ValidacaoFiscal.aliquota(null, "do ICMS")).isEqualByComparingTo("0");
        assertThat(ValidacaoFiscal.aliquota(new BigDecimal("18"), "do ICMS")).isEqualByComparingTo("18.00");
        assertThatThrownBy(() -> ValidacaoFiscal.aliquota(new BigDecimal("100.01"), "do ICMS"))
                .hasMessageContaining("entre 0 e 100");
        assertThatThrownBy(() -> ValidacaoFiscal.aliquota(new BigDecimal("1.655"), "do PIS"))
                .hasMessageContaining("2 casas");
        assertThat(ValidacaoFiscal.reducaoBase(null)).isNull();
        assertThat(ValidacaoFiscal.reducaoBase(BigDecimal.ZERO)).as("zero = sem redução").isNull();
        assertThat(ValidacaoFiscal.reducaoBase(new BigDecimal("33.33"))).isEqualByComparingTo("33.33");
        assertThatThrownBy(() -> ValidacaoFiscal.reducaoBase(new BigDecimal("100")))
                .hasMessageContaining("menor que 100");
    }

    @Test
    void origemDe0a8() {
        assertThat(ValidacaoFiscal.origem(null)).isNull();
        assertThat(ValidacaoFiscal.origem(8)).isEqualTo(8);
        assertThatThrownBy(() -> ValidacaoFiscal.origem(9)).hasMessageContaining("0 a 8");
    }

    @Test
    void formatacao() {
        assertThat(ValidacaoFiscal.formatarNcm("22030000")).isEqualTo("2203.00.00");
        assertThat(ValidacaoFiscal.formatarCest("0300100")).isEqualTo("03.001.00");
        assertThat(ValidacaoFiscal.formatarNcm("2203")).isEqualTo("2203");
        assertThat(ValidacaoFiscal.formatarNcm(null)).isEmpty();
    }

    @Test
    void nomeDoPerfil() {
        assertThat(ValidacaoFiscal.nomePerfil("  Tributado   18% ")).isEqualTo("Tributado 18%");
        assertThatThrownBy(() -> ValidacaoFiscal.nomePerfil(" ")).hasMessage("Informe o nome do perfil tributário.");
        assertThatThrownBy(() -> ValidacaoFiscal.nomePerfil("x".repeat(61))).hasMessageContaining("60 caracteres");
    }
}
