package br.com.varesync.report;

import br.com.varesync.model.Permissao;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * R31 — Impostos das vendas (F22.5): os tributos gravados nos itens das vendas finalizadas do período
 * ({@code venda_item_tributo}), agrupados por CFOP, situação do ICMS (CST ou CSOSN) e alíquota do ICMS, e uma linha à
 * parte, "Itens sem tributação calculada", com a quantidade e o valor dos itens sem tributos (produto sem dados fiscais
 * na venda, ou venda anterior à F22). Vendas canceladas e itens cancelados não entram; devoluções também não, porque o
 * sistema não emite nota de devolução. Pede também a permissão FISCAL, como o R30.
 */
public final class RelImpostosVendas implements Relatorio<RelImpostosVendas.Linha> {

    public static final String SEM_TRIBUTACAO = "Itens sem tributação calculada";

    /**
     * Um grupo (CFOP, situação e alíquota do ICMS) ou a linha dos itens sem tributação ({@code cfop} nulo).
     *
     * @param ibs          IBS da UF + IBS do município; nulo quando nenhum item do grupo tem IBS e CBS
     * @param aproximados  federal + estadual + municipal; nulo sem nenhum item com a tabela IBPT
     */
    public record Linha(String cfop, String situacao, BigDecimal aliquotaIcms, long itens, BigDecimal valorLiquido,
                        BigDecimal baseIcms, BigDecimal valorIcms, BigDecimal fcp, BigDecimal pis, BigDecimal cofins,
                        BigDecimal cbs, BigDecimal ibs, BigDecimal aproximados) {

        public boolean semTributacao() {
            return cfop == null;
        }
    }

    @Override
    public String codigo() {
        return "R31";
    }

    @Override
    public String nome() {
        return "Impostos das vendas";
    }

    @Override
    public String descricao() {
        return "Tributos calculados nos itens vendidos, por CFOP e situação do ICMS; à parte, os itens sem tributação "
                + "calculada. Vendas canceladas não entram.";
    }

    @Override
    public Set<TipoFiltro> filtros() {
        return EnumSet.of(TipoFiltro.PERIODO);
    }

    @Override
    public Set<Permissao> permissoesExtras() {
        return Set.of(Permissao.FISCAL);
    }

    @Override
    public List<Linha> consultar(Connection con, Filtros filtros, ParametrosRelatorio parametros) throws SQLException {
        String sql = """
                SELECT t.cfop, CASE WHEN t.cst_icms IS NOT NULL THEN 'CST ' || t.cst_icms ELSE 'CSOSN ' || t.csosn END
                           AS situacao,
                       t.aliquota_icms, count(*) AS itens, sum(vi.valor_liquido) AS liquido, sum(t.base_icms) AS base,
                       sum(t.valor_icms) AS icms, sum(t.valor_fcp) AS fcp, sum(t.valor_pis) AS pis,
                       sum(t.valor_cofins) AS cofins, sum(t.valor_cbs) AS cbs,
                       sum(t.valor_ibs_uf + t.valor_ibs_mun) AS ibs,
                       sum(t.trib_federal + t.trib_estadual + t.trib_municipal) AS aproximados
                  FROM venda v
                  JOIN venda_item vi ON vi.venda_id = v.id AND NOT vi.cancelado
                  JOIN venda_item_tributo t ON t.venda_item_id = vi.id
                 WHERE v.status = 'FINALIZADA' AND v.data_hora >= ? AND v.data_hora < ?
                 GROUP BY t.cfop, situacao, t.aliquota_icms
                 ORDER BY t.cfop, situacao, t.aliquota_icms
                """;
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getString("cfop"), rs.getString("situacao"),
                            rs.getBigDecimal("aliquota_icms"), rs.getLong("itens"), rs.getBigDecimal("liquido"),
                            rs.getBigDecimal("base"), rs.getBigDecimal("icms"), rs.getBigDecimal("fcp"),
                            rs.getBigDecimal("pis"), rs.getBigDecimal("cofins"), rs.getBigDecimal("cbs"),
                            rs.getBigDecimal("ibs"), rs.getBigDecimal("aproximados")));
                }
            }
        }
        String sem = """
                SELECT count(*), sum(vi.valor_liquido)
                  FROM venda v
                  JOIN venda_item vi ON vi.venda_id = v.id AND NOT vi.cancelado
                  LEFT JOIN venda_item_tributo t ON t.venda_item_id = vi.id
                 WHERE v.status = 'FINALIZADA' AND v.data_hora >= ? AND v.data_hora < ? AND t.venda_item_id IS NULL
                """;
        try (PreparedStatement ps = con.prepareStatement(sem)) {
            Consultas.periodo(ps, 1, filtros, parametros);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    linhas.add(new Linha(null, SEM_TRIBUTACAO, null, rs.getLong(1), rs.getBigDecimal(2), null, null,
                            null, null, null, null, null, null));
                }
            }
        }
        return linhas;
    }

    @Override
    public List<Coluna<Linha>> colunas(Filtros filtros) {
        return List.of(
                Coluna.<Linha>texto("CFOP", l -> l.cfop() == null ? "" : l.cfop()).comLargura(0.8),
                Coluna.<Linha>texto("Situação do ICMS", Linha::situacao).comLargura(2.6),
                Coluna.<Linha>texto("Alíquota", l -> l.aliquotaIcms() == null ? ""
                        : Formatos.percentual(l.aliquotaIcms())).comLargura(0.95),
                Coluna.<Linha>inteiro("Itens", Linha::itens).comLargura(0.8),
                Coluna.moeda("Valor líquido", Linha::valorLiquido),
                Coluna.moeda("Base do ICMS", Linha::baseIcms),
                Coluna.moeda("ICMS", Linha::valorIcms),
                Coluna.moeda("FCP", Linha::fcp),
                Coluna.moeda("PIS", Linha::pis),
                Coluna.moeda("COFINS", Linha::cofins),
                Coluna.moeda("CBS", Linha::cbs),
                Coluna.moeda("IBS", Linha::ibs),
                Coluna.moeda("Trib. aproximados", Linha::aproximados));
    }
}
