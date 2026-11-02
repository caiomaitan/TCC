package br.com.varesync.report;

import br.com.varesync.model.Permissao;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R25 — DRE gerencial (F15.5): as 14 linhas de {@link Dre} no mês escolhido e no anterior, com o percentual de cada
 * linha sobre a receita líquida do mês (na linha do lucro bruto, a margem bruta; na do resultado, a margem líquida).
 * As despesas aparecem por categoria logo abaixo da linha delas. Exige também a permissão FINANCEIRO.
 */
public final class RelDre implements Relatorio<RelDre.Linha> {

    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MM/uuuu");
    private static final String RECUO = "      ";

    /** Linha do relatório; valores nulos quando a categoria não existe no mês. */
    public record Linha(String chave, String descricao, boolean resultado, boolean detalhe, BigDecimal valor,
                        BigDecimal percentual, BigDecimal valorAnterior, BigDecimal percentualAnterior) {

        /** Descrição com recuo no detalhe, como na tela e no PDF. */
        public String descricaoComRecuo() {
            return detalhe ? RECUO + descricao : descricao;
        }
    }

    @Override
    public String codigo() {
        return "R25";
    }

    @Override
    public String nome() {
        return "DRE gerencial";
    }

    @Override
    public String descricao() {
        return "Resultado do mês e do mês anterior, linha a linha, com o percentual sobre a receita líquida. A receita "
                + "líquida e o lucro bruto batem com o R01 e o R05; as despesas entram pela competência.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.MES);
    }

    @Override
    public Set<Permissao> permissoesExtras() {
        return Set.of(Permissao.FINANCEIRO);
    }

    /** O mês do filtro; sem ele, o mês de hoje. */
    public static YearMonth mes(Filtros filtros, ParametrosRelatorio parametros) {
        YearMonth mes = filtros.financeiro().mes();
        return mes != null ? mes : YearMonth.from(parametros.hoje());
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        YearMonth mes = mes(filtros, parametros);
        List<Dre.Linha> atual = Dre.calcular(con, Dre.mes(mes.atDay(1)), parametros.zona());
        List<Dre.Linha> anterior = Dre.calcular(con, Dre.mes(mes.minusMonths(1).atDay(1)), parametros.zona());
        return juntar(atual, anterior);
    }

    /** As linhas dos dois meses, na ordem do DRE; o detalhe que só existe num dos meses fica com o outro vazio. */
    static List<Linha> juntar(List<Dre.Linha> atual, List<Dre.Linha> anterior) {
        BigDecimal receita = Dre.valorDa(atual, 4);
        BigDecimal receitaAnterior = Dre.valorDa(anterior, 4);
        Map<String, Dre.Linha> doAnterior = new LinkedHashMap<>();
        anterior.forEach(l -> doAnterior.put(l.chave(), l));
        List<Dre.Linha> ordem = new ArrayList<>(atual);
        for (Dre.Linha l : anterior) {
            if (ordem.stream().noneMatch(a -> a.chave().equals(l.chave()))) {
                int depois = 0;
                for (int i = 0; i < ordem.size(); i++) {
                    if (ordem.get(i).linhaPrincipal() == l.linhaPrincipal()) {
                        depois = i;
                    }
                }
                ordem.add(depois + 1, l);
            }
        }
        List<Linha> linhas = new ArrayList<>();
        for (Dre.Linha l : ordem) {
            Dre.Linha a = atual.stream().filter(x -> x.chave().equals(l.chave())).findFirst().orElse(null);
            Dre.Linha b = doAnterior.get(l.chave());
            linhas.add(new Linha(l.chave(), l.descricao(), l.resultado(), l.detalhe(),
                    a == null ? null : a.valor(), a == null ? null : percentual(a.valor(), receita),
                    b == null ? null : b.valor(), b == null ? null : percentual(b.valor(), receitaAnterior)));
        }
        return linhas;
    }

    private static BigDecimal percentual(BigDecimal valor, BigDecimal receita) {
        return Dinheiro.percentualDe(valor, receita).orElse(null);
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        YearMonth mes = filtros.financeiro().mes();
        String titulo = mes == null ? "Mês" : MES.format(mes);
        String anterior = mes == null ? "Mês anterior" : MES.format(mes.minusMonths(1));
        return List.of(
                Coluna.<Linha>texto("Linha", Linha::descricaoComRecuo).comLargura(3.6),
                Coluna.<Linha>de(titulo, TipoColuna.MOEDA, Linha::valor).comLargura(1.4),
                Coluna.percentual("% " + titulo, Linha::percentual).comLargura(1.1),
                Coluna.<Linha>de(anterior, TipoColuna.MOEDA, Linha::valorAnterior).comLargura(1.4),
                Coluna.percentual("% " + anterior, Linha::percentualAnterior).comLargura(1.1));
    }

    @Override
    public String descreverFiltros(Filtros filtros) {
        YearMonth mes = filtros.financeiro().mes();
        return mes == null ? "Mês atual e anterior" : "Mês: " + MES.format(mes) + " (comparado com "
                + MES.format(mes.minusMonths(1)) + ")";
    }
}
