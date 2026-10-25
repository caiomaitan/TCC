package br.com.varesync.report;

import br.com.varesync.service.ErroInesperadoException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * PDF genérico dos relatórios tabulares (7.4), montado a partir da definição de colunas: o layout é um JRXML gerado
 * aqui (formato do JasperReports 7, o mesmo do comprovante) e compilado uma vez por formato de tabela. Cabeçalho em
 * todas as páginas com loja, título e filtros; títulos das colunas; linhas; totais no fim; rodapé com quem emitiu,
 * quando e "Página x de y". A4 em pé quando as colunas cabem, deitado quando não.
 *
 * <p>Nenhum texto do usuário entra no JRXML: títulos, filtros, valores e totais vão como parâmetros e campos
 * (já formatados como na tela).
 */
public class RelatorioPdf {

    /** Soma das larguras relativas ({@link TipoColuna#largura()}) que ainda cabe em A4 em pé. */
    static final double LARGURA_MAXIMA_EM_PE = 12;
    /** Acima disto, a tela pede confirmação antes de gerar o PDF e sugere filtros (F10.7). */
    public static final int PAGINAS_PARA_CONFIRMAR = 50;
    private static final int MARGEM = 28;
    private static final int RESPIRO = 2;
    // Alturas das faixas do JRXML gerado (cabeçalho da página, títulos das colunas, linha, rodapé).
    private static final int ALTURA_CABECALHO = 62;
    private static final int ALTURA_TITULOS = 18;
    private static final int ALTURA_LINHA = 13;
    private static final int ALTURA_RODAPE = 18;

    private final Map<String, JasperReport> compilados = new ConcurrentHashMap<>();

    /** @return o PDF em bytes */
    public <T> byte[] gerar(ResultadoRelatorio<T> resultado) {
        try {
            return JasperExportManager.exportReportToPdf(preencher(resultado));
        } catch (JRException e) {
            throw new ErroInesperadoException(e);
        }
    }

    /** O relatório preenchido, antes de virar PDF (os testes o desenham como imagem para conferir o layout). */
    <T> JasperPrint preencher(ResultadoRelatorio<T> resultado) throws JRException {
        List<Coluna<T>> colunas = resultado.colunas();
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("LOJA", resultado.loja());
        parametros.put("TITULO", resultado.titulo());
        parametros.put("FILTROS", resultado.descricaoFiltros());
        parametros.put("EMISSAO", resultado.emissao());
        List<String> totais = resultado.totais();
        for (int i = 0; i < colunas.size(); i++) {
            parametros.put("H" + i, colunas.get(i).titulo());
            parametros.put("T" + i, resultado.temTotais() ? totais.get(i) : "");
        }
        List<Map<String, ?>> linhas = new ArrayList<>();
        for (T linha : resultado.linhas()) {
            Map<String, String> campos = new HashMap<>();
            for (int i = 0; i < colunas.size(); i++) {
                campos.put("c" + i, colunas.get(i).formatar(linha));
            }
            linhas.add(campos);
        }
        return JasperFillManager.fillReport(compilar(jrxml(colunas)), parametros,
                new JRMapCollectionDataSource(linhas));
    }

    /**
     * Páginas do PDF estimadas pelo número de linhas (F10.7), com a mesma orientação e as mesmas alturas do JRXML.
     * Texto longo que quebra a linha aumenta o PDF: a estimativa é o mínimo.
     */
    public static <T> int estimarPaginas(List<Coluna<T>> colunas, int linhas) {
        int alturaPagina = deitado(colunas) ? 595 : 842;
        int util = alturaPagina - 2 * MARGEM - ALTURA_CABECALHO - ALTURA_TITULOS - ALTURA_RODAPE;
        int porPagina = util / ALTURA_LINHA;
        return Math.max(1, (linhas + porPagina - 1) / porPagina);
    }

    public static int estimarPaginas(ResultadoRelatorio<?> resultado) {
        return estimarPaginas(resultado.colunas(), resultado.linhas().size());
    }

    /**
     * Aviso para confirmar antes do PDF grande (F10.7): vazio até {@value #PAGINAS_PARA_CONFIRMAR} páginas; acima,
     * o tamanho e os filtros do relatório que ainda podem reduzi-lo.
     */
    public static Optional<String> avisoPdfGrande(ResultadoRelatorio<?> resultado) {
        int paginas = estimarPaginas(resultado);
        if (paginas <= PAGINAS_PARA_CONFIRMAR) {
            return Optional.empty();
        }
        List<String> sugestoes = sugestoesDeFiltro(resultado.relatorio().filtros(), resultado.filtros());
        return Optional.of("O PDF terá cerca de " + paginas + " páginas (" + resultado.linhas().size()
                + " linhas)." + (sugestoes.isEmpty() ? "" : " Para um arquivo menor, filtre por "
                + juntar(sugestoes) + ".") + " A tela e o CSV mostram tudo sem esse tamanho.\n\n"
                + "Gerar o PDF mesmo assim?");
    }

