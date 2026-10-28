package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Comprovante de devolução (F13.2): mesmo layout de 48 colunas do comprovante da venda. */
class ComprovanteDevolucaoTest {

    private static final Comprovante.Loja LOJA = new Comprovante.Loja("Mercado Bom Preço", "", "", "");
    private static final OffsetDateTime AGORA = OffsetDateTime.parse("2026-10-27T15:30:00-03:00");

    private static ComprovanteDevolucao comprovante(FormaReembolso forma, BigDecimal estornado, BigDecimal saldo) {
        return new ComprovanteDevolucao(LOJA, 7, AGORA, 12, AGORA.minusDays(2), "Ana Admin", null,
                new Comprovante.Cliente("Maria da Silva", "***.982.247-**"),
                List.of(new ComprovanteDevolucao.Item(1, "7891000315507", "Refrigerante cola 2 L", BigDecimal.ONE,
                                "UN", false, new BigDecimal("5.22"), false),
                        new ComprovanteDevolucao.Item(2, "123", "Queijo", new BigDecimal("0.250"), "KG", true,
                                new BigDecimal("9.98"), true)),
                new BigDecimal("15.20"), forma, "Cliente desistiu", null, estornado, saldo, "Volte sempre!");
    }

    @Test
    void valeTrocaComSaldoECashbackEstornado() {
        String texto = comprovante(FormaReembolso.CREDITO_CLIENTE, new BigDecimal("0.15"), new BigDecimal("15.05"))
                .texto();
        assertThat(texto).contains(Comprovante.NAO_FISCAL, "DEVOLUÇÃO DE MERCADORIA", "Cliente: Maria da Silva",
                        "CPF: ***.982.247-**", "Registrada por: Ana Admin", "Motivo: Cliente desistiu", "Volte sempre!")
                .containsPattern("Devolução nº 7 +27/10/2026 15:30")
                .containsPattern("Venda nº 12 +25/10/2026 15:30")
                .containsPattern("1 UN +R\\$ 5,22")
                .containsPattern("0,250 KG \\(avariado\\) +R\\$ 9,98")
                .containsPattern("TOTAL DEVOLVIDO +R\\$ 15,20")
                .containsPattern("Reembolso +Vale-troca")
                .containsPattern("Cashback estornado +-R\\$ 0,15")
                .containsPattern("Saldo do vale-troca +R\\$ 15,05")
                .doesNotContain("Liberada por");
        assertThat(texto.lines()).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(Comprovante.COLUNAS));
    }

    @Test
    void dinheiroSemCreditoNaoMostraSaldo() {
        ComprovanteDevolucao c = comprovante(FormaReembolso.DINHEIRO, BigDecimal.ZERO, null);
        assertThat(c.texto()).containsPattern("Reembolso +Dinheiro").doesNotContain("Saldo", "Cashback");
        assertThat(c.titulo()).isEqualTo("Comprovante da devolução nº 7");
        assertThat(c.nomeArquivo()).isEqualTo("comprovante-devolucao-7.pdf");
    }
}
