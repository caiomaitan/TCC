package br.com.varesync.dao;

import br.com.varesync.model.TributoItem;
import br.com.varesync.model.ItemTributavel;
import br.com.varesync.model.PerfilTributario;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tributos de cada item vendido ({@code venda_item_tributo}, F22). */
public class TributoVendaDAO {

    /** Item que entra no cálculo, com o cadastro fiscal lido na transação da venda. */
    public record ItemParaCalculo(long vendaItemId, ItemTributavel item) {
    }

    /**
     * Itens não cancelados da venda cujo produto tem NCM, origem e perfil tributário ativo, com o ST retido do produto
     * e a linha IBPT do NCM ({@code ex} vazio) vigente em {@code data}. Os outros itens ficam sem tributos.
     */
    public List<ItemParaCalculo> itensParaCalculo(Connection con, long vendaId, LocalDate data) throws SQLException {
        String sql = """
                SELECT vi.id, vi.quantidade, vi.valor_liquido, p.ncm, p.cest, p.origem,
                       p.st_base_unitaria, p.st_aliquota, p.st_icms_substituto_unitario, p.st_icms_retido_unitario,
                       t.id AS perfil_id, t.nome, t.cfop, t.cst_icms, t.csosn, t.aliquota_icms, t.reducao_base_icms,
                       t.cst_pis, t.aliquota_pis, t.cst_cofins, t.aliquota_cofins, t.codigo_beneficio, t.cst_ibs_cbs,
                       t.cclass_trib, t.aliquota_fcp, t.reducao_aliquota_ibs_cbs,
                       i.codigo AS ibpt_codigo, i.aliquota_nacional_federal, i.aliquota_importados_federal,
                       i.aliquota_estadual, i.aliquota_municipal, i.versao
                  FROM venda_item vi
                  JOIN produto p ON p.id = vi.produto_id
                  JOIN perfil_tributario t ON t.id = p.perfil_tributario_id AND t.ativo
                  LEFT JOIN ibpt i ON i.codigo = p.ncm AND i.ex = ''
                                  AND i.vigencia_inicio <= ? AND i.vigencia_fim >= ?
                 WHERE vi.venda_id = ? AND NOT vi.cancelado AND p.ncm IS NOT NULL AND p.origem IS NOT NULL
                 ORDER BY vi.sequencia
                """;
        List<ItemParaCalculo> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, data);
            ps.setObject(2, data);
            ps.setLong(3, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PerfilTributario perfil = new PerfilTributario(rs.getLong("perfil_id"), rs.getString("nome"),
                            rs.getString("cfop"), rs.getString("cst_icms"), rs.getString("csosn"),
                            rs.getBigDecimal("aliquota_icms"), rs.getBigDecimal("reducao_base_icms"),
                            rs.getString("cst_pis"), rs.getBigDecimal("aliquota_pis"), rs.getString("cst_cofins"),
                            rs.getBigDecimal("aliquota_cofins"), rs.getString("codigo_beneficio"),
                            rs.getString("cst_ibs_cbs"), rs.getString("cclass_trib"), true, 0,
                            rs.getBigDecimal("aliquota_fcp"), rs.getBigDecimal("reducao_aliquota_ibs_cbs"));
                    ItemTributavel.StRetido st = new ItemTributavel.StRetido(rs.getBigDecimal("st_base_unitaria"),
                            rs.getBigDecimal("st_aliquota"), rs.getBigDecimal("st_icms_substituto_unitario"),
                            rs.getBigDecimal("st_icms_retido_unitario"));
                    ItemTributavel.Ibpt ibpt = rs.getString("ibpt_codigo") == null ? null
                            : new ItemTributavel.Ibpt(rs.getBigDecimal("aliquota_nacional_federal"),
                            rs.getBigDecimal("aliquota_importados_federal"), rs.getBigDecimal("aliquota_estadual"),
                            rs.getBigDecimal("aliquota_municipal"), rs.getString("versao"));
                    itens.add(new ItemParaCalculo(rs.getLong("id"), new ItemTributavel(
                            rs.getBigDecimal("quantidade"), rs.getBigDecimal("valor_liquido"), rs.getString("ncm"),
                            rs.getString("cest"), rs.getInt("origem"), perfil, st, ibpt)));
                }
            }
        }
        return itens;
    }

    public void inserir(Connection con, long vendaItemId, TributoItem t) throws SQLException {
        String sql = """
                INSERT INTO venda_item_tributo (venda_item_id, ncm, cest, origem, cfop, cst_icms, csosn,
                       codigo_beneficio, cst_pis, cst_cofins, cst_ibs_cbs, cclass_trib,
                       base_icms, aliquota_icms, reducao_base_icms, valor_icms, aliquota_fcp, valor_fcp,
                       base_st_retido, aliquota_st, icms_substituto, icms_st_retido,
                       base_pis, aliquota_pis, valor_pis, base_cofins, aliquota_cofins, valor_cofins,
                       base_ibs_cbs, aliquota_cbs, aliquota_ibs_uf, aliquota_ibs_mun, reducao_aliquota_ibs_cbs,
                       valor_cbs, valor_ibs_uf, valor_ibs_mun,
                       trib_federal, trib_estadual, trib_municipal, ibpt_versao)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, vendaItemId);
            texto(ps, i++, t.ncm());
            texto(ps, i++, t.cest());
            ps.setInt(i++, t.origem());
            texto(ps, i++, t.cfop());
            texto(ps, i++, t.cstIcms());
            texto(ps, i++, t.csosn());
            texto(ps, i++, t.codigoBeneficio());
            texto(ps, i++, t.cstPis());
            texto(ps, i++, t.cstCofins());
            texto(ps, i++, t.cstIbsCbs());
            texto(ps, i++, t.cclassTrib());
            for (BigDecimal valor : new BigDecimal[] {t.baseIcms(), t.aliquotaIcms(), t.reducaoBaseIcms(),
                    t.valorIcms(), t.aliquotaFcp(), t.valorFcp(), t.baseStRetido(), t.aliquotaSt(),
                    t.icmsSubstituto(), t.icmsStRetido(), t.basePis(), t.aliquotaPis(), t.valorPis(),
                    t.baseCofins(), t.aliquotaCofins(), t.valorCofins(), t.baseIbsCbs(), t.aliquotaCbs(),
                    t.aliquotaIbsUf(), t.aliquotaIbsMun(), t.reducaoAliquotaIbsCbs(), t.valorCbs(), t.valorIbsUf(),
                    t.valorIbsMun(), t.tribFederal(), t.tribEstadual(), t.tribMunicipal()}) {
                ps.setBigDecimal(i++, valor);
            }
            texto(ps, i, t.ibptVersao());
            ps.executeUpdate();
        }
    }

    /** Tributos gravados do item (segunda via, R31 e testes). */
    public Optional<TributoItem> buscar(Connection con, long vendaItemId) throws SQLException {
        String sql = """
                SELECT ncm, cest, origem, cfop, cst_icms, csosn, codigo_beneficio, cst_pis, cst_cofins, cst_ibs_cbs,
                       cclass_trib, base_icms, aliquota_icms, reducao_base_icms, valor_icms, aliquota_fcp, valor_fcp,
                       base_st_retido, aliquota_st, icms_substituto, icms_st_retido, base_pis, aliquota_pis,
                       valor_pis, base_cofins, aliquota_cofins, valor_cofins, base_ibs_cbs, aliquota_cbs,
                       aliquota_ibs_uf, aliquota_ibs_mun, reducao_aliquota_ibs_cbs, valor_cbs, valor_ibs_uf,
                       valor_ibs_mun, trib_federal, trib_estadual, trib_municipal, ibpt_versao
                  FROM venda_item_tributo
                 WHERE venda_item_id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TributoItem(rs.getString("ncm"), rs.getString("cest"), rs.getInt("origem"),
                        rs.getString("cfop"), rs.getString("cst_icms"), rs.getString("csosn"),
                        rs.getString("codigo_beneficio"), rs.getString("cst_pis"), rs.getString("cst_cofins"),
                        rs.getString("cst_ibs_cbs"), rs.getString("cclass_trib"), rs.getBigDecimal("base_icms"),
                        rs.getBigDecimal("aliquota_icms"), rs.getBigDecimal("reducao_base_icms"),
                        rs.getBigDecimal("valor_icms"), rs.getBigDecimal("aliquota_fcp"),
                        rs.getBigDecimal("valor_fcp"), rs.getBigDecimal("base_st_retido"),
                        rs.getBigDecimal("aliquota_st"), rs.getBigDecimal("icms_substituto"),
                        rs.getBigDecimal("icms_st_retido"), rs.getBigDecimal("base_pis"),
                        rs.getBigDecimal("aliquota_pis"), rs.getBigDecimal("valor_pis"),
                        rs.getBigDecimal("base_cofins"), rs.getBigDecimal("aliquota_cofins"),
                        rs.getBigDecimal("valor_cofins"), rs.getBigDecimal("base_ibs_cbs"),
                        rs.getBigDecimal("aliquota_cbs"), rs.getBigDecimal("aliquota_ibs_uf"),
                        rs.getBigDecimal("aliquota_ibs_mun"), rs.getBigDecimal("reducao_aliquota_ibs_cbs"),
                        rs.getBigDecimal("valor_cbs"), rs.getBigDecimal("valor_ibs_uf"),
                        rs.getBigDecimal("valor_ibs_mun"), rs.getBigDecimal("trib_federal"),
                        rs.getBigDecimal("trib_estadual"), rs.getBigDecimal("trib_municipal"),
                        rs.getString("ibpt_versao")));
            }
        }
    }

    private static void texto(PreparedStatement ps, int indice, String valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.VARCHAR);
        } else {
            ps.setString(indice, valor);
        }
    }
}
