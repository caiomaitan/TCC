package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra13NcmEmPerfisTest {

    private final RegraFiscal regra = new Regra13NcmEmPerfis();

    @Test
    void avisaComOMesmoNcmEmOutrosPerfis() {
        assertThat(regra.verificar(item().outrosPerfis("Simples ST").montar(), SIMPLES)).singleElement()
                .satisfies(i -> {
                    assertThat(i.gravidade()).isEqualTo(Gravidade.AVISO);
                    assertThat(i.mensagem()).contains("2203.00.00", "no perfil \"Simples ST\"");
                });
        assertThat(regra.verificar(item().outrosPerfis("A", "B").montar(), SIMPLES)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("nos perfis \"A\", \"B\""));
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().semPerfil().outrosPerfis("A").montar(), SIMPLES)).isEmpty();
    }
}
