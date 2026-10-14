package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Vetores de A.1 (GTIN-8/12/13/14). */
class GtinTest {

    @ParameterizedTest
    @CsvSource({
            "789100031550, 7",
            "789123456789, 5",
            "1234567, 0"
    })
    void digitoVerificadorDoApendiceA1(String semDigito, int esperado) {
        assertThat(Gtin.digitoVerificador(semDigito)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7891000315507", "7891234567895", "12345670", "036000291452", "17891000315504"})
    void codigosValidos(String codigo) {
        assertThat(Gtin.valido(codigo)).isTrue();
        assertThat(Gtin.validar(codigo)).isEmpty();
    }

    @Test
    void digitoVerificadorErradoEInvalido() {
        assertThat(Gtin.valido("7891234567890")).isFalse();
        assertThat(Gtin.validar("7891234567890"))
                .contains("Código de barras 7891234567890 inválido: o dígito verificador não confere.");
    }

    @Test
    void tamanhoForaDoGtinEInvalido() {
        assertThat(Gtin.validar("123456789")).contains("O código de barras deve ter 8, 12, 13 ou 14 dígitos.");
        assertThat(Gtin.validar("1234567")).contains("O código de barras deve ter 8, 12, 13 ou 14 dígitos.");
        assertThat(Gtin.validar("789100031550712")).isPresent();
    }

    @Test
    void soAceitaDigitos() {
        assertThat(Gtin.validar("78910003155O7")).contains("O código de barras deve ter só números.");
        assertThat(Gtin.validar(" 7891000315507")).contains("O código de barras deve ter só números.");
        assertThat(Gtin.validar("")).contains("Informe o código de barras.");
        assertThat(Gtin.validar(null)).contains("Informe o código de barras.");
        assertThatThrownBy(() -> Gtin.digitoVerificador("12a")).isInstanceOf(IllegalArgumentException.class);
    }
}
