package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra09StSemCestTest {

    private final RegraFiscal regra = new Regra09StSemCest();

    @Test
    void disparaComStSemCest() {
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").csosn("500")).montar(), SIMPLES))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("CSOSN 500", "sem CEST"));
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").cst("60")).montar(), NORMAL)).hasSize(1);
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").csosn("500")).cest("0302100").montar(),
                SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty(); // sem ST
    }
}
