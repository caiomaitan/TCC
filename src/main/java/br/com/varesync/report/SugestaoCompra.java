package br.com.varesync.report;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Sugestão de compra e cobertura (F16.2), base da aba Sugestão de compra da tela Compras, do R26 e do R28. Produtos
 * ativos, com o saldo de {@code vw_estoque_atual} e a venda líquida de devoluções de {@code vw_resultado_item}.
 *
 * <ul>
 *   <li>média diária = (vendido − devolvido nos últimos {@code compras.dias_media} dias, hoje incluído) ÷ dias; venda
 *       líquida negativa conta como zero;</li>
 *   <li>necessidade = média × ({@code compras.cobertura_dias} + prazo de entrega do fornecedor principal);</li>
 *   <li>sugestão = o maior entre (necessidade − saldo − pendente) e (estoque mínimo − saldo − pendente), nunca
 *       negativa, arredondada para cima no múltiplo do fator do fornecedor principal (sem principal: prazo 0 e fator
 *       1).</li>
 * </ul>
 * O pendente em pedidos de compra só existe a partir da parte B da F16 (pedido de compra); até lá é zero.
 */
public final class SugestaoCompra {

    /** Dias da venda recente que marca a ruptura (F16.2: "venderam nos últimos 30 dias"). */
    public static final int DIAS_RUPTURA = 30;

    private SugestaoCompra() {
    }

    /**
     * Uma linha por produto ativo.
     *
     * @param mediaDia      média de venda por dia, 3 casas
     * @param coberturaDias dias que o saldo dura na média (inteiros, para baixo); nulo sem venda na média
     * @param ultimoCusto   do fornecedor principal; sem ele, o último custo do produto ou o custo médio
     * @param fornecedorId  fornecedor principal, nulo sem ele
     * @param ruptura       saldo ≤ 0 e houve venda nos últimos 30 dias
     */
    public record Linha(long produtoId, String produto, String categoria, String unidade, boolean fracionada,
                        BigDecimal saldo, BigDecimal estoqueMinimo, BigDecimal mediaDia, Long coberturaDias,
                        BigDecimal pendente, BigDecimal sugestao, BigDecimal ultimoCusto, BigDecimal valorEstimado,
                        Long fornecedorId, String fornecedor, int prazoEntregaDias, BigDecimal fator,
                        String unidadeCompra, boolean ruptura) {

        /** Sugestão na unidade de compra (sugestão ÷ fator, exato porque a sugestão é múltiplo do fator). */
        public BigDecimal sugestaoNaUnidadeDeCompra() {
            return sugestao.divide(fator, Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
        }
    }

    /** Resultado do cálculo de um produto (3 casas). */
    public record Calculo(BigDecimal mediaDia, BigDecimal necessidade, BigDecimal sugestao, Long coberturaDias) {
    }

    /**
     * Cálculo de 1 produto, sem banco.
     *
     * @param vendidoLiquido quantidade vendida − devolvida nos {@code diasMedia} dias
     * @param fator          múltiplo da sugestão (≥ 1)
     */
    public static Calculo calcular(BigDecimal vendidoLiquido, int diasMedia, int coberturaDias, int prazoDias,
                                   BigDecimal saldo, BigDecimal pendente, BigDecimal estoqueMinimo, BigDecimal fator) {
        if (diasMedia < 1) {
            throw new IllegalArgumentException("Dias da média devem ser 1 ou mais");
        }
        BigDecimal liquido = vendidoLiquido.max(BigDecimal.ZERO);
        BigDecimal dias = BigDecimal.valueOf(diasMedia);
        BigDecimal media = liquido.divide(dias, Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
        // Sem arredondar a média antes: necessidade = vendido × (cobertura + prazo) ÷ dias.
        BigDecimal necessidade = liquido.multiply(BigDecimal.valueOf((long) coberturaDias + prazoDias))
                .divide(dias, Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
        BigDecimal pelaVenda = necessidade.subtract(saldo).subtract(pendente);
        BigDecimal peloMinimo = estoqueMinimo.subtract(saldo).subtract(pendente);
        BigDecimal falta = pelaVenda.max(peloMinimo).max(BigDecimal.ZERO);
        BigDecimal sugestao = falta.signum() == 0 ? BigDecimal.ZERO
                : falta.divide(fator, 0, RoundingMode.CEILING).multiply(fator);
        Long cobertura = liquido.signum() == 0 ? null
                : saldo.max(BigDecimal.ZERO).multiply(dias).divide(liquido, 0, RoundingMode.FLOOR).longValueExact();
        return new Calculo(media, necessidade, sugestao.setScale(Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO),
                cobertura);
    }

    /**
     * Linhas dos produtos ativos, por nome.
     *
     * @param fornecedorId só os produtos com este fornecedor principal; nulo = todos
     * @param categoriaId  nulo = todas
     */
    public static List<Linha> consultar(Connection con, Long fornecedorId, Long categoriaId, LocalDate hoje,
                                        ZoneId zona, Configuracoes.Compras compras) throws SQLException {
        String sql = """
                SELECT e.produto_id, e.nome, e.categoria_nome, e.unidade, um.fracionada, e.saldo, e.estoque_minimo,
                       coalesce(p.ultimo_custo, p.custo) AS custo_produto,
                       pf.fornecedor_id, f.razao_social, pf.prazo_entrega_dias, pf.fator, pf.unidade_compra,
                       pf.ultimo_custo AS custo_vinculo,
                       coalesce(v.liquido, 0) AS liquido, coalesce(v.vendido_recente, 0) AS vendido_recente
                  FROM vw_estoque_atual e
                  JOIN produto p                ON p.id = e.produto_id
                  JOIN unidade_medida um        ON um.sigla = e.unidade
                  LEFT JOIN produto_fornecedor pf ON pf.produto_id = e.produto_id AND pf.principal
                  LEFT JOIN fornecedor f        ON f.id = pf.fornecedor_id
                  LEFT JOIN (SELECT r.produto_id,
                                    sum(r.quantidade) FILTER (WHERE r.data_hora >= ?) AS liquido,
                                    sum(r.quantidade) FILTER (WHERE r.tipo = 'VENDA' AND r.data_hora >= ?)
                                        AS vendido_recente
                               FROM vw_resultado_item r
                              WHERE r.data_hora >= ? AND r.data_hora < ?
                              GROUP BY r.produto_id) v ON v.produto_id = e.produto_id
                 WHERE e.ativo
                   AND (CAST(? AS BIGINT) IS NULL OR pf.fornecedor_id = ?)
                   AND (CAST(? AS BIGINT) IS NULL OR e.categoria_id = ?)
                 ORDER BY e.nome, e.produto_id
                """;
        // Últimos N dias com hoje incluído (como o R12): de (hoje − N + 1) 00:00 até amanhã 00:00.
        OffsetDateTime inicioMedia = inicio(hoje, compras.diasMedia(), zona);
        OffsetDateTime inicioRuptura = inicio(hoje, DIAS_RUPTURA, zona);
        OffsetDateTime fim = hoje.plusDays(1).atStartOfDay(zona).toOffsetDateTime();
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, inicioMedia);
            ps.setObject(2, inicioRuptura);
            ps.setObject(3, inicioMedia.isBefore(inicioRuptura) ? inicioMedia : inicioRuptura);
            ps.setObject(4, fim);
            Consultas.opcional(ps, 5, fornecedorId);
            Consultas.opcional(ps, 7, categoriaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(ler(rs, compras));
                }
            }
        }
        return linhas;
    }