    /** Os filtros do relatório que ainda estão abertos, na ordem da tela. */
    static List<String> sugestoesDeFiltro(Set<TipoFiltro> usados, Filtros filtros) {
        List<String> sugestoes = new ArrayList<>();
        if (usados.contains(TipoFiltro.PRODUTO) && filtros.produto() == null) {
            sugestoes.add("um produto");
        }
        if (usados.contains(TipoFiltro.TIPO_MOVIMENTO) && filtros.tipo() == null) {
            sugestoes.add("um tipo de movimento");
        }
        if (usados.contains(TipoFiltro.CATEGORIA) && filtros.categoria() == null) {
            sugestoes.add("uma categoria");
        }
        if (usados.contains(TipoFiltro.OPERADOR) && filtros.operador() == null) {
            sugestoes.add("um operador");
        }
        if (usados.contains(TipoFiltro.FORNECEDOR) && filtros.fornecedor() == null) {
            sugestoes.add("um fornecedor");
        }
        if (usados.contains(TipoFiltro.DETALHAR_VENDAS) && filtros.detalharVendas()) {
            sugestoes.add("dia (sem detalhar cada venda)");
        }
        if (usados.contains(TipoFiltro.PERIODO)) {
            sugestoes.add("um período menor");
        }
        return sugestoes;
    }

    /** "a", "a ou b", "a, b ou c". */
    private static String juntar(List<String> partes) {
        if (partes.size() == 1) {
            return partes.get(0);
        }
        return String.join(", ", partes.subList(0, partes.size() - 1)) + " ou " + partes.get(partes.size() - 1);
    }

    private static <T> boolean deitado(List<Coluna<T>> colunas) {
        return colunas.stream().mapToDouble(Coluna::largura).sum() > LARGURA_MAXIMA_EM_PE;
    }

    public <T> void salvar(ResultadoRelatorio<T> resultado, Path destino) {
        try {
            Files.write(destino, gerar(resultado));
        } catch (IOException e) {
            throw new ErroInesperadoException(e);
        }
    }

    private JasperReport compilar(String jrxml) throws JRException {
        JasperReport pronto = compilados.get(jrxml);
        if (pronto == null) {
            pronto = JasperCompileManager.compileReport(
                    new ByteArrayInputStream(jrxml.getBytes(StandardCharsets.UTF_8)));
            compilados.put(jrxml, pronto);
        }
        return pronto;
    }

    /** O JRXML da tabela; depende só do número, da largura e do alinhamento das colunas. */
    static <T> String jrxml(List<Coluna<T>> colunas) {
        boolean deitado = deitado(colunas);
        int larguraPagina = deitado ? 842 : 595;
        int alturaPagina = deitado ? 595 : 842;
        int util = larguraPagina - 2 * MARGEM;
        int[] x = new int[colunas.size()];
        int[] w = new int[colunas.size()];
        double soma = colunas.stream().mapToDouble(Coluna::largura).sum();
        int posicao = 0;
        for (int i = 0; i < colunas.size(); i++) {
            x[i] = posicao;
            w[i] = i == colunas.size() - 1 ? util - posicao
                    : (int) Math.floor(util * colunas.get(i).largura() / soma);
            posicao += w[i];
        }

        StringBuilder s = new StringBuilder();
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<jasperReport name=\"relatorio\" language=\"java\" pageWidth=\"").append(larguraPagina)
                .append("\" pageHeight=\"").append(alturaPagina).append("\"")
                .append(deitado ? " orientation=\"Landscape\"" : "")
                .append(" columnWidth=\"").append(util).append("\" leftMargin=\"").append(MARGEM)
                .append("\" rightMargin=\"").append(MARGEM).append("\" topMargin=\"").append(MARGEM)
                .append("\" bottomMargin=\"").append(MARGEM)
                .append("\" whenNoDataType=\"AllSectionsNoDetail\" uuid=\"0b7f6a52-3c1e-4d6b-9a0e-6f1c2d3e4a51\">\n");
        s.append("    <style name=\"Base\" default=\"true\" fontName=\"DejaVu Sans\" fontSize=\"8\"/>\n");
        for (String p : List.of("LOJA", "TITULO", "FILTROS", "EMISSAO")) {
            parametro(s, p);
        }
        for (int i = 0; i < colunas.size(); i++) {
            parametro(s, "H" + i);
            parametro(s, "T" + i);
        }
        for (int i = 0; i < colunas.size(); i++) {
            s.append("    <field name=\"c").append(i).append("\" class=\"java.lang.String\"/>\n");
        }

        s.append("    <pageHeader height=\"").append(ALTURA_CABECALHO).append("\">\n");
        texto(s, 0, 0, util, 14, "Left", "fontSize=\"10\" bold=\"true\"", "$P{LOJA}", false);
        texto(s, 0, 14, util, 20, "Left", "fontSize=\"13\" bold=\"true\"", "$P{TITULO}", false);
        // Até duas linhas de filtros (o R13 descreve o inventário, a situação e os não contados).
        texto(s, 0, 35, util, 23, "Left", "", "$P{FILTROS}", false);
        linha(s, 0, 60, util);
        s.append("    </pageHeader>\n");

