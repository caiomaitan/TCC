package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelatorioFechamentoTest {

    private static RelatorioFechamento fechado(String esperado, String contado) {
        OffsetDateTime agora = OffsetDateTime.parse("2026-10-13T18:00:00-03:00");
        CaixaSessao sessao = new CaixaSessao(1, "CAIXA-01", StatusCaixa.FECHADO, 1, "Ana", agora.minusHours(8),
                new BigDecimal("100.00"), "Ana", agora, new BigDecimal(esperado), new BigDecimal(contado), null);
        CaixaResumo resumo = new CaixaResumo(1, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, new BigDecimal(esperado));
        return new RelatorioFechamento(sessao, resumo, List.of(), List.of(), BigDecimal.ZERO);
    }

    @Test
    void diferencaEContadoMenosEsperado() {
        // A.10: esperado 56,00
        assertThat(fechado("56.00", "55.00").diferenca()).isEqualByComparingTo("-1.00");
        assertThat(fechado("56.00", "58.50").diferenca()).isEqualByComparingTo("2.50");
        assertThat(fechado("56.00", "56").diferenca()).isEqualByComparingTo("0");
    }

    @Test
    void situacaoDizSobraFaltaOuSemDiferenca() {
        assertThat(fechado("56.00", "55.00").situacao()).isEqualTo("Falta de R$ 1,00");
        assertThat(fechado("56.00", "58.50").situacao()).isEqualTo("Sobra de R$ 2,50");
        assertThat(fechado("50.00", "50.00").situacao()).isEqualTo("Sem diferença");
        assertThat(fechado("1234.56", "0").situacao()).isEqualTo("Falta de R$ 1.234,56");
    }

    @Test
    void situacaoNaFraseSoTrocaAPrimeiraLetra() {
        assertThat(fechado("56.00", "55.00").situacaoNaFrase()).isEqualTo("falta de R$ 1,00");
        assertThat(fechado("50.00", "50.00").situacaoNaFrase()).isEqualTo("sem diferença");
    }

    @Test
    void tipoDeMovimentoDoCaixaTemSinalEAcaoDeAuditoria() {
        assertThat(TipoCaixaMovimento.SANGRIA.sinal()).isEqualTo(-1);
        assertThat(TipoCaixaMovimento.SUPRIMENTO.sinal()).isEqualTo(1);
        assertThat(TipoCaixaMovimento.SANGRIA.acaoAuditoria()).isEqualTo(AcaoAuditoria.SANGRIA);
        assertThat(TipoCaixaMovimento.SUPRIMENTO.acaoAuditoria()).isEqualTo(AcaoAuditoria.SUPRIMENTO);
        assertThat(TipoCaixaMovimento.SUPRIMENTO).hasToString("Suprimento");
    }
}
