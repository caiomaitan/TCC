package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.ConferenciaPedido;
import br.com.varesync.model.ItemPedido;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.model.StatusPedido;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R27, PDF do pedido de compra e conferência pedido × nota (F16.3 e F16.4), sem banco. */
class PedidoCompraRelatoriosTest {

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    void r27ColunasETotais() {
        ResultadoRelatorio<RelPedidosPendentes.Linha> r27 = DadosFicticios.r27();
        assertThat(r27.colunas()).extracting(Coluna::titulo).containsExactly("Pedido", "Fornecedor", "Situação",
                "Enviado em", "Previsão", "Produto", "Un.", "Pedida", "Recebida", "Pendente",
                "Custo previsto", "Valor pendente");
        assertThat(r27.colunas().get(4).formatar(r27.linhas().get(0))).isEqualTo("31/10/2026 (Atrasado)");
        assertThat(r27.colunas().get(4).formatar(r27.linhas().get(2))).isEqualTo("09/11/2026");
        assertThat(r27.colunas().get(0).formatar(r27.linhas().get(0))).isEqualTo("12");
        assertThat(r27.totais()).contains("2 atrasado(s)", "R$ 518,00");
    }

    @Test
    void pdfDoPedidoComAUnidadeDeCompraNoCabecalhoENasLinhas() {
        PedidoCompra pedido = new PedidoCompra(12, 3, "Laticínios Vale do Pardo Ltda", true, StatusPedido.ENVIADO,
                LocalDate.of(2026, 11, 9), "Entregar pela manhã",
                OffsetDateTime.of(2026, 11, 4, 9, 0, 0, 0, ZoneOffset.ofHours(-3)), "Ana Admin",
                OffsetDateTime.of(2026, 11, 4, 9, 30, 0, 0, ZoneOffset.ofHours(-3)), "Ana Admin", null, null, 2,
                d("592.00"), d("149.000"));
        List<ItemPedido> itens = List.of(
                new ItemPedido(1, 7, "Leite integral 1 L", "UN", false, d("144.000"), d("3.3333"), d("0.000"),
                        "LT-01", "CX", d("12.000")),
                new ItemPedido(2, 9, "Queijo muçarela", "KG", true, d("5.000"), d("28.0000"), d("0.000"), null, null,
                        d("1")));
        ResultadoRelatorio<ItemPedido> r = RelPedidoCompra.exportacao(pedido, "11.222.333/0001-81", itens,
                new Emissao("Mercado Bom Preço", "Ana Admin", pedido.enviadoEm()));
        assertThat(r.titulo()).isEqualTo("Pedido de compra nº 12");
        assertThat(r.descricaoFiltros()).isEqualTo("Fornecedor: Laticínios Vale do Pardo Ltda (CNPJ/CPF "
                + "11.222.333/0001-81) · Previsão de entrega: 09/11/2026 · Situação: Enviado em 04/11/2026 09:30 · "
                + "Observação: Entregar pela manhã");
        assertThat(RelPedidoCompra.naUnidadeDeCompra(itens.get(0))).isEqualTo("12 CX");
        assertThat(RelPedidoCompra.naUnidadeDeCompra(itens.get(1))).isEqualTo("5 un. de compra");
        assertThat(r.colunas().get(1).formatar(r.linhas().get(0))).isEqualTo("LT-01");
        assertThat(r.totais()).contains("R$ 620,00"); // 480,00 + 140,00
        assertThat(new RelatorioPdf().gerar(r)).isNotEmpty();
    }

    @Test
    void conferenciaDestacaQuantidadeECustoDivergentes() {
        ConferenciaPedido.Linha confere = new ConferenciaPedido.Linha(1, "Leite", "UN", false, d("144"), d("120"),
                d("24"), d("3.50"), d("3.5000"));
        assertThat(confere.situacao()).isEqualTo(ConferenciaPedido.Linha.CONFERE);
        assertThat(confere.quantidadeDivergente()).isFalse();
        assertThat(confere.custoDivergente()).isFalse();
        assertThat(confere.pendenteDepois()).isEqualByComparingTo("0");

        ConferenciaPedido.Linha abaixo = new ConferenciaPedido.Linha(1, "Leite", "UN", false, d("144"), d("0"),
                d("120"), d("3.50"), d("3.60"));
        assertThat(abaixo.situacao()).isEqualTo(ConferenciaPedido.Linha.ABAIXO);
        assertThat(abaixo.pendenteDepois()).isEqualByComparingTo("24");
        assertThat(abaixo.custoDivergente()).isTrue();

        ConferenciaPedido.Linha acimaDoPedido = new ConferenciaPedido.Linha(1, "Leite", "UN", false, d("144"),
                d("150"), null, d("3.50"), null);
        assertThat(acimaDoPedido.pendenteAntes()).as("nunca negativo").isEqualByComparingTo("0");
        assertThat(acimaDoPedido.situacao()).isEqualTo(ConferenciaPedido.Linha.NAO_VEIO);

        ConferenciaPedido.Linha fora = new ConferenciaPedido.Linha(2, "Queijo", "UN", false, null, d("0"), d("4"),
                null, d("12.00"));
        assertThat(fora.situacao()).isEqualTo(ConferenciaPedido.Linha.FORA_DO_PEDIDO);
        assertThat(fora.quantidadeDivergente()).isTrue();
        assertThat(fora.pendenteDepois()).isNull();
        assertThat(new ConferenciaPedido(null, List.of(confere)).temDivergencia()).isFalse();
        assertThat(new ConferenciaPedido(null, List.of(confere, fora)).temDivergencia()).isTrue();
    }
}
