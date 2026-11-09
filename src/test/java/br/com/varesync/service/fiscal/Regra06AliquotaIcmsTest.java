package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Regra06AliquotaIcmsTest {

    private final RegraFiscal regra = new Regra06AliquotaIcms();

    @ParameterizedTest
    @ValueSource(strings = {"40", "41", "60"})
    void disparaComCstSemDestaqueEAliquota(String cst) {
        assertThat(regra.verificar(item().perfil(perfil().cst(cst).icms("18")).montar(), NORMAL)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("CST " + cst, "18,00%"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"102", "103", "300", "400", "500"})
    void disparaComCsosnSemDestaqueEAliquota(String csosn) {
        assertThat(regra.verificar(item().perfil(perfil().csosn(csosn).icms("4")).montar(), SIMPLES)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "20"})
    void disparaComCstTributadoSemAliquota(String cst) {
        assertThat(regra.verificar(item().perfil(perfil().cst(cst).reducao("10")).montar(), NORMAL))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("alíquota do ICMS é zero"));
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18")).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cst("60")).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().csosn("101").icms("2.5")).montar(), SIMPLES)).isEmpty();
    }
}
