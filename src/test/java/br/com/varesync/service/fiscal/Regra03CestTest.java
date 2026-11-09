package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra03CestTest {

    private final RegraFiscal regra = new Regra03Cest();

    @Test
    void disparaComFormatoInvalido() {
        assertThat(regra.verificar(item().cest("030210").montar(), SIMPLES)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("7 dígitos"));
    }

    @Test
    void disparaForaDaTabelaOuDeOutroNcm() {
        assertThat(regra.verificar(item().cest("0302100").tabelas(null, false, null).montar(), SIMPLES))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("03.021.00", "não existe"));
        assertThat(regra.verificar(item().cest("0302100").tabelas(null, true, false).montar(), SIMPLES))
                .singleElement().satisfies(i -> assertThat(i.mensagem())
                        .contains("não corresponde ao NCM 2203.00.00"));
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty(); // sem CEST
        assertThat(regra.verificar(item().cest("0302100").montar(), SIMPLES)).isEmpty(); // sem tabela
        assertThat(regra.verificar(item().cest("0302100").tabelas(null, true, true).montar(), SIMPLES)).isEmpty();
    }
}
