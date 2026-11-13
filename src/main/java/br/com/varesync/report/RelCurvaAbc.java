package br.com.varesync.report;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R07 — Curva ABC (7.1, 7.3, A.9), de {@code vw_resultado_item} (vendas − devoluções): produtos do período ordenados
 * por receita ou lucro, do maior para o menor. Classe pelo % acumulado <b>antes</b> do produto: &lt; 80% → A;
 * &lt; 95% → B; senão C. A coluna "% acumulado" mostra o acumulado já com o produto (A até 80%, B até 95%).
 *
 * <p>Por lucro, a base do % é a soma dos lucros positivos; produto com lucro zero ou negativo fica no fim, classe C,
 * sem % acumulado (ele não contribui para o lucro).
 */
public final class RelCurvaAbc implements Relatorio<RelCurvaAbc.Linha> {

    static final BigDecimal LIMITE_A = BigDecimal.valueOf(80);
    static final BigDecimal LIMITE_B = BigDecimal.valueOf(95);

    /** @param valor receita ou lucro, conforme o critério */
    public record Linha(String classe, long produtoId, String produto, BigDecimal valor, BigDecimal percentual,
                        BigDecimal percentualAcumulado) {
    }

    /** Produto e valor antes da classificação. */
    record Valor(long produtoId, String produto, BigDecimal valor) {
    }

    @Override
    public String codigo() {
        return "R07";
    }

    @Override
    public String nome() {
        return "Curva ABC";
    }

    @Override
    public String descricao() {
        return "Produtos classificados pela receita ou pelo lucro do período: A (até 80% do total), B (até 95%) e C.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO, TipoFiltro.CRITERIO);
    }

    @Override
    public List<Criterio> criterios() {
        return List.of(Criterio.RECEITA, Criterio.LUCRO);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros)
            throws SQLException {
        String sql = """
                SELECT r.produto_id, r.produto_nome, sum(r.receita) AS receita, sum(r.lucro_bruto) AS lucro
                  FROM vw_resultado_item r
                 WHERE r.data_hora >= ? AND r.data_hora < ?
                 GROUP BY r.produto_id, r.produto_nome
                """;
        boolean porLucro = filtros.criterioOuPadrao() == Criterio.LUCRO;
        List<Valor> valores = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    valores.add(new Valor(rs.getLong("produto_id"), rs.getString("produto_nome"),
                            rs.getBigDecimal(porLucro ? "lucro" : "receita")));
                }
            }
        }
        return classificar(valores);
    }

    /** A.9: ordena do maior para o menor (empate pelo nome) e classifica pelo % acumulado antes de cada produto. */
    static List<Linha> classificar(List<Valor> valores) {
        List<Valor> ordenados = valores.stream()
                .sorted(Comparator.comparing(Valor::valor, Comparator.reverseOrder())
                        .thenComparing(Valor::produto, String.CASE_INSENSITIVE_ORDER))
                .toList();
        BigDecimal base = Coluna.soma(ordenados.stream().filter(v -> v.valor().signum() > 0).toList(), Valor::valor);
        List<Linha> linhas = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        for (Valor v : ordenados) {
            BigDecimal percentual = Dinheiro.percentualDe(v.valor(), base).orElse(null);
            if (v.valor().signum() <= 0) {
                linhas.add(new Linha("C", v.produtoId(), v.produto(), v.valor(), percentual, null));
                continue;
            }
            // Classe pelo acumulado antes do produto, sem arredondar (A.9).
            BigDecimal antes = acumulado.multiply(BigDecimal.valueOf(100));
            String classe = antes.compareTo(LIMITE_A.multiply(base)) < 0 ? "A"
                    : antes.compareTo(LIMITE_B.multiply(base)) < 0 ? "B" : "C";
            acumulado = acumulado.add(v.valor());
            linhas.add(new Linha(classe, v.produtoId(), v.produto(), v.valor(), percentual,
                    Dinheiro.percentualDe(acumulado, base).orElse(null)));
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        String valor = filtros.criterioOuPadrao() == Criterio.LUCRO ? "Lucro" : "Receita";
        return List.of(
                Coluna.<Linha>texto("Classe", Linha::classe).comLargura(0.7),
                Coluna.<Linha>codigo("Código", Linha::produtoId).comLargura(0.8),
                Coluna.texto("Produto", Linha::produto),
                Coluna.moeda(valor, Linha::valor),
                // A soma dos % arredondados daria 99,99 ou 100,01; o total é a base inteira.
                Coluna.percentual("%", Linha::percentual).comTotal(linhas ->
                        linhas.stream().anyMatch(l -> l.percentualAcumulado() != null)
                                ? Dinheiro.percentual(BigDecimal.valueOf(100)) : null),
                Coluna.percentual("% acumulado", Linha::percentualAcumulado));
    }
}
