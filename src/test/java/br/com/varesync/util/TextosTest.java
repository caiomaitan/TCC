package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TextosTest {

    @Test
    void vazio() {
        assertThat(Textos.vazio(null)).isTrue();
        assertThat(Textos.vazio("")).isTrue();
        assertThat(Textos.vazio("  \t ")).isTrue();
        assertThat(Textos.vazio(" a ")).isFalse();
    }

    @Test
    void normalizaEspacos() {
        assertThat(Textos.normalizarEspacos("  Café   Pilão \t 500 g  ")).isEqualTo("Café Pilão 500 g");
        assertThat(Textos.normalizarEspacos(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "Açúcar Cristal 1kg, acucar cristal 1kg",       // mesmo valor que o banco grava em nome_busca (cenário 1 do script)
            "AÇÚCAR, acucar",
            "acucar, acucar",
            "Pão Francês, pao frances",
            "MAÇÃ Fuji, maca fuji",
            "Pêssego em Calda, pessego em calda",
            "Água Mineral  500ml, agua mineral 500ml",
            "Ñandu Ü, nandu u"
    })
    void normalizaBuscaComoONomeBuscaDoBanco(String texto, String esperado) {
        assertThat(Textos.normalizarBusca(texto)).isEqualTo(esperado);
    }

    @Test
    void buscarAcucarEncontraAcucarComAcento() {
        String nomeBusca = Textos.normalizarBusca("Açúcar Refinado União 1kg");
        assertThat(nomeBusca).contains(Textos.normalizarBusca("acucar"));
        assertThat(nomeBusca).contains(Textos.normalizarBusca("AÇÚCAR"));
    }

    @Test
    void semAcentoPreservaOResto() {
        assertThat(Textos.semAcento("Refrigerante Cola PET 2L")).isEqualTo("Refrigerante Cola PET 2L");
        assertThat(Textos.semAcento("ÁÀÂÃÄ éêë íï óôõö úü çñ")).isEqualTo("AAAAA eee ii oooo uu cn");
    }

    @Test
    void normalizaLogin() {
        assertThat(Textos.normalizarLogin("  Joao.Silva ")).isEqualTo("joao.silva");
        assertThat(Textos.normalizarLogin(null)).isEmpty();
    }

    @Test
    void digitos() {
        assertThat(Textos.soDigitos("7891000315507")).isTrue();
        assertThat(Textos.soDigitos("789 100")).isFalse();
        assertThat(Textos.soDigitos("")).isFalse();
        assertThat(Textos.soDigitos(null)).isFalse();
        assertThat(Textos.apenasDigitos("11.222.333/0001-81")).isEqualTo("11222333000181");
        assertThat(Textos.apenasDigitos(null)).isEmpty();
    }

    @Test
    void vazioSeNuloSoAparaAsPontas() {
        assertThat(Textos.vazioSeNulo(null)).isEmpty();
        assertThat(Textos.vazioSeNulo("  Rua  das Flores ")).isEqualTo("Rua  das Flores");
    }
}
