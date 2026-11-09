package br.com.varesync.service.fiscal;

import static br.com.varesync.service.fiscal.Fiscais.SIMPLES;
import static br.com.varesync.service.fiscal.Fiscais.item;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Regra02NcmTest {

    private final RegraFiscal regra = new Regra02Ncm();

    @Test
    void disparaComFormatoInvalido() {
        assertThat(regra.verificar(item().ncm("2203000").montar(), SIMPLES)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("\"2203000\"", "8 dígitos"));
        assertThat(regra.verificar(item().ncm("2203.00.00").montar(), SIMPLES)).hasSize(1);
        assertThat(regra.verificar(item().ncm("2203000A").montar(), SIMPLES)).hasSize(1);
    }

    @Test
    void disparaComNcmForaDaTabelaImportada() {
        assertThat(regra.verificar(item().tabelas(false, null, null).montar(), SIMPLES)).singleElement()
                .satisfies(i -> assertThat(i.mensagem()).contains("2203.00.00", "não existe na tabela NCM"));
    }

    @Test
    void naoDispara() {
        assertThat(regra.verificar(item().montar(), SIMPLES)).isEmpty(); // sem tabela: só o formato
        assertThat(regra.verificar(item().tabelas(true, null, null).montar(), SIMPLES)).isEmpty();
        assertThat(regra.verificar(item().ncm(null).montar(), SIMPLES)).isEmpty(); // a regra 1 acusa
    }
}
