package br.com.varesync.report;

import br.com.varesync.util.Formatos;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Relatório gerado: linhas, colunas e o que vai no cabeçalho e no rodapé (loja, título, filtros, quem emitiu e
 * quando). É o que a tela mostra e o que a exportação CSV e PDF recebe.
 */
public record ResultadoRelatorio<T>(Relatorio<T> relatorio, Filtros filtros, List<Coluna<T>> colunas,
                                    List<T> linhas, String loja, String emitidoPor, OffsetDateTime emitidoEm) {

    public ResultadoRelatorio {
        colunas = List.copyOf(colunas);
        linhas = List.copyOf(linhas);
    }

    public String titulo() {
        return relatorio.titulo();
    }

    public String descricaoFiltros() {
        return filtros.descrever(relatorio.filtros());
    }

    /** "Emitido por Ana Admin em 17/10/2026 14:30". */
    public String emissao() {
        return "Emitido por " + emitidoPor + " em " + Formatos.dataHora(emitidoEm);
    }

    public boolean temTotais() {
        return colunas.stream().anyMatch(Coluna::totalizavel);
    }

    /** Textos da linha de totais; a primeira coluna não totalizável recebe "Total". */
    public List<String> totais() {
        String[] textos = new String[colunas.size()];
        boolean rotulo = false;
        for (int i = 0; i < colunas.size(); i++) {
            Coluna<T> c = colunas.get(i);
            if (c.totalizavel()) {
                textos[i] = c.formatarTotal(linhas);
            } else if (!rotulo) {
                textos[i] = "Total (" + linhas.size() + (linhas.size() == 1 ? " linha)" : " linhas)");
                rotulo = true;
            } else {
                textos[i] = "";
            }
        }
        return List.of(textos);
    }
}
