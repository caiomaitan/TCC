package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Cabeçalho da entrada de mercadoria: título e aviso de divergência entre a soma dos itens e o total da nota. */
class EntradaResumoTest {

    private static EntradaResumo entrada(String numero, String valorNota, String soma) {
        return new EntradaResumo(9, 1, "Distribuidora Teste Ltda", numero, null,
                valorNota == null ? null : new BigDecimal(valorNota), StatusEntrada.ABERTA, OffsetDateTime.now(),
                "Ana Admin", null, null, 2, new BigDecimal(soma), null);
    }

    @Test
    void tituloComOuSemNota() {
        assertThat(entrada("1234", null, "0").titulo()).isEqualTo("Entrada nº 9 — Distribuidora Teste Ltda (NF 1234)");
        assertThat(entrada(null, null, "0").titulo()).isEqualTo("Entrada nº 9 — Distribuidora Teste Ltda");
    }

    @Test
    void avisoSoQuandoASomaDifereDoValorInformado() {
        assertThat(entrada("1", null, "42.00").avisoTotal()).as("sem valor da nota, sem aviso").isEmpty();
        assertThat(entrada("1", "42.00", "42.00").avisoTotal()).isEmpty();
        assertThat(entrada("1", "50.00", "42.00").avisoTotal()).hasValue("A soma dos itens (R$ 42,00) é diferente "
                + "do valor total da nota (R$ 50,00): faltam R$ 8,00 nos itens. Confira as quantidades e os custos.");
        assertThat(entrada("1", "40.00", "42.00").avisoTotal()).get().asString().contains("sobram R$ 2,00");
        assertThat(entrada("1", "40.00", "42.00").diferencaTotal()).hasValueSatisfying(
                d -> assertThat(d).isEqualByComparingTo("-2.00"));
    }
}
