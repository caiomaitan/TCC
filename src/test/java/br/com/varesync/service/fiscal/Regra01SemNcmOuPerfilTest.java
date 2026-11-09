package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra01SemNcmOuPerfilTest {

    private final RegraFiscal regra = new Regra01SemNcmOuPerfil();

    @Test
    void disparaSemNcmESemPerfil() {
        assertThat(regra.verificar(item().ncm(null).montar(), SIMPLES)).extracting(Inconsistencia::mensagem)
                .containsExactly("Sem NCM.");
        assertThat(regra.verificar(item().semPerfil().montar(), SIMPLES)).extracting(Inconsistencia::mensagem)
                .containsExactly("Sem perfil tributário.");
        assertThat(regra.verificar(item().ncm(" ").semPerfil().montar(), SIMPLES)).hasSize(2)
                .allMatch(i -> i.gravidade() == Gravidade.ERRO && i.regra() == 1);
    }

    @Test
    void naoDisparaComNcmEPerfil() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty();
    }
}
