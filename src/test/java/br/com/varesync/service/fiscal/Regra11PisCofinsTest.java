package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Regra11PisCofinsTest {

    private final RegraFiscal regra = new Regra11PisCofins();

    private static Fiscais.Perfil normal() {
        return perfil().cst("00").icms("18").pis("01", "1.65").cofins("01", "7.60");
    }

    @ParameterizedTest
    @ValueSource(strings = {"04", "06", "07", "08", "09"})
    void disparaComCstSemAliquotaEAliquota(String cst) {
        assertThat(regra.verificar(item().perfil(normal().pis(cst, "1.65").cofins(cst, "0")).montar(), NORMAL))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("CST " + cst + " do PIS", "1,65%"));
    }

    @Test
    void disparaComCst01Ou02SemAliquota() {
        assertThat(regra.verificar(item().perfil(normal().cofins("01", "0")).montar(), NORMAL)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("CST 01 da COFINS", "zero"));
        assertThat(regra.verificar(item().perfil(normal().pis("02", "0").cofins("02", "9")).montar(), NORMAL))
                .hasSize(1);
    }

    @Test
    void disparaComAliquotaNoSimples() {
        var achados = regra.verificar(item().perfil(perfil().pis("49", "0.65").cofins("49", "3")).montar(), SIMPLES);
        assertThat(achados).hasSize(2).allMatch(i -> i.gravidade() == Gravidade.ERRO)
                .allSatisfy(i -> assertThat(i.mensagem()).contains("Simples"));
        // CST sem alíquota e alíquota no Simples: uma mensagem só para o mesmo imposto.
        assertThat(regra.verificar(item().perfil(perfil().pis("04", "1").cofins("04", "0")).montar(), SIMPLES))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("CST 04 do PIS"));
    }

    @Test
    void avisaCstDoPisDiferenteDoDaCofins() {
        assertThat(regra.verificar(item().perfil(perfil().pis("49", "0").cofins("99", "0")).montar(), SIMPLES))
                .singleElement().satisfies(i -> {
                    assertThat(i.gravidade()).isEqualTo(Gravidade.AVISO);
                    assertThat(i.mensagem()).contains("(49)", "(99)");
                });
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().perfil(normal()).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().perfil(normal().pis("06", "0").cofins("06", "0")).montar(), NORMAL))
                .isEmpty();
        assertThat(regra.verificar(item().semPerfil().montar(), NORMAL)).isEmpty();
    }
}
