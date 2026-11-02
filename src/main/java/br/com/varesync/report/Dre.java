package br.com.varesync.report;

import br.com.varesync.model.Periodo;
import br.com.varesync.service.ImportacaoProdutosService;
import br.com.varesync.service.ProdutoService;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DRE gerencial (F15.5), base do R25 e do resultado do mês no painel. Valores com sinal: o que reduz o resultado vem
 * negativo, e o resultado líquido é a soma das linhas 6 a 13.
 *
 * <ul>
 * <li>1 a 6 saem de {@code vw_resultado_item} (receita bruta e descontos das vendas, devoluções na data delas, CMV
 * das vendas menos o devolvido): a receita líquida e o lucro bruto são os do R01 e do R05 no mesmo período.</li>
 * <li>7: o valor do R14 no período, sem "Estoque inicial" e "Importação" (estoque que entrou, não perda).</li>
 * <li>8: CASHBACK − ESTORNO_CASHBACK.</li>
 * <li>9 e 10: contas a pagar não canceladas por competência (grupos OPERACIONAL e OUTRA; IMPOSTO). MERCADORIA fica de
 * fora: o custo da mercadoria aparece pelo CMV.</li>
 * <li>11: taxas dos recebíveis não cancelados na data da venda (ou do recebimento do crediário), juros pagos e o
 * grupo FINANCEIRA por competência.</li>
 * <li>12: multas e juros recebidos menos o desconto dado nos encargos (recebimentos não estornados) e descontos
 * obtidos nos pagamentos não estornados.</li>
 * <li>13: sobras − faltas dos caixas fechados no período.</li>
 * </ul>
 */
public final class Dre {

    static final Set<String> MOTIVOS_FORA_DA_LINHA_7 = Set.of(ProdutoService.MOTIVO_ESTOQUE_INICIAL.toLowerCase(),
            ImportacaoProdutosService.MOTIVO_ESTOQUE.toLowerCase());

    /**
     * Uma linha do DRE.
     *
     * @param chave     "1" a "14"; o detalhe é "9:Aluguel"
     * @param numero    o número da linha; nulo no detalhe
     * @param resultado linha de resultado (= receita líquida, = lucro bruto, = resultado líquido)
     */
    public record Linha(String chave, Integer numero, String descricao, BigDecimal valor, boolean resultado) {

        public boolean detalhe() {
            return numero == null;
        }

        /** Linha a que o detalhe pertence ("9:Aluguel" → 9). */
        public int linhaPrincipal() {
            return Integer.parseInt(chave.contains(":") ? chave.substring(0, chave.indexOf(':')) : chave);
        }
    }

    private Dre() {
    }

