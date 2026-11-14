package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra16StRetidoTest {

    private final RegraFiscal regra = new Regra16StRetido();

    @Test
    void avisaStSemORetido() {
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").csosn("500")).montar(), SIMPLES))
                .singleElement().satisfies(i -> {
                    assertThat(i.erro()).isFalse();
                    assertThat(i.mensagem()).contains("CSOSN 500", "ST retido");
                });
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").cst("60")).montar(), NORMAL)).hasSize(1);
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().perfil(perfil().cfop("5405").csosn("500")).comStRetido().montar(),
                SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().montar(), SIMPLES)).as("sem ST").isEmpty();
        assertThat(regra.verificar(item().semPerfil().montar(), SIMPLES)).isEmpty();
    }
}
