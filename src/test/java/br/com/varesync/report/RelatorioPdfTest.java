package br.com.varesync.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
        // R04 e, com as colunas de devolução (F13.3), o R01 saem deitados; o R06 cabe em pé.
        assertThat(RelatorioPdf.jrxml(DadosFicticios.r04().colunas())).contains("orientation=\"Landscape\"")
                .contains("pageWidth=\"842\"");
        assertThat(RelatorioPdf.jrxml(DadosFicticios.r01(false).colunas())).contains("orientation=\"Landscape\"");
        assertThat(RelatorioPdf.jrxml(DadosFicticios.r06().colunas())).doesNotContain("Landscape")
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

    /** Repete as linhas do resultado até {@code quantas}. */
    private static <T> ResultadoRelatorio<T> comLinhas(ResultadoRelatorio<T> base, int quantas) {
        List<T> linhas = new ArrayList<>();
        for (int i = 0; i < quantas; i++) {
            linhas.add(base.linhas().get(i % base.linhas().size()));
        }
        return new ResultadoRelatorio<>(base.relatorio(), base.filtros(), base.colunas(), linhas, base.loja(),
                base.emitidoPor(), base.emitidoEm());
    }

    /** F10.7: a estimativa pelo número de linhas é o número de páginas do PDF gerado (em pé e deitado). */
    @Test
    void estimativaDePaginasBateComOPdf() throws Exception {
        for (int quantas : new int[] {0, 1, 30, 70, 140}) {
            ResultadoRelatorio<RelMovimentacao.Linha> deitado = comLinhas(DadosFicticios.r04(), quantas);
            assertThat(RelatorioPdf.estimarPaginas(deitado)).as("R04 com %d linhas", quantas)
                    .isEqualTo(pdf.preencher(deitado).getPages().size());
            ResultadoRelatorio<RelRentabilidadeCategoria.Linha> emPe = comLinhas(DadosFicticios.r06(), quantas);
            assertThat(RelatorioPdf.estimarPaginas(emPe)).as("R06 com %d linhas", quantas)
                    .isEqualTo(pdf.preencher(emPe).getPages().size());
        }
        assertThat(RelatorioPdf.estimarPaginas(DadosFicticios.r04().colunas(), 33)).isEqualTo(1);
        assertThat(RelatorioPdf.estimarPaginas(DadosFicticios.r04().colunas(), 34)).isEqualTo(2);
        assertThat(RelatorioPdf.estimarPaginas(DadosFicticios.r06().colunas(), 52)).isEqualTo(1);
    }

    /** F10.7: acima de 50 páginas, aviso com o tamanho e os filtros que ainda reduzem o PDF. */
    @Test
    void pdfGrandePedeConfirmacaoESugereFiltros() {
        ResultadoRelatorio<RelMovimentacao.Linha> cinquenta = comLinhas(DadosFicticios.r04(), 50 * 33);
        assertThat(RelatorioPdf.estimarPaginas(cinquenta)).isEqualTo(50);
        assertThat(RelatorioPdf.avisoPdfGrande(cinquenta)).isEmpty();

        ResultadoRelatorio<RelMovimentacao.Linha> grande = comLinhas(DadosFicticios.r04(), 7979);
        assertThat(RelatorioPdf.avisoPdfGrande(grande)).hasValue("O PDF terá cerca de 242 páginas (7979 linhas). "
                + "Para um arquivo menor, filtre por um produto, um tipo de movimento ou um período menor. A tela "
                + "e o CSV mostram tudo sem esse tamanho.\n\nGerar o PDF mesmo assim?");

        assertThat(RelatorioPdf.sugestoesDeFiltro(new RelMovimentacao().filtros(), Filtros.vazios()
                .comProduto(new Opcao(1, "Café")))).containsExactly("um tipo de movimento", "um período menor");
        assertThat(RelatorioPdf.sugestoesDeFiltro(new RelVendasPeriodo().filtros(), Filtros.vazios()
                .comDetalhe(true))).containsExactly("um operador", "dia (sem detalhar cada venda)",
                "um período menor");
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
