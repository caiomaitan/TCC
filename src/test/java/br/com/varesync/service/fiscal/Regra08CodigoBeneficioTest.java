package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.RegimeTributario;
import org.junit.jupiter.api.Test;

class Regra08CodigoBeneficioTest {

    private final RegraFiscal regra = new Regra08CodigoBeneficio();

    @Test
    void disparaComCst00EComCodigo() {
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18").beneficio("SP010360")).montar(),
                NORMAL)).singleElement().satisfies(i -> {
                    assertThat(i.gravidade()).isEqualTo(Gravidade.ERRO);
                    assertThat(i.mensagem()).contains("CST 00", "recusa");
                });
    }

    @Test
    void disparaComFormatoInvalidoOuOutraUf() {
        assertThat(regra.verificar(item().perfil(perfil().cst("40").beneficio("SP01036")).montar(), NORMAL))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("fora do formato"));
        assertThat(regra.verificar(item().perfil(perfil().cst("40").beneficio("XX010360")).montar(), NORMAL))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("fora do formato"));
        assertThat(regra.verificar(item().perfil(perfil().cst("40").beneficio("PR830001")).montar(), NORMAL))
                .singleElement().satisfies(i -> assertThat(i.mensagem()).contains("UF PR", "loja é de SP"));
    }

    @Test
    void avisaCst20_40_41SemCodigo() {
        for (String cst : new String[] {"20", "40", "41"}) {
            assertThat(regra.verificar(item().perfil(perfil().cst(cst)).montar(), NORMAL)).singleElement()
                    .satisfies(i -> assertThat(i.gravidade()).isEqualTo(Gravidade.AVISO));
        }
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18")).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cst("40").beneficio("SP010360")).montar(), NORMAL))
                .isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cst("40").beneficio("SEM CBENEF")).montar(), NORMAL))
                .isEmpty();
        // Sem a UF da loja, a UF do código não é conferida.
        assertThat(regra.verificar(item().perfil(perfil().cst("40").beneficio("PR830001")).montar(),
                new ContextoFiscal(RegimeTributario.NORMAL, ""))).isEmpty();
        assertThat(regra.verificar(item().montar(), Fiscais.SIMPLES)).isEmpty();
    }
}
