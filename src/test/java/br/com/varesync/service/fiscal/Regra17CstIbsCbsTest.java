package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.NORMAL;
import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static br.com.varesync.service.fiscal.Fiscais.perfil;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Regra17CstIbsCbsTest {

    private final RegraFiscal regra = new Regra17CstIbsCbs();

    private static Fiscais.Perfil normal(String cstIbsCbs, String reducao) {
        return perfil().cst("00").icms("18").pis("01", "1.65").cofins("01", "7.60")
                .ibsCbs(cstIbsCbs, cstIbsCbs + "001").reducaoIbsCbs(reducao);
    }

    @Test
    void cstQueANfceNaoAceitaEErro() {
        for (String cst : List.of("550", "510", "620", "011")) {
            assertThat(regra.verificar(item().perfil(normal(cst, null)).montar(), NORMAL)).as(cst).singleElement()
                    .satisfies(i -> {
                        assertThat(i.erro()).isTrue();
                        assertThat(i.mensagem()).contains("CST " + cst, "000, 200 e 410");
                    });
        }
    }

    @Test
    void cst200SemReducaoEErroEReducaoForaDo200EAviso() {
        assertThat(regra.verificar(item().perfil(normal("200", null)).montar(), NORMAL)).singleElement()
                .satisfies(i -> {
                    assertThat(i.erro()).isTrue();
                    assertThat(i.mensagem()).contains("CST 200", "sem a redução");
                });
        assertThat(regra.verificar(item().perfil(normal("000", "60")).montar(), NORMAL)).singleElement()
                .satisfies(i -> {
                    assertThat(i.erro()).isFalse();
                    assertThat(i.mensagem()).contains("60,00%", "CST 000", "só vale no CST 200");
                });
        // CST não aceito com redução: o erro do CST e o aviso da redução.
        assertThat(regra.verificar(item().perfil(normal("550", "60")).montar(), NORMAL)).hasSize(2);
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().perfil(normal("000", null)).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().perfil(normal("200", "60")).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().perfil(normal("410", null)).montar(), NORMAL)).isEmpty();
        assertThat(regra.verificar(item().perfil(normal("550", null)).montar(), SIMPLES)).as("só no normal")
                .isEmpty();
        assertThat(regra.verificar(item().perfil(perfil().cst("00").icms("18")).montar(), NORMAL))
                .as("sem CST: fica com a regra 12").isEmpty();
        assertThat(regra.verificar(item().semPerfil().montar(), NORMAL)).isEmpty();
    }
}
