package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaResumo;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.StatusCaixa;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.VendasPorForma;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exportação do fechamento de caixa (R11): as seções da janela no CSV (números sem "R$", UTF-8 com BOM) e no PDF
 * de layout próprio ({@code reports/fechamento-caixa.jrxml}), com os valores de A.10 (esperado 56,00).
 */
class FechamentoCaixaExportacaoTest {

    private static final OffsetDateTime FECHADO = OffsetDateTime.parse("2026-10-13T18:00:00-03:00")
            .atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
    private static final Emissao EMISSAO = new Emissao("Mercado Bom Preço", "Ana Admin", FECHADO);

    static RelatorioFechamento exemplo() {
        CaixaSessao sessao = new CaixaSessao(12, "CAIXA-01", StatusCaixa.FECHADO, 1, "Maria Oliveira",
                FECHADO.minusHours(8), new BigDecimal("100.00"), "José Santos", FECHADO, new BigDecimal("56.00"),
                new BigDecimal("55.00"), new BigDecimal("-1.00"));
        CaixaResumo resumo = new CaixaResumo(12, new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("50.00"),
                new BigDecimal("6.00"), 1, new BigDecimal("16.00"), 1, new BigDecimal("56.00"));
        return new RelatorioFechamento(sessao, resumo,
                List.of(new VendasPorForma("Dinheiro", 1, new BigDecimal("6.00")),
                        new VendasPorForma("PIX", 1, new BigDecimal("10.00"))),
                List.of(new CaixaMovimento(1, TipoCaixaMovimento.SANGRIA, new BigDecimal("50.00"),
                        "Depósito; banco", "Carlos Pereira", FECHADO.minusHours(2))),
                new BigDecimal("0.80"));
    }

    @Test
    void csvTemAsSecoesDaJanelaComValoresNumericos() {
        String csv = FechamentoCaixaExportacao.csv(exemplo(), EMISSAO);
        assertThat(csv).startsWith(ExportadorCsv.BOM + "Mercado Bom Preço\r\nR11 — Fechamento do caixa nº 12\r\n"
                + "Emitido por Ana Admin em 13/10/2026");
        assertThat(csv).contains("\r\nResumo\r\nTerminal;CAIXA-01\r\n")
                .contains("Abertura;13/10/2026 10:00 — Maria Oliveira\r\n")
                .contains("Total vendido;16,00\r\n")
                .contains("Descontos concedidos;0,80\r\n")
                .contains("(=) Dinheiro esperado;56,00\r\nDinheiro contado;55,00\r\n")
                .contains("Diferença (contado − esperado);-1,00\r\nSituação;Falta de R$ 1,00\r\n")
                .contains("Forma de pagamento;Nº de vendas;Valor\r\nDinheiro;1;6,00\r\nPIX;1;10,00\r\nTotal;;16,00\r\n")
                .contains("Data/hora;Tipo;Valor;Motivo;Usuário\r\n13/10/2026 16:00;Sangria;50,00;"
                        + "\"Depósito; banco\";Carlos Pereira\r\n");
    }

    @Test
    void semVendasNemMovimentosAvisaNasSecoes() {
        RelatorioFechamento base = exemplo();
        RelatorioFechamento vazio = new RelatorioFechamento(base.sessao(), base.resumo(), List.of(), List.of(),
                BigDecimal.ZERO);
        assertThat(FechamentoCaixaExportacao.csv(vazio, EMISSAO)).contains("Nenhuma venda nesta sessão.")
                .contains("Nenhuma sangria ou suprimento nesta sessão.");
    }

    @Test
    void pdfComLayoutProprio() throws Exception {
        FechamentoCaixaPdf pdf = new FechamentoCaixaPdf();
        byte[] bytes = pdf.gerar(exemplo(), EMISSAO);
        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(pdf.preencher(exemplo(), EMISSAO).getPages()).hasSize(1);

        Path destino = Files.createTempFile("fechamento", ".pdf");
        try {
            pdf.salvar(exemplo(), EMISSAO, destino);
            assertThat(Files.size(destino)).isGreaterThan(1000);
        } finally {
            Files.deleteIfExists(destino);
        }
    }

    @Test
    void nomeDoArquivoTemONumeroDoCaixa() {
        assertThat(FechamentoCaixaExportacao.nomeArquivo(exemplo(), "pdf")).isEqualTo("R11_fechamento_caixa_12.pdf");
    }

    @Test
    void r13NoCsvComSobraEFaltaEmColunas() {
        String[] linhas = ExportadorCsv.gerar(DadosFicticios.r13()).substring(1).split("\r\n");
        assertThat(linhas).containsExactly(
                "Código;Produto;Un.;Sistema;Contado;Diferença;Custo unit.;Sobra (R$);Falta (R$)",
                "1;Açúcar refinado 1 kg;UN;10;11;1;3,6000;3,60;",
                "2;Banana prata;KG;5,000;2,500;-2,500;3,4000;;8,50",
                "3;Café 500 g;UN;;;;;;",
                "Total;;;;;;;3,60;8,50");
    }
}
