package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Chave de acesso (F23.3) com os vetores do aceite da F23, parte A. */
class ChaveAcessoTest {

    private static final YearMonth NOVEMBRO_2026 = YearMonth.of(2026, 11);

    @Test
    void emissaoNormalDoAceite() {
        assertThat(ChaveAcesso.montar(35, NOVEMBRO_2026, "12345678000195", 1, 123, 1, "87654321"))
                .isEqualTo("35261112345678000195650010000001231876543214");
    }

    @Test
    void contingenciaDoAceite() {
        assertThat(ChaveAcesso.montar(35, NOVEMBRO_2026, "12345678000195", 1, 124, 9, "87654321"))
                .isEqualTo("35261112345678000195650010000001249876543217");
    }

    @Test
    void restoZeroOuUmDaDigitoZero() {
        // procura chaves cujo resto do módulo 11 é 0 e 1: as duas dão DV 0
        int achados = 0;
        for (int numero = 1; numero < 300 && achados < 2; numero++) {
            String chave = ChaveAcesso.montar(35, NOVEMBRO_2026, "12345678000195", 1, numero, 1, "87654321");
            String sem = chave.substring(0, 43);
            int soma = 0;
            int peso = 2;
            for (int i = sem.length() - 1; i >= 0; i--) {
                soma += (sem.charAt(i) - '0') * peso;
                peso = peso == 9 ? 2 : peso + 1;
            }
            if (soma % 11 < 2) {
                assertThat(chave).endsWith("0");
                achados++;
            }
        }
        assertThat(achados).isPositive();
    }

    @Test
    void validaEmGrupos() {
        String chave = "35261112345678000195650010000001231876543214";
        assertThat(ChaveAcesso.valida(chave)).isTrue();
        assertThat(ChaveAcesso.valida("35261112345678000195650010000001231876543215")).isFalse();
        assertThat(ChaveAcesso.emGrupos(chave)).isEqualTo(
                "3526 1112 3456 7800 0195 6500 1000 0001 2318 7654 3214");
    }
}
