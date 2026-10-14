package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Origem do kardex, período com as duas datas incluídas e o tipo de cada ajuste manual (6.4). */
class EstoqueModeloTest {

    private static MovimentoKardex movimento(Long venda, Long inventario, Long entrada, String motivo) {
        return new MovimentoKardex(1, OffsetDateTime.parse("2026-10-13T10:00:00-03:00"), TipoMovimento.VENDA,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, "Ana", venda, inventario, entrada,
                motivo);
    }

    @Test
    void origemDoKardexMostraODocumentoOuOMotivo() {
        assertThat(movimento(123L, null, null, null).origem()).isEqualTo("Venda nº 123");
        assertThat(movimento(null, 4L, null, null).origem()).isEqualTo("Inventário nº 4");
        assertThat(movimento(null, null, 9L, null).origem()).isEqualTo("Entrada nº 9");
        assertThat(movimento(null, null, null, "Avaria: embalagem").origem()).isEqualTo("Avaria: embalagem");
        assertThat(movimento(null, null, null, null).origem()).isEmpty();
    }

    @Test
    void periodoIncluiAsDuasDatas() {
        ZoneId sp = ZoneId.of("America/Sao_Paulo");
        Periodo periodo = new Periodo(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13));

        assertThat(periodo.inicioEm(sp)).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00-03:00"));
        assertThat(periodo.fimExclusivoEm(sp)).isEqualTo(OffsetDateTime.parse("2026-10-14T00:00:00-03:00"));
    }

    @Test
    void cadaAjusteGeraOSeuTipoDeMovimento() {
        assertThat(TipoAjuste.ENTRADA.tipoMovimento()).isEqualTo(TipoMovimento.AJUSTE_ENTRADA);
        assertThat(TipoAjuste.SAIDA.tipoMovimento()).isEqualTo(TipoMovimento.AJUSTE_SAIDA);
        assertThat(TipoAjuste.PERDA.tipoMovimento()).isEqualTo(TipoMovimento.PERDA);
        assertThat(TipoAjuste.PERDA.motivoDaLista()).isTrue();
        assertThat(TipoAjuste.ENTRADA.motivoDaLista()).isFalse();
        assertThat(TipoAjuste.PERDA.sugestoes())
                .containsExactly("Vencimento", "Avaria", "Furto/desvio", "Consumo interno", "Outros");
        assertThat(MotivoPerda.doRotulo(" avaria ")).contains(MotivoPerda.AVARIA);
        assertThat(MotivoPerda.doRotulo("Quebra")).isEmpty();
    }
}
