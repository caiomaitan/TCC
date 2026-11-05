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
 * O pendente é a soma de (quantidade − recebida) dos pedidos de compra ENVIADO e PARCIAL, sem ficar negativo em nenhum
 * item (F16.3). Fornecedor principal inativo deixa de valer como principal: prazo 0, fator 1 e o custo do produto,
 * com aviso na linha.
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
     * @param fornecedorId  fornecedor principal ativo, nulo sem ele (ou com o principal inativo)
     * @param aviso         "Fornecedor principal inativo (…)" quando o principal está inativo; nulo sem aviso
     * @param ruptura       saldo ≤ 0 e houve venda nos últimos 30 dias
     */
    public record Linha(long produtoId, String produto, String categoria, String unidade, boolean fracionada,
                        BigDecimal saldo, BigDecimal estoqueMinimo, BigDecimal mediaDia, Long coberturaDias,
                        BigDecimal pendente, BigDecimal sugestao, BigDecimal ultimoCusto, BigDecimal valorEstimado,
                        Long fornecedorId, String fornecedor, int prazoEntregaDias, BigDecimal fator,
                        String unidadeCompra, boolean ruptura, String aviso) {

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
                       pf.ultimo_custo AS custo_vinculo, f.ativo AS fornecedor_ativo,
                       coalesce(pend.pendente, 0) AS pendente,
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
                  LEFT JOIN (SELECT i.produto_id, sum(greatest(i.quantidade - i.quantidade_recebida, 0)) AS pendente
                               FROM pedido_compra_item i
                               JOIN pedido_compra pc ON pc.id = i.pedido_id
                              WHERE pc.status IN ('ENVIADO', 'PARCIAL')
                              GROUP BY i.produto_id) pend ON pend.produto_id = e.produto_id
                 WHERE e.ativo
                   AND (CAST(? AS BIGINT) IS NULL OR (pf.fornecedor_id = ? AND f.ativo))
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
        boolean temPrincipal = rs.getObject("fornecedor_id") != null;
        // F16.3: o principal inativo deixa de valer (prazo 0, fator 1 e o custo do produto), com aviso.
        boolean ativo = temPrincipal && rs.getBoolean("fornecedor_ativo");
        Long fornecedor = ativo ? rs.getLong("fornecedor_id") : null;
        String aviso = temPrincipal && !ativo ? avisoInativo(rs.getString("razao_social")) : null;
        int prazo = ativo ? rs.getInt("prazo_entrega_dias") : 0;
        BigDecimal fator = ativo ? rs.getBigDecimal("fator") : BigDecimal.ONE;
        BigDecimal saldo = rs.getBigDecimal("saldo");
        BigDecimal pendente = rs.getBigDecimal("pendente").setScale(Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
        Calculo c = calcular(rs.getBigDecimal("liquido"), compras.diasMedia(), compras.coberturaDias(), prazo, saldo,
                pendente, rs.getBigDecimal("estoque_minimo"), fator);
        BigDecimal custo = ativo && rs.getBigDecimal("custo_vinculo") != null ? rs.getBigDecimal("custo_vinculo")
                : rs.getBigDecimal("custo_produto");
        return new Linha(rs.getLong("produto_id"), rs.getString("nome"), rs.getString("categoria_nome"),
                rs.getString("unidade"), rs.getBoolean("fracionada"), saldo, rs.getBigDecimal("estoque_minimo"),
                c.mediaDia(), c.coberturaDias(), pendente, c.sugestao(), custo, Dinheiro.multiplicar(c.sugestao(), custo),
                fornecedor, ativo ? rs.getString("razao_social") : null, prazo, fator,
                ativo ? rs.getString("unidade_compra") : null,
                saldo.signum() <= 0 && rs.getBigDecimal("vendido_recente").signum() > 0, aviso);
    }

    /** Aviso da linha com o fornecedor principal inativo (F16.3). */
    public static String avisoInativo(String fornecedor) {
        return "Fornecedor principal inativo (" + fornecedor + "): prazo 0 e fator 1";
    }

    private static OffsetDateTime inicio(LocalDate hoje, int dias, ZoneId zona) {
        return hoje.minusDays(dias - 1L).atStartOfDay(zona).toOffsetDateTime();
    }
}
