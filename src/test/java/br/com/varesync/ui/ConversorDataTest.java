package br.com.varesync.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Datas digitadas nos filtros e formulários (dd/MM/aaaa). Sem JavaFX na tela. */
class ConversorDataTest {

    private final ConversorData conversor = new ConversorData();

    @Test
    void lerEMostrar() {
        assertThat(conversor.fromString(" 05/10/2026 ")).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(conversor.toString(LocalDate.of(2026, 2, 28))).isEqualTo("28/02/2026");
        assertThat(conversor.fromString("29/02/2028")).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void dataImpossivelOuMalDigitadaViraNula() {
        assertThat(conversor.fromString("31/02/2026")).isNull(); // antes virava 28/02 em silêncio
        assertThat(conversor.fromString("29/02/2026")).isNull();
        assertThat(conversor.fromString("2026-10-05")).isNull();
        assertThat(conversor.fromString("")).isNull();
    }
}