        s.append("    <columnHeader height=\"").append(ALTURA_TITULOS).append("\">\n");
        s.append("            <element kind=\"rectangle\" x=\"0\" y=\"0\" width=\"").append(util)
                .append("\" height=\"18\" mode=\"Opaque\" backcolor=\"#E8ECF2\"><pen lineWidth=\"0\"/></element>\n");
        for (int i = 0; i < colunas.size(); i++) {
            texto(s, x[i] + RESPIRO, 2, w[i] - 2 * RESPIRO, 14, alinhamento(colunas.get(i)), "bold=\"true\"",
                    "$P{H" + i + "}", true);
        }
        s.append("    </columnHeader>\n");

        s.append("    <detail>\n        <band height=\"").append(ALTURA_LINHA).append("\">\n");
        for (int i = 0; i < colunas.size(); i++) {
            texto(s, x[i] + RESPIRO, 1, w[i] - 2 * RESPIRO, 12, alinhamento(colunas.get(i)), "",
                    "$F{c" + i + "}", true);
        }
        s.append("        </band>\n    </detail>\n");

        s.append("    <pageFooter height=\"").append(ALTURA_RODAPE).append("\">\n");
        linha(s, 0, 2, util);
        texto(s, 0, 5, util - 120, 12, "Left", "fontSize=\"7\"", "$P{EMISSAO}", false);
        texto(s, util - 120, 5, 90, 12, "Right", "fontSize=\"7\"",
                "\"Página \" + $V{PAGE_NUMBER} + \" de\"", false);
        s.append("            <element kind=\"textField\" x=\"").append(util - 30)
                .append("\" y=\"5\" width=\"30\" height=\"12\" fontSize=\"7\" evaluationTime=\"Report\">\n")
                .append("                <expression><![CDATA[\" \" + $V{PAGE_NUMBER}]]></expression>\n")
                .append("            </element>\n");
        s.append("    </pageFooter>\n");

        s.append("    <summary height=\"18\">\n");
        linha(s, 0, 1, util);
        // "Total (n linhas)" ocupa as colunas sem total que vêm em seguida, para não quebrar numa coluna estreita.
        int rotulo = rotuloDoTotal(colunas);
        for (int i = 0; i < colunas.size(); i++) {
            if (i == rotulo) {
                int fim = i + 1;
                while (fim < colunas.size() && !colunas.get(fim).totalizavel()) {
                    fim++;
                }
                texto(s, x[i] + RESPIRO, 4, x[fim - 1] + w[fim - 1] - x[i] - 2 * RESPIRO, 12, "Left",
                        "bold=\"true\"", "$P{T" + i + "}", true);
                i = fim - 1;
            } else {
                texto(s, x[i] + RESPIRO, 4, w[i] - 2 * RESPIRO, 12, alinhamento(colunas.get(i)), "bold=\"true\"",
                        "$P{T" + i + "}", true);
            }
        }
        s.append("    </summary>\n");
        s.append("</jasperReport>\n");
        return s.toString();
    }

    /** Coluna que recebe "Total (n linhas)": a primeira sem total, como em {@link ResultadoRelatorio#totais()}. */
    static <T> int rotuloDoTotal(List<Coluna<T>> colunas) {
        for (int i = 0; i < colunas.size(); i++) {
            if (!colunas.get(i).totalizavel()) {
                return i;
            }
        }
        return -1;
    }

    private static void parametro(StringBuilder s, String nome) {
        s.append("    <parameter name=\"").append(nome).append("\" class=\"java.lang.String\"/>\n");
    }

    private static void texto(StringBuilder s, int x, int y, int w, int h, String alinhamento, String extras,
                              String expressao, boolean estica) {
        s.append("            <element kind=\"textField\" x=\"").append(x).append("\" y=\"").append(y)
                .append("\" width=\"").append(w).append("\" height=\"").append(h).append("\" hTextAlign=\"")
                .append(alinhamento).append("\"");
        if (!extras.isEmpty()) {
            s.append(' ').append(extras);
        }
        if (estica) {
            s.append(" textAdjust=\"StretchHeight\"");
        }
        s.append(">\n                <expression><![CDATA[").append(expressao).append("]]></expression>\n")
                .append("            </element>\n");
    }

    private static void linha(StringBuilder s, int x, int y, int w) {
        s.append("            <element kind=\"line\" x=\"").append(x).append("\" y=\"").append(y)
                .append("\" width=\"").append(w).append("\" height=\"1\"><pen lineWidth=\"0.5\"/></element>\n");
    }

    private static String alinhamento(Coluna<?> coluna) {
        return switch (coluna.alinhamento()) {
            case ESQUERDA -> "Left";
            case CENTRO -> "Center";
            case DIREITA -> "Right";
        };
    }
}
