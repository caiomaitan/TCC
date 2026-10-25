package br.com.varesync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Comprovante não fiscal (6.6): conteúdo obrigatório e texto de largura fixa. */
public class ComprovanteTest {

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    /** Venda do roteiro de aceite: 3 × 5,49 + 0,345 kg × 39,90, 5% na venda, PIX 10 + dinheiro 50. */
    public static Comprovante exemplo(StatusVenda status) {
        return exemplo(status, null);
    }

    static Comprovante exemplo(StatusVenda status, Comprovante.Cliente cliente) {
        Comprovante.Loja loja = new Comprovante.Loja("Mercado Bom Preço", "11.222.333/0001-81", "Rua A, 10",
                "(16) 3333-4444");
        List<Comprovante.Item> itens = List.of(
                new Comprovante.Item(1, "7891000315507", "Refrigerante cola 2 L", v("3"), "UN", false, v("5.49"),
                        v("16.47"), v("0"), false),
                new Comprovante.Item(2, "2", "Queijo muçarela", v("0.345"), "KG", true, v("39.90"), v("13.77"),
                        v("0"), false),
                new Comprovante.Item(3, "7891234567895", "Café 500 g", v("1"), "UN", false, v("18.90"), v("18.90"),
                        v("0"), true));
        OffsetDateTime quando = OffsetDateTime.parse("2026-10-13T14:05:00-03:00");
        return new Comprovante(loja, 42, quando, "Ana Admin", "CAIXA-01", status,
                status == StatusVenda.CANCELADA ? quando.plusMinutes(5) : null,
                status == StatusVenda.CANCELADA ? "Cliente desistiu" : null, itens, v("30.24"), v("0.00"),
                v("1.51"), v("28.73"), List.of(new Comprovante.Pagamento("PIX", v("10.00")),
                new Comprovante.Pagamento("Dinheiro", v("50.00"))), v("31.27"), "Obrigado pela preferência!",
                cliente);
    }

    @Test
    void clienteIdentificadoApareceComOCpfMascarado() {
        String texto = exemplo(StatusVenda.FINALIZADA,
                new Comprovante.Cliente("Maria da Silva", "***.982.247-**")).texto();
        assertThat(texto).contains("Cliente: Maria da Silva", "CPF: ***.982.247-**").doesNotContain("52998224725");
        assertThat(exemplo(StatusVenda.FINALIZADA, new Comprovante.Cliente("Padaria Boa", "11.222.333/0001-81"))
                .texto()).contains("Cliente: Padaria Boa", "CNPJ: 11.222.333/0001-81");
        assertThat(exemplo(StatusVenda.FINALIZADA, new Comprovante.Cliente("João", "")).texto())
                .contains("Cliente: João").doesNotContainPattern("(?m)^CPF:"); // a loja tem "CNPJ/CPF:"
        assertThat(exemplo(StatusVenda.FINALIZADA).texto()).doesNotContain("Cliente:");
    }

    @Test
    void textoTemOsDadosObrigatoriosEmLarguraFixa() {
        String texto = exemplo(StatusVenda.FINALIZADA).texto();
        assertThat(texto).contains("Mercado Bom Preço", "CNPJ/CPF: 11.222.333/0001-81", "NÃO É DOCUMENTO FISCAL",
                "Venda nº 42", "Operador: Ana Admin", "Terminal: CAIXA-01", "001 7891000315507 Refrigerante cola 2 L",
                "3 UN x R$ 5,49", "0,345 KG x R$ 39,90", "ITEM CANCELADO", "Subtotal", "R$ 30,24",
                "Desconto na venda", "-R$ 1,51", "TOTAL", "R$ 28,73", "PIX", "R$ 10,00", "Dinheiro", "R$ 50,00",
                "Troco", "R$ 31,27", "Obrigado pela preferência!");
        assertThat(texto).doesNotContain("Descontos nos itens", "CANCELADA");
        assertThat(texto.lines()).allSatisfy(linha -> assertThat(linha.length()).isLessThanOrEqualTo(Comprovante.COLUNAS));
        assertThat(texto.lines().filter(l -> l.startsWith("TOTAL")).findFirst().orElseThrow())
                .hasSize(Comprovante.COLUNAS).endsWith("R$ 28,73");
    }

    @Test
    void vendaCanceladaAvisaNoComprovante() {
        String texto = exemplo(StatusVenda.CANCELADA).texto();
        assertThat(texto).contains("VENDA CANCELADA em", "Motivo: Cliente desistiu");
    }

    @Test
    void textoLongoECortadoParaCaberNaLinha() {
        assertThat(Comprovante.formatar(LinhaComprovante.normal("x".repeat(60), "R$ 1,00"), 20))
                .isEqualTo("x".repeat(12) + " R$ 1,00");
        assertThat(Comprovante.formatar(LinhaComprovante.centro("abc"), 9)).isEqualTo("   abc");
        assertThat(Comprovante.formatar(LinhaComprovante.separador(), 5)).isEqualTo("-----");
    }
}
