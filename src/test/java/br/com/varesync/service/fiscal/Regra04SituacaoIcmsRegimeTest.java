package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra04SituacaoIcmsRegimeTest {

    private final RegraFiscal regra = new Regra04SituacaoIcmsRegime();

    @Test
    void disparaComCsosnNoNormalECstNoSimples() {
        assertThat(regra.verificar(item().montar(), NORMAL)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("CSOSN 102", "regime normal"));
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18")).montar(), SIMPLES))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("CST 00", "Simples"));
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18")).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().semPerfil().montar(), NORMAL)).isEmpty();
    }
}
