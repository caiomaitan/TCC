package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra10OrigemTest {

    private final RegraFiscal regra = new Regra10Origem();

    @Test
    void disparaSemOrigem() {
        assertThat(regra.verificar(item().origem(null).montar(), SIMPLES)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("Origem"));
        assertThat(regra.verificar(item().origem(null).semPerfil().montar(), SIMPLES)).hasSize(1);
    }

    @Test
    void naoDisparaComOrigem() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().origem(2).montar(), SIMPLES)).isEmpty();
    }
}
