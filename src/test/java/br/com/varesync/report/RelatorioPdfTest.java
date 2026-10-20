package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PDF genérico (7.4): o JRXML montado a partir das colunas de cada relatório compila no JasperReports 7 e gera o
 * PDF com dados fictícios (com acentos), inclusive sem nenhuma linha.
 */
class RelatorioPdfTest {

    private final RelatorioPdf pdf = new RelatorioPdf();

    @Test
    void cadaRelatorioGeraPdf() {
        for (ResultadoRelatorio<?> resultado : DadosFicticios.todos()) {
            byte[] bytes = pdf.gerar(resultado);
            assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).as(resultado.titulo())
                    .isEqualTo("%PDF-");
            assertThat(bytes.length).as(resultado.titulo()).isGreaterThan(1000);
        }
    }

    @Test
    void semLinhasTambemGeraPdf() {
        ResultadoRelatorio<RelEstoqueAtual.Linha> base = DadosFicticios.r03();
        ResultadoRelatorio<RelEstoqueAtual.Linha> vazio = new ResultadoRelatorio<>(base.relatorio(), base.filtros(),
                base.colunas(), List.of(), base.loja(), base.emitidoPor(), base.emitidoEm());
        assertThat(pdf.gerar(vazio).length).isGreaterThan(1000);
    }

    @Test
    void muitasColunasViramPaginaDeitada() {
        assertThat(RelatorioPdf.jrxml(DadosFicticios.r04().colunas())).contains("orientation=\"Landscape\"")
                .contains("pageWidth=\"842\"");
        assertThat(RelatorioPdf.jrxml(DadosFicticios.r01(false).colunas())).doesNotContain("Landscape")
                .contains("pageWidth=\"595\"");
    }

    @Test
    void textoDoUsuarioNaoEntraNoJrxml() {
        String jrxml = RelatorioPdf.jrxml(DadosFicticios.r03().colunas());
        assertThat(jrxml).doesNotContain("Produto").doesNotContain("Açúcar").contains("$P{H1}").contains("$F{c1}");
    }

    @Test
    void rotuloDoTotalOcupaAsColunasSemTotalQueVemDepois() {
        // R13: Código, Produto, Un., Sistema, Contado, Diferença, Custo sem total; o rótulo vai de Código a Custo.
        String jrxml = RelatorioPdf.jrxml(DadosFicticios.r13().colunas());
        assertThat(RelatorioPdf.rotuloDoTotal(DadosFicticios.r13().colunas())).isZero();
        assertThat(jrxml).contains("$P{T0}").doesNotContain("$P{T1}").contains("$P{T7}").contains("$P{T8}");
    }

    @Test
    void salvaOArquivo() throws Exception {
        Path destino = Files.createTempFile("relatorio", ".pdf");
        try {
            pdf.salvar(DadosFicticios.r05(), destino);
            assertThat(Files.size(destino)).isGreaterThan(1000);
        } finally {
            Files.deleteIfExists(destino);
        }
    }
}
