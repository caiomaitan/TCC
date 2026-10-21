package br.com.varesync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FormatosTest {

    @Test
    void moeda() {
        assertThat(Formatos.moeda(new BigDecimal("1234.56"))).isEqualTo("R$ 1.234,56");
        assertThat(Formatos.moeda(new BigDecimal("5.4"))).isEqualTo("R$ 5,40");
        assertThat(Formatos.moeda(new BigDecimal("0.005"))).isEqualTo("R$ 0,01");
        assertThat(Formatos.moeda(new BigDecimal("-1"))).isEqualTo("-R$ 1,00");
        assertThat(Formatos.moeda(new BigDecimal("1234567.891"))).isEqualTo("R$ 1.234.567,89");
    }

    @Test
    void valorAusenteViraTraco() {
        assertThat(Formatos.moeda((BigDecimal) null)).isEqualTo("—");
        assertThat(Formatos.moeda(Optional.empty())).isEqualTo("—");
        assertThat(Formatos.percentual(Dinheiro.percentualDe(BigDecimal.ONE, BigDecimal.ZERO))).isEqualTo("—");
    }

    @Test
    void quantidade() {
        assertThat(Formatos.quantidade(new BigDecimal("3.000"), false)).isEqualTo("3");
        assertThat(Formatos.quantidade(new BigDecimal("1500"), false)).isEqualTo("1.500");
        assertThat(Formatos.quantidade(new BigDecimal("0.345"), true)).isEqualTo("0,345");
        assertThat(Formatos.quantidade(new BigDecimal("2"), true)).isEqualTo("2,000");
    }

    @Test
    void percentual() {
        assertThat(Formatos.percentual(new BigDecimal("41.7122"))).isEqualTo("41,71%");
        assertThat(Formatos.percentual(Optional.of(new BigDecimal("13.64")))).isEqualTo("13,64%");
    }

    @Test
    void datas() {
        assertThat(Formatos.data(LocalDate.of(2026, 3, 7))).isEqualTo("07/03/2026");
        OffsetDateTime local = OffsetDateTime.of(2026, 3, 7, 14, 5, 9, 0,
                ZoneId.systemDefault().getRules().getOffset(java.time.Instant.parse("2026-03-07T12:00:00Z")));
        assertThat(Formatos.dataHora(local)).isEqualTo("07/03/2026 14:05");
        assertThat(Formatos.dataHoraComSegundos(local)).isEqualTo("07/03/2026 14:05:09");
    }

    @Test
    void telefoneComDddFixoECelular() {
        assertThat(Formatos.telefone("1633334444")).isEqualTo("(16) 3333-4444");
        assertThat(Formatos.telefone("16999998888")).isEqualTo("(16) 99999-8888");
        assertThat(Formatos.telefone("123")).isEqualTo("123");
        assertThat(Formatos.telefone(null)).isEmpty();
    }

    @Test
    void dataHoraEmUtcEhConvertidaParaOFusoLocal() {
        OffsetDateTime utc = OffsetDateTime.of(2026, 3, 7, 12, 0, 0, 0, ZoneOffset.UTC);
        String esperado = Formatos.dataHora(utc.atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime());
        assertThat(Formatos.dataHora(utc)).isEqualTo(esperado);
    }
}
