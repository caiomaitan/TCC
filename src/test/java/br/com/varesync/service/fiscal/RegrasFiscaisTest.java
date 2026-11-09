package br.com.varesync.service.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RegrasFiscaisTest {

    @Test
    void trezeRegrasNaOrdemDosNumeros() {
        assertThat(RegrasFiscais.todas()).extracting(RegraFiscal::numero)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 13).boxed().toList());
        assertThat(RegrasFiscais.todas()).allSatisfy(r -> assertThat(r.titulo()).isNotBlank());
        assertThat(RegrasFiscais.regra(5)).get().extracting(RegraFiscal::rotulo).isEqualTo("5 — CFOP");
        assertThat(RegrasFiscais.regra(14)).isEmpty();
    }

    @Test
    void produtoCertoNaoTemNada() {
        assertThat(RegrasFiscais.verificar(Fiscais.item().montar(), Fiscais.SIMPLES)).isEmpty();
        // Regime normal com perfil completo.
        assertThat(RegrasFiscais.verificar(Fiscais.item().perfil(Fiscais.perfil().cst("00").icms("18")
                .pis("01", "1.65").cofins("01", "7.60").ibsCbs("000", "000001")).montar(), Fiscais.NORMAL)).isEmpty();
    }

    @Test
    void cadaAchadoTemONumeroDaRegra() {
        var achados = RegrasFiscais.verificar(Fiscais.item().ncm(null).origem(null).semPerfil().montar(),
                Fiscais.SIMPLES);
        assertThat(achados).extracting(Inconsistencia::regra).containsExactly(1, 1, 10);
        assertThat(achados).allMatch(Inconsistencia::erro);
    }
}
