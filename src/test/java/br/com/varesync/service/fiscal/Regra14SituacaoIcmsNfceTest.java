package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Regra14SituacaoIcmsNfceTest {

    private final RegraFiscal regra = new Regra14SituacaoIcmsNfce();

    @Test
    void disparaComSituacaoQueANfceNaoAceita() {
        for (String cst : List.of("10", "30", "51", "70", "90")) {
            assertThat(regra.verificar(item().perfil(perfil().cst(cst).icms("18")).montar(), NORMAL)).as(cst)
                    .singleElement().satisfies(i -> {
                        assertThat(i.erro()).isTrue();
                        assertThat(i.mensagem()).contains("CST " + cst, "CST 00, 20, 40, 41 e 60");
                    });
        }
        for (String csosn : List.of("101", "201", "202", "900")) {
            assertThat(regra.verificar(item().perfil(perfil().csosn(csosn)).montar(), SIMPLES)).as(csosn).hasSize(1);
        }
    }

    @Test
    void naoDispara() {
        for (String cst : List.of("00", "20", "40", "41", "60")) {
            assertThat(regra.verificar(item().perfil(perfil().cst(cst)).montar(), NORMAL)).as(cst).isEmpty();
        }
        for (String csosn : List.of("102", "103", "300", "400", "500")) {
            assertThat(regra.verificar(item().perfil(perfil().csosn(csosn)).montar(), SIMPLES)).as(csosn).isEmpty();
        }
        assertThat(regra.verificar(item().semPerfil().montar(), SIMPLES)).isEmpty();
    }
}
