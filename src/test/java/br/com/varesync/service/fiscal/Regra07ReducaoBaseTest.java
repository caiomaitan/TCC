package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra07ReducaoBaseTest {

    private final RegraFiscal regra = new Regra07ReducaoBase();

    @Test
    void disparaComCst20Ou70SemReducao() {
        assertThat(regra.verificar(item().perfil(perfil().cst("20").icms("18")).montar(), NORMAL)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("CST 20", "não foi informado"));
        assertThat(regra.verificar(item().perfil(perfil().cst("70").icms("18")).montar(), NORMAL)).hasSize(1);
    }

    @Test
    void disparaComReducaoEmOutroCstOuCsosn() {
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18").reducao("33.33")).montar(), NORMAL))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("33,33%", "CST 00"));
        assertThat(regra.verificar(item().perfil(perfil().reducao("10")).montar(), SIMPLES)).hasSize(1);
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().perfil(perfil().cst("20").icms("18").reducao("33.33")).montar(), NORMAL))
                .isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18")).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
    }
}
