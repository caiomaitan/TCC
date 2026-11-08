package br.com.varesync.dao;

import br.com.varesync.model.PerfilTributario;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Perfis tributários (F19.2). */
public class PerfilTributarioDAO {

    /** Valores já normalizados pelo service. */
    public record Gravacao(String nome, String cfop, String cstIcms, String csosn, BigDecimal aliquotaIcms,
                           BigDecimal reducaoBaseIcms, String cstPis, BigDecimal aliquotaPis, String cstCofins,
                           BigDecimal aliquotaCofins, String codigoBeneficio, String cstIbsCbs, String cclassTrib,
                           boolean ativo) {
    }

    private static final String COLUNAS = """
            SELECT t.id, t.nome, t.cfop, t.cst_icms, t.csosn, t.aliquota_icms, t.reducao_base_icms, t.cst_pis,
                   t.aliquota_pis, t.cst_cofins, t.aliquota_cofins, t.codigo_beneficio, t.cst_ibs_cbs, t.cclass_trib,
                   t.ativo, (SELECT count(*) FROM produto p WHERE p.perfil_tributario_id = t.id) AS produtos
              FROM perfil_tributario t
            """;

    /** Na ordem do nome; com {@code soAtivos}, só os ativos. */
    public List<PerfilTributario> listar(Connection con, boolean soAtivos) throws SQLException {
        String sql = COLUNAS + (soAtivos ? " WHERE t.ativo" : "") + " ORDER BY lower(t.nome), t.id";
        List<PerfilTributario> perfis = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                perfis.add(ler(rs));
            }
        }
        return perfis;
    }

    public Optional<PerfilTributario> buscar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE t.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Trava a linha do perfil (aplicação em lote e exclusão). */
    public Optional<PerfilTributario> travar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT id FROM perfil_tributario WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
            }
        }
        return buscar(con, id);
    }

    /** Outro perfil com o mesmo nome, sem diferenciar maiúsculas. */
    public Optional<PerfilTributario> mesmoNome(Connection con, String nome, Long exceto) throws SQLException {
        String sql = COLUNAS + " WHERE lower(t.nome) = lower(?) AND (?::bigint IS NULL OR t.id <> ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nome);
            Jdbc.definirLong(ps, 2, exceto);
            Jdbc.definirLong(ps, 3, exceto);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    public long inserir(Connection con, Gravacao g, long usuarioId, OffsetDateTime agora) throws SQLException {
        String sql = """
                INSERT INTO perfil_tributario (nome, cfop, cst_icms, csosn, aliquota_icms, reducao_base_icms, cst_pis,
                                               aliquota_pis, cst_cofins, aliquota_cofins, codigo_beneficio,
                                               cst_ibs_cbs, cclass_trib, ativo, criado_em, criado_por,
                                               atualizado_em, atualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g);
            ps.setObject(i++, agora);
            ps.setLong(i++, usuarioId);
            ps.setObject(i++, agora);
            ps.setLong(i, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void alterar(Connection con, long id, Gravacao g, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        String sql = """
                UPDATE perfil_tributario
                   SET nome = ?, cfop = ?, cst_icms = ?, csosn = ?, aliquota_icms = ?, reducao_base_icms = ?,
                       cst_pis = ?, aliquota_pis = ?, cst_cofins = ?, aliquota_cofins = ?, codigo_beneficio = ?,
                       cst_ibs_cbs = ?, cclass_trib = ?, ativo = ?, atualizado_em = ?, atualizado_por = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definir(ps, g);
            ps.setObject(i++, agora);
            ps.setLong(i++, usuarioId);
            ps.setLong(i, id);
            ps.executeUpdate();
        }
    }

    public void excluir(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM perfil_tributario WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static int definir(PreparedStatement ps, Gravacao g) throws SQLException {
        int i = 1;
        ps.setString(i++, g.nome());
        ps.setString(i++, g.cfop());
        texto(ps, i++, g.cstIcms());
        texto(ps, i++, g.csosn());
        ps.setBigDecimal(i++, g.aliquotaIcms());
        ps.setBigDecimal(i++, g.reducaoBaseIcms());
        ps.setString(i++, g.cstPis());
        ps.setBigDecimal(i++, g.aliquotaPis());
        ps.setString(i++, g.cstCofins());
        ps.setBigDecimal(i++, g.aliquotaCofins());
        texto(ps, i++, g.codigoBeneficio());
        texto(ps, i++, g.cstIbsCbs());
        texto(ps, i++, g.cclassTrib());
        ps.setBoolean(i++, g.ativo());
        return i;
    }

    private static void texto(PreparedStatement ps, int indice, String valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.VARCHAR);
        } else {
            ps.setString(indice, valor);
        }
    }

    private static PerfilTributario ler(ResultSet rs) throws SQLException {
        return new PerfilTributario(rs.getLong("id"), rs.getString("nome"), rs.getString("cfop"),
                rs.getString("cst_icms"), rs.getString("csosn"), rs.getBigDecimal("aliquota_icms"),
                rs.getBigDecimal("reducao_base_icms"), rs.getString("cst_pis"), rs.getBigDecimal("aliquota_pis"),
                rs.getString("cst_cofins"), rs.getBigDecimal("aliquota_cofins"), rs.getString("codigo_beneficio"),
                rs.getString("cst_ibs_cbs"), rs.getString("cclass_trib"), rs.getBoolean("ativo"),
                rs.getLong("produtos"));
    }
}
