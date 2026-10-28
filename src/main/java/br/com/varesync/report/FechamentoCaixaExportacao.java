package br.com.varesync.report;

import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaResumo;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.VendasPorForma;
import br.com.varesync.service.ErroInesperadoException;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Conteúdo do relatório de fechamento de caixa (R11, 6.5) para exportar: as mesmas seções da janela (resumo,
 * dinheiro na gaveta, vendas por forma de pagamento, sangrias e suprimentos) como uma lista de linhas com estilo,
 * usada pelo CSV e pelo PDF ({@link FechamentoCaixaPdf}). No CSV os valores vão como número (sem "R$", vírgula
 * decimal), como na exportação genérica.
 */
public final class FechamentoCaixaExportacao {

    /** Como a linha aparece no PDF; no CSV todas viram campos separados por ";". */
    public enum Estilo { SECAO, PAR, CABECALHO, LINHA, TOTAL, TEXTO }

    /** Uma linha: até 5 colunas (PAR usa 2: rótulo e valor). */
    public record Linha(Estilo estilo, List<String> colunas) {

        public Linha {
            colunas = List.copyOf(colunas);
        }

        public String coluna(int i) {
            return i < colunas.size() ? colunas.get(i) : "";
        }
    }

    private static final DateTimeFormatter DATA_HORA_CSV = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private FechamentoCaixaExportacao() {
    }

    /** "Fechamento do caixa nº 12". */
    public static String titulo(RelatorioFechamento r) {
        return "R11 — Fechamento do caixa nº " + r.sessao().id();
    }

    /** "R11_fechamento_caixa_12.csv". */
    public static String nomeArquivo(RelatorioFechamento r, String extensao) {
        return "R11_fechamento_caixa_" + r.sessao().id() + "." + extensao;
    }

    /**
     * As linhas do relatório.
     *
     * @param csv valores como número (CSV) ou formatados como na tela (PDF)
     */
    public static List<Linha> linhas(RelatorioFechamento r, boolean csv) {
        CaixaSessao s = r.sessao();
        CaixaResumo resumo = r.resumo();
        List<Linha> linhas = new ArrayList<>();
        secao(linhas, "Resumo");
        par(linhas, "Terminal", s.terminal());
        par(linhas, "Abertura", dataHora(s.abertoEm(), csv) + " — " + s.abertoPorNome());
        par(linhas, "Fechamento", s.fechadoEm() == null ? "(caixa aberto)"
                : dataHora(s.fechadoEm(), csv) + " — " + s.fechadoPorNome());
        par(linhas, "Nº de vendas", String.valueOf(resumo.quantidadeVendas()));
        par(linhas, "Total vendido", moeda(resumo.totalVendido(), csv));
        par(linhas, "Vendas canceladas", String.valueOf(resumo.quantidadeCanceladas()));
        par(linhas, "Descontos concedidos", moeda(r.descontos(), csv));

        secao(linhas, "Dinheiro na gaveta");
        par(linhas, "Fundo de troco", moeda(resumo.valorAbertura(), csv));
        par(linhas, "(+) Vendas em dinheiro", moeda(resumo.vendasDinheiro(), csv));
        par(linhas, "(+) Suprimentos", moeda(resumo.suprimentos(), csv));
        par(linhas, "(−) Sangrias", moeda(resumo.sangrias(), csv));
        par(linhas, "(−) Devoluções em dinheiro", moeda(resumo.devolucoesDinheiro(), csv));
        par(linhas, "(=) Dinheiro esperado", moeda(s.valorEsperado(), csv));
        par(linhas, "Dinheiro contado", moeda(s.valorContado(), csv));
        if (s.valorContado() != null && s.valorEsperado() != null) {
            par(linhas, "Diferença (contado − esperado)", moeda(r.diferenca(), csv));
            par(linhas, "Situação", r.situacao());
        }

        secao(linhas, "Vendas por forma de pagamento");
        if (r.vendasPorForma().isEmpty()) {
            linhas.add(new Linha(Estilo.TEXTO, List.of("Nenhuma venda nesta sessão.")));
        } else {
            linhas.add(new Linha(Estilo.CABECALHO, List.of("Forma de pagamento", "Nº de vendas", "Valor")));
            BigDecimal total = Dinheiro.ZERO;
            for (VendasPorForma f : r.vendasPorForma()) {
                linhas.add(new Linha(Estilo.LINHA, List.of(f.forma(), String.valueOf(f.quantidadeVendas()),
                        moeda(f.valor(), csv))));
                total = total.add(f.valor());
            }
            linhas.add(new Linha(Estilo.TOTAL, List.of("Total", "", moeda(total, csv))));
        }

        secao(linhas, "Sangrias e suprimentos");
        if (r.movimentos().isEmpty()) {
            linhas.add(new Linha(Estilo.TEXTO, List.of("Nenhuma sangria ou suprimento nesta sessão.")));
        } else {
            linhas.add(new Linha(Estilo.CABECALHO, List.of("Data/hora", "Tipo", "Valor", "Motivo", "Usuário")));
            for (CaixaMovimento m : r.movimentos()) {
                linhas.add(new Linha(Estilo.LINHA, List.of(dataHora(m.criadoEm(), csv), m.tipo().descricao(),
                        moeda(m.valor(), csv), m.motivo() == null ? "" : m.motivo(), m.usuarioNome())));
            }
        }
        return linhas;
    }

    /** CSV: título, filtros (emissão) e as linhas; separador ";", UTF-8 com BOM (7.4). */
    public static String csv(RelatorioFechamento r, Emissao emissao) {
        StringBuilder csv = new StringBuilder(ExportadorCsv.BOM);
        linhaCsv(csv, List.of(emissao.loja()));
        linhaCsv(csv, List.of(titulo(r)));
        linhaCsv(csv, List.of(emissao.texto()));
        for (Linha linha : linhas(r, true)) {
            if (linha.estilo() == Estilo.SECAO) {
                csv.append("\r\n");
            }
            linhaCsv(csv, linha.colunas());
        }
        return csv.toString();
    }

    public static void salvarCsv(RelatorioFechamento r, Emissao emissao, Path destino) {
        try {
            Files.writeString(destino, csv(r, emissao), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ErroInesperadoException(e);
        }
    }

    private static void linhaCsv(StringBuilder csv, List<String> campos) {
        for (int i = 0; i < campos.size(); i++) {
            if (i > 0) {
                csv.append(';');
            }
            csv.append(ExportadorCsv.campo(campos.get(i)));
        }
        csv.append("\r\n");
    }

    private static void secao(List<Linha> linhas, String titulo) {
        linhas.add(new Linha(Estilo.SECAO, List.of(titulo)));
    }

    private static void par(List<Linha> linhas, String rotulo, String valor) {
        linhas.add(new Linha(Estilo.PAR, List.of(rotulo, valor)));
    }

    private static String moeda(BigDecimal valor, boolean csv) {
        if (valor == null) {
            return csv ? "" : Formatos.SEM_VALOR;
        }
        return csv ? Dinheiro.dinheiro(valor).toPlainString().replace('.', ',') : Formatos.moeda(valor);
    }

    private static String dataHora(OffsetDateTime valor, boolean csv) {
        return csv ? DATA_HORA_CSV.format(valor.atZoneSameInstant(ZoneId.systemDefault()))
                : Formatos.dataHora(valor);
    }
}
