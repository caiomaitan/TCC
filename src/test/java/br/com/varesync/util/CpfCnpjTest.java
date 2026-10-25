package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CPF e CNPJ numérico e alfanumérico: vetores de A.2 e A.3. */
class CpfCnpjTest {

    @Test
    void cpfMascaradoMostraSoOsSeisDigitosDoMeioECnpjVaiInteiro() {
        assertThat(CpfCnpj.ocultar("12345678909")).as("exemplo da F12.2").isEqualTo("***.456.789-**");
        assertThat(CpfCnpj.ocultar("52998224725")).isEqualTo("***.982.247-**");
        assertThat(CpfCnpj.ocultar("12ABC34501DE35")).isEqualTo("12.ABC.345/01DE-35");
        assertThat(CpfCnpj.ocultar(null)).isEmpty();
    }

    @Test
    void cpfDoApendiceA2() {
        assertThat(CpfCnpj.cpfValido("52998224725")).isTrue();
        assertThat(CpfCnpj.cpfValido("52998224724")).isFalse();
        assertThat(CpfCnpj.cpfValido("11111111111")).as("dígitos todos iguais").isFalse();
    }

    @Test
    void cnpjNumericoDoApendiceA3() {
        assertThat(CpfCnpj.cnpjValido("11222333000181")).isTrue();
        assertThat(CpfCnpj.cnpjValido("11222333000180")).isFalse();
        assertThat(CpfCnpj.cnpjValido("00000000000000")).as("caracteres todos iguais").isFalse();
    }

    @Test
    void cnpjAlfanumericoDoApendiceA3() {
        assertThat(CpfCnpj.cnpjValido("12ABC34501DE35")).as("exemplo da Receita Federal").isTrue();
        assertThat(CpfCnpj.cnpjValido("12ABC34501DE34")).isFalse();
        assertThat(CpfCnpj.cnpjValido("AB12CD34EFGH83")).isTrue();
        assertThat(CpfCnpj.cnpjValido("12ABC34501DE3X")).as("DV tem de ser numérico").isFalse();
        assertThat(CpfCnpj.cnpjValido("12abc34501de35")).as("só maiúsculas: normalizar antes").isFalse();
    }

    @Test
    void normalizaATiraMascaraEPassaParaMaiusculas() {
        assertThat(CpfCnpj.normalizar(" 12.abc.345/01de-35 ")).isEqualTo("12ABC34501DE35");
        assertThat(CpfCnpj.normalizar("529.982.247-25")).isEqualTo("52998224725");
        assertThat(CpfCnpj.normalizar(null)).isEmpty();
    }

    @Test
    void formataComMascara() {
        assertThat(CpfCnpj.formatar("52998224725")).isEqualTo("529.982.247-25");
        assertThat(CpfCnpj.formatar("11222333000181")).isEqualTo("11.222.333/0001-81");
        assertThat(CpfCnpj.formatar("12ABC34501DE35")).isEqualTo("12.ABC.345/01DE-35");
        assertThat(CpfCnpj.formatar("123")).isEqualTo("123");
    }

    @Test
    void mensagensDeValidacao() {
        assertThat(CpfCnpj.validar("52998224725")).isEmpty();
        assertThat(CpfCnpj.validar("12ABC34501DE35")).isEmpty();
        assertThat(CpfCnpj.validar("52998224724")).hasValue(
                "CPF 529.982.247-24 inválido: o dígito verificador não confere.");
        assertThat(CpfCnpj.validar("12ABC34501DE34")).hasValue(
                "CNPJ 12.ABC.345/01DE-34 inválido: o dígito verificador não confere.");
        assertThat(CpfCnpj.validar("12ABC34501DEX5")).get().asString().contains("2 últimas só números");
        assertThat(CpfCnpj.validar("1234")).hasValue("Informe o CPF com 11 dígitos ou o CNPJ com 14 posições.");
        assertThat(CpfCnpj.validar("5299822472A")).as("CPF com letra")
                .hasValue("Informe o CPF com 11 dígitos ou o CNPJ com 14 posições.");
    }
}
