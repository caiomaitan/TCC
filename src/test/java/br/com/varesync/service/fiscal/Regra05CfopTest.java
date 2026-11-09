package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra05CfopTest {

    private final RegraFiscal regra = new Regra05Cfop();

    @Test
    void disparaComCfopQueNaoEDeSaidaInterna() {
        assertThat(regra.verificar(item().perfil(perfil().cfop("6102")).montar(), SIMPLES)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("6102", "começa com 5"));
    }

    @Test
    void disparaCom5405SemSt() {
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").csosn("102")).montar(), SIMPLES))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("5405", "CSOSN 102"));
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").cst("00").icms("18")).montar(), NORMAL))
                .hasSize(1);
    }

    @Test
    void disparaCom5101Ou5102EST() {
        assertThat(regra.verificar(item().perfil(perfil().cfop("5102").csosn("500")).montar(), SIMPLES))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("5102", "5405"));
        assertThat(regra.verificar(item().perfil(perfil().cfop("5101").cst("60")).montar(), NORMAL)).hasSize(1);
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").csosn("500")).montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").cst("60")).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().semPerfil().montar(), SIMPLES)).isEmpty();
    }
}