    /** Linhas do período (mês fechado ou do dia 1º até hoje); competência entre o início e o fim. */
    public static List<Linha> calcular(Connection con, Periodo periodo, ZoneId zona) throws SQLException {
        OffsetDateTime de = periodo.inicioEm(zona);
        OffsetDateTime ate = periodo.fimExclusivoEm(zona);
        List<Linha> linhas = new ArrayList<>();

        BigDecimal bruta;
        BigDecimal descontos;
        BigDecimal devolucoes;
        BigDecimal cmv;
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT coalesce(sum(valor_bruto) FILTER (WHERE tipo = 'VENDA'), 0),
                       coalesce(sum(desconto_total) FILTER (WHERE tipo = 'VENDA'), 0),
                       coalesce(-sum(receita) FILTER (WHERE tipo = 'DEVOLUCAO'), 0),
                       coalesce(sum(cmv), 0)
                  FROM vw_resultado_item
                 WHERE data_hora >= ? AND data_hora < ?
                """)) {
            ps.setObject(1, de);
            ps.setObject(2, ate);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                bruta = rs.getBigDecimal(1);
                descontos = rs.getBigDecimal(2);
                devolucoes = rs.getBigDecimal(3);
                cmv = rs.getBigDecimal(4);
            }
        }
        BigDecimal receitaLiquida = bruta.subtract(descontos).subtract(devolucoes);
        BigDecimal lucroBruto = receitaLiquida.subtract(cmv);
        linhas.add(new Linha("1", 1, "Receita bruta", bruta, false));
        linhas.add(new Linha("2", 2, "(−) Descontos concedidos", descontos.negate(), false));
        linhas.add(new Linha("3", 3, "(−) Devoluções", devolucoes.negate(), false));
        linhas.add(new Linha("4", 4, "= Receita líquida", receitaLiquida, true));
        linhas.add(new Linha("5", 5, "(−) CMV", cmv.negate(), false));
        linhas.add(new Linha("6", 6, "= Lucro bruto", lucroBruto, true));

        BigDecimal perdas = perdasEAjustes(con, periodo, zona);
        linhas.add(new Linha("7", 7, "(−) Perdas e ajustes de estoque", perdas, false));

        BigDecimal cashback = valor(con, """
                SELECT coalesce(sum(valor), 0) FROM cliente_credito_movimento
                 WHERE tipo IN ('CASHBACK', 'ESTORNO_CASHBACK') AND criado_em >= ? AND criado_em < ?
                """, de, ate);
        linhas.add(new Linha("8", 8, "(−) Cashback concedido", cashback.negate(), false));

        List<Linha> operacionais = new ArrayList<>();
        List<Linha> impostos = new ArrayList<>();
        List<Linha> financeiras = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT c.grupo, c.nome, sum(t.valor) AS valor
                  FROM titulo_pagar t
                  JOIN categoria_despesa c ON c.id = t.categoria_id
                 WHERE t.status <> 'CANCELADO' AND t.competencia >= ? AND t.competencia <= ?
                   AND c.grupo IN ('OPERACIONAL', 'OUTRA', 'IMPOSTO', 'FINANCEIRA')
                 GROUP BY c.grupo, c.id, c.nome
                 ORDER BY c.id
                """)) {
            ps.setObject(1, periodo.inicio());
            ps.setObject(2, periodo.fim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String grupo = rs.getString("grupo");
                    String nome = rs.getString("nome");
                    BigDecimal valor = rs.getBigDecimal("valor").negate();
                    switch (grupo) {
                        case "IMPOSTO" -> impostos.add(new Linha("10:" + nome, null, nome, valor, false));
                        case "FINANCEIRA" -> financeiras.add(new Linha("11:" + nome, null, nome, valor, false));
                        default -> operacionais.add(new Linha("9:" + nome, null, nome, valor, false));
                    }
                }
            }
        }
        BigDecimal totalOperacionais = soma(operacionais);
        linhas.add(new Linha("9", 9, "(−) Despesas operacionais", totalOperacionais, false));
        linhas.addAll(operacionais);
        BigDecimal totalImpostos = soma(impostos);
        linhas.add(new Linha("10", 10, "(−) Impostos sobre vendas", totalImpostos, false));

        BigDecimal taxas = valor(con, """
                SELECT coalesce(sum(r.taxa), 0)
                  FROM recebivel r
                  LEFT JOIN venda_pagamento vp      ON vp.id = r.venda_pagamento_id
                  LEFT JOIN venda v                 ON v.id = vp.venda_id
                  LEFT JOIN recebimento_pagamento rp ON rp.id = r.recebimento_pagamento_id
                  LEFT JOIN recebimento rc          ON rc.id = rp.recebimento_id
                 WHERE r.status <> 'CANCELADO'
                   AND coalesce(v.data_hora, rc.data_hora) >= ? AND coalesce(v.data_hora, rc.data_hora) < ?
                """, de, ate);
        BigDecimal jurosPagos;
        BigDecimal descontosObtidos;
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT coalesce(sum(juros), 0), coalesce(sum(desconto), 0)
                  FROM titulo_pagar_pagamento
                 WHERE NOT estornado AND data >= ? AND data <= ?
                """)) {
            ps.setObject(1, periodo.inicio());
            ps.setObject(2, periodo.fim());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                jurosPagos = rs.getBigDecimal(1);
                descontosObtidos = rs.getBigDecimal(2);
            }
        }
        financeiras.add(0, new Linha("11:Juros pagos", null, "Juros pagos", jurosPagos.negate(), false));
        financeiras.add(0, new Linha("11:Taxas", null, "Taxas de cartão e PIX", taxas.negate(), false));
        linhas.add(new Linha("11", 11, "(−) Despesas financeiras", soma(financeiras), false));
        linhas.addAll(financeiras);

        BigDecimal encargos = valor(con, """
                SELECT coalesce(sum(multa + juros - desconto), 0) FROM recebimento
                 WHERE NOT estornado AND data_hora >= ? AND data_hora < ?
                """, de, ate);
        List<Linha> receitas = List.of(
                new Linha("12:Encargos", null, "Multas e juros recebidos", encargos, false),
                new Linha("12:Descontos", null, "Descontos obtidos", descontosObtidos, false));
        linhas.add(new Linha("12", 12, "(+) Receitas financeiras", soma(receitas), false));
        linhas.addAll(receitas);

        BigDecimal diferencas = valor(con, """
                SELECT coalesce(sum(diferenca), 0) FROM caixa_sessao
                 WHERE status = 'FECHADO' AND fechado_em >= ? AND fechado_em < ?
                """, de, ate);
        linhas.add(new Linha("13", 13, "(±) Diferenças de caixa", diferencas, false));

        BigDecimal resultado = lucroBruto;
        for (Linha l : linhas) {
            if (!l.detalhe() && l.numero() >= 7) {
                resultado = resultado.add(l.valor());
            }
        }
        linhas.add(new Linha("14", 14, "= Resultado líquido", resultado, true));
        return linhas;
    }

    /** Linha 14 de {@link #calcular}. */
    public static BigDecimal resultadoLiquido(List<Linha> linhas) {
        return valorDa(linhas, 14);
    }

    public static BigDecimal valorDa(List<Linha> linhas, int numero) {
        return linhas.stream().filter(l -> !l.detalhe() && l.numero() == numero).findFirst().orElseThrow().valor();
    }

    /** Σ do R14 no período, sem as entradas de estoque inicial e de importação. */
    private static BigDecimal perdasEAjustes(Connection con, Periodo periodo, ZoneId zona) throws SQLException {
        List<RelPerdasAjustes.Linha> r14 = new RelPerdasAjustes().consultar(con, Filtros.vazios().comPeriodo(periodo),
                new ParametrosRelatorio(zona, null, null));
        BigDecimal soma = Dinheiro.ZERO;
        for (RelPerdasAjustes.Linha l : r14) {
            if (!MOTIVOS_FORA_DA_LINHA_7.contains(l.motivo().toLowerCase())) {
                soma = soma.add(l.valor());
            }
        }
        return soma;
    }

    private static BigDecimal soma(List<Linha> linhas) {
        return linhas.stream().map(Linha::valor).reduce(Dinheiro.ZERO, BigDecimal::add);
    }

    private static BigDecimal valor(Connection con, String sql, OffsetDateTime de, OffsetDateTime ate)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, de);
            ps.setObject(2, ate);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Período do mês inteiro. */
    public static Periodo mes(LocalDate qualquerDia) {
        LocalDate inicio = qualquerDia.withDayOfMonth(1);
        return new Periodo(inicio, inicio.plusMonths(1).minusDays(1));
    }
}
