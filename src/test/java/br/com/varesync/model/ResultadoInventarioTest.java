package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Resultado de inventário (R13): valor da diferença, totais de sobra e falta e itens não contados (6.7, 7.1). */
class ResultadoInventarioTest {

    private static final OffsetDateTime AGORA = OffsetDateTime.parse("2026-10-17T10:00:00-03:00");
    private static final InventarioResumo ABERTO = new InventarioResumo(4, "Mercearia", StatusInventario.ABERTO,
            AGORA, "Ana", null, null, 4, 3);

    private static ItemDivergencia item(long id, String sistema, String contado, String custo, boolean fracionada) {
        BigDecimal s = sistema == null ? null : new BigDecimal(sistema);
        BigDecimal c = contado == null ? null : new BigDecimal(contado);
        return new ItemDivergencia(id, id, "Produto " + id, fracionada ? "KG" : "UN", fracionada, s, c,
                c == null ? null : c.subtract(s), custo == null ? null : new BigDecimal(custo),
                c == null ? null : AGORA, c == null ? null : "Ana");
    }

    @Test
    void valorDaDiferencaEDiferencaVezesCustoArredondadoNoCentavo() {
        assertThat(item(1, "10", "8", "21.5000", false).valorDiferenca()).isEqualByComparingTo("-43.00");
        // 0,345 kg × 28,1234 = 9,702573 → 9,70
        assertThat(item(2, "1.000", "1.345", "28.1234", true).valorDiferenca()).isEqualByComparingTo("9.70");
        assertThat(item(3, null, null, null, false).valorDiferenca()).isNull();
        assertThat(item(4, "5", "5", "3.2000", false).temDiferenca()).isFalse();
        assertThat(item(5, null, null, null, false).temDiferenca()).isFalse();
    }

    @Test
    void totaisDeSobraEFaltaContamSoItensContados() {
        ResultadoInventario r = new ResultadoInventario(ABERTO, List.of(
                item(1, "10", "8", "21.5000", false),      // falta 2 × 21,50 = 43,00
                item(2, "1.000", "1.345", "28.1234", true), // sobra 9,70
                item(3, "4", "4", "5.0000", false),        // sem diferença
                item(4, null, null, null, false)));         // não contado

        assertThat(r.valorSobra()).isEqualByComparingTo("9.70");
        assertThat(r.valorFalta()).isEqualByComparingTo("43.00");
        assertThat(r.valorLiquido()).isEqualByComparingTo("-33.30");
        assertThat(r.itensComSobra()).isEqualTo(1);
        assertThat(r.itensComFalta()).isEqualTo(1);
        assertThat(r.itensComDiferenca()).isEqualTo(2);
        assertThat(r.itensContados()).isEqualTo(3);
        assertThat(r.itensNaoContados()).isEqualTo(1);
    }

    @Test
    void semDiferencasOsTotaisSaoZero() {
        ResultadoInventario r = new ResultadoInventario(ABERTO, List.of(item(1, "3", "3", "1.0000", false)));

        assertThat(r.valorSobra()).isEqualByComparingTo("0").hasScaleOf(2);
        assertThat(r.valorFalta()).isEqualByComparingTo("0");
        assertThat(r.itensComDiferenca()).isZero();
        assertThat(r.itensNaoContados()).isZero();
    }

    @Test
    void mensagemDaInclusaoDizOQueEntrouEOQueFicouDeFora() {
        assertThat(new InclusaoInventario(1, 0, List.of()).mensagem()).isEqualTo("1 produto incluído.");
        assertThat(new InclusaoInventario(12, 2, List.of()).mensagem())
                .isEqualTo("12 produtos incluídos. 2 já estavam neste inventário.");
        assertThat(new InclusaoInventario(0, 1, List.of("Café 500 g (inventário nº 3)")).mensagem())
                .isEqualTo("Nenhum produto incluído. 1 já estava neste inventário. 1 não foi incluído porque está "
                        + "em outro inventário aberto: Café 500 g (inventário nº 3).");
        List<String> sete = List.of("A (nº 1)", "B (nº 1)", "C (nº 1)", "D (nº 1)", "E (nº 1)", "F (nº 1)", "G (nº 1)");
        assertThat(new InclusaoInventario(3, 0, sete).mensagem())
                .isEqualTo("3 produtos incluídos. 7 não foram incluídos porque estão em outro inventário aberto: "
                        + "A (nº 1), B (nº 1), C (nº 1), D (nº 1), E (nº 1) e mais 2.");
    }

    @Test
    void cabecalhoDoInventario() {
        assertThat(ABERTO.titulo()).isEqualTo("Inventário nº 4 — Mercearia");
        assertThat(ABERTO.aberto()).isTrue();
        assertThat(ABERTO.itensNaoContados()).isEqualTo(1);
    }
}
