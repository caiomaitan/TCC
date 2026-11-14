package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra15IbptTest {

    private final RegraFiscal regra = new Regra15Ibpt();

    @Test
    void avisaNcmSemLinhaVigente() {
        assertThat(regra.verificar(item().ibpt(false).montar(), SIMPLES)).singleElement().satisfies(i -> {
            assertThat(i.erro()).isFalse();
            assertThat(i.mensagem()).contains("2203.00.00", "tabela IBPT");
        });
    }

    @Test
    void naoDisparaComALinhaOuSemATabela() {
        assertThat(regra.verificar(item().ibpt(true).montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().ibpt(null).montar(), SIMPLES)).as("sem tabela importada").isEmpty();
    }
}
