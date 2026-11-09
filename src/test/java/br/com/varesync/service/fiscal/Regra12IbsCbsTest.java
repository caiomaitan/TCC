package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra12IbsCbsTest {

    private final RegraFiscal regra = new Regra12IbsCbs();

    @Test
    void avisaNoRegimeNormalSemCstOuSemClassTrib() {
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18")).montar(), NORMAL)).singleElement()
                .satisfies(i -> {
                    assertThat(i.gravidade()).isEqualTo(Gravidade.AVISO);
                    assertThat(i.mensagem()).contains("o CST do IBS e da CBS e o cClassTrib");
                });
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18").ibsCbs("000", null)).montar(),
                NORMAL)).singleElement().satisfies(i -> assertThat(i.mensagem()).contains("sem o cClassTrib"));
    }

    @Test
    void avisaClassTribQueNaoComecaPeloCst() {
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18").ibsCbs("200", "000001")).montar(),
                NORMAL)).singleElement().satisfies(i -> assertThat(i.mensagem()).contains("000001", "(200)"));
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18").ibsCbs("000", "000001")).montar(),
                NORMAL)).isEmpty();
        // No Simples, sem IBS e CBS no perfil não é aviso (NT 2026.005), nem com cClassTrib de outro CST.
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().ibsCbs("200", "000001")).montar(), SIMPLES)).isEmpty();
    }
}