    private static Linha ler(ResultSet rs, Configuracoes.Compras compras) throws SQLException {
        Long fornecedor = rs.getObject("fornecedor_id") == null ? null : rs.getLong("fornecedor_id");
        int prazo = fornecedor == null ? 0 : rs.getInt("prazo_entrega_dias");
        BigDecimal fator = fornecedor == null ? BigDecimal.ONE : rs.getBigDecimal("fator");
        BigDecimal saldo = rs.getBigDecimal("saldo");
        // Pendente em pedidos de compra: parte B da F16.
        BigDecimal pendente = BigDecimal.ZERO.setScale(Dinheiro.ESCALA_QUANTIDADE);
        Calculo c = calcular(rs.getBigDecimal("liquido"), compras.diasMedia(), compras.coberturaDias(), prazo, saldo,
                pendente, rs.getBigDecimal("estoque_minimo"), fator);
        BigDecimal custo = rs.getBigDecimal("custo_vinculo") != null ? rs.getBigDecimal("custo_vinculo")
                : rs.getBigDecimal("custo_produto");
        return new Linha(rs.getLong("produto_id"), rs.getString("nome"), rs.getString("categoria_nome"),
                rs.getString("unidade"), rs.getBoolean("fracionada"), saldo, rs.getBigDecimal("estoque_minimo"),
                c.mediaDia(), c.coberturaDias(), pendente, c.sugestao(), custo, Dinheiro.multiplicar(c.sugestao(), custo),
                fornecedor, rs.getString("razao_social"), prazo, fator, rs.getString("unidade_compra"),
                saldo.signum() <= 0 && rs.getBigDecimal("vendido_recente").signum() > 0);
    }

    private static OffsetDateTime inicio(LocalDate hoje, int dias, ZoneId zona) {
        return hoje.minusDays(dias - 1L).atStartOfDay(zona).toOffsetDateTime();
    }
}
