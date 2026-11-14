package br.com.varesync.dao;

import br.com.varesync.model.AlvoPerfil;
import br.com.varesync.model.ProdutoFiscal;
import br.com.varesync.model.CodigoCest;
import br.com.varesync.model.CodigoNcm;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dados fiscais do produto (NCM, CEST, origem e perfil tributário), aplicação do perfil em lote e as tabelas de apoio
 * NCM e CEST (F19.2 e F19.3).
 */
public class FiscalDAO {

    private static final int LOTE = 1000;

    /**
     * Filtro da aba Produtos do cadastro fiscal.
     *
     * @param termoNome     nome normalizado (contém); nulo ou vazio = qualquer
     * @param digitos       código interno ou código de barras exato (no lugar do nome)
     * @param perfilId      só os produtos deste perfil; nulo = qualquer
     * @param soPendentes   só os ativos sem NCM ou sem perfil
     */
    public record Filtro(String termoNome, String digitos, Long categoriaId, Long perfilId, boolean soPendentes,
                         boolean incluirInativos) {
    }

    private static final String COLUNAS = """
            SELECT p.id, p.nome, c.nome AS categoria_nome, p.ativo, p.ncm, p.cest, p.origem,
                   p.perfil_tributario_id, t.nome AS perfil_nome
              FROM produto p
              LEFT JOIN categoria c         ON c.id = p.categoria_id
              LEFT JOIN perfil_tributario t ON t.id = p.perfil_tributario_id
            """;

    public List<ProdutoFiscal> listar(Connection con, Filtro filtro) throws SQLException {
        StringBuilder sql = new StringBuilder(COLUNAS).append(" WHERE TRUE");
        List<Object> parametros = new ArrayList<>();
        if (filtro.digitos() != null) {
            sql.append("""
                     AND (p.id = ? OR EXISTS (SELECT 1 FROM produto_codigo_barras b
                                               WHERE b.produto_id = p.id AND b.codigo = ?))
                    """);
            parametros.add(filtro.digitos().length() > 18 ? -1L : Long.parseLong(filtro.digitos()));
            parametros.add(filtro.digitos());
        } else if (filtro.termoNome() != null && !filtro.termoNome().isEmpty()) {
            sql.append(" AND p.nome_busca LIKE ? ESCAPE '\\'");
            parametros.add("%" + Jdbc.escaparLike(filtro.termoNome()) + "%");
        }
        if (filtro.categoriaId() != null) {
            sql.append(" AND p.categoria_id = ?");
            parametros.add(filtro.categoriaId());
        }
        if (filtro.perfilId() != null) {
            sql.append(" AND p.perfil_tributario_id = ?");
            parametros.add(filtro.perfilId());
        }
        if (filtro.soPendentes()) {
            sql.append(" AND p.ativo AND (p.ncm IS NULL OR p.perfil_tributario_id IS NULL)");
        } else if (!filtro.incluirInativos()) {
            sql.append(" AND p.ativo");
        }
        sql.append(" ORDER BY p.nome_busca, p.id");
        List<ProdutoFiscal> produtos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < parametros.size(); i++) {
                ps.setObject(i + 1, parametros.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    produtos.add(ler(rs));
                }
            }
        }
        return produtos;
    }

    /** Dados fiscais do produto, travando a linha dele. */
    public Optional<ProdutoFiscal> travar(Connection con, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT id FROM produto WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
            }
        }
        return buscar(con, produtoId);
    }

    public Optional<ProdutoFiscal> buscar(Connection con, long produtoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE p.id = ?")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    public void gravar(Connection con, long produtoId, String ncm, String cest, Integer origem, Long perfilId,
                       long usuarioId, OffsetDateTime agora) throws SQLException {
        String sql = """
                UPDATE produto
                   SET ncm = ?, cest = ?, origem = ?, perfil_tributario_id = ?, atualizado_por = ?, atualizado_em = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            texto(ps, 1, ncm);
            texto(ps, 2, cest);
            if (origem == null) {
                ps.setNull(3, Types.SMALLINT);
            } else {
                ps.setInt(3, origem);
            }
            Jdbc.definirLong(ps, 4, perfilId);
            ps.setLong(5, usuarioId);
            ps.setObject(6, agora);
            ps.setLong(7, produtoId);
            ps.executeUpdate();
        }
    }

    // ----- Aplicação em lote -----

    /** Produtos do alvo (já normalizado: NCM só com dígitos): quantos são e quantos já têm o perfil. */
    public record Contagem(long produtos, long jaComOPerfil) {
    }

    public Contagem contarAlvo(Connection con, AlvoPerfil alvo, long perfilId) throws SQLException {
        String sql = "SELECT count(*), count(*) FILTER (WHERE p.perfil_tributario_id = ?) FROM produto p WHERE "
                + condicao(alvo);
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, perfilId);
            definirAlvo(con, ps, 2, alvo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Contagem(rs.getLong(1), rs.getLong(2));
            }
        }
    }

    /** Nomes dos produtos do alvo que mudam de perfil (para a auditoria), até {@code limite}. */
    public List<String> nomesQueMudam(Connection con, AlvoPerfil alvo, long perfilId, int limite) throws SQLException {
        String sql = "SELECT p.nome FROM produto p WHERE " + condicao(alvo)
                + " AND p.perfil_tributario_id IS DISTINCT FROM ? ORDER BY p.nome_busca, p.id LIMIT ?";
        List<String> nomes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definirAlvo(con, ps, 1, alvo);
            ps.setLong(i++, perfilId);
            ps.setInt(i, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nomes.add(rs.getString(1));
                }
            }
        }
        return nomes;
    }

    /** Põe o perfil nos produtos do alvo que ainda não o têm. @return quantos mudaram */
    public int aplicarPerfil(Connection con, AlvoPerfil alvo, long perfilId, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        String sql = "UPDATE produto p SET perfil_tributario_id = ?, atualizado_por = ?, atualizado_em = ? WHERE "
                + condicao(alvo) + " AND p.perfil_tributario_id IS DISTINCT FROM ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, perfilId);
            ps.setLong(2, usuarioId);
            ps.setObject(3, agora);
            int i = definirAlvo(con, ps, 4, alvo);
            ps.setLong(i, perfilId);
            return ps.executeUpdate();
        }
    }

    private static String condicao(AlvoPerfil alvo) {
        return switch (alvo.tipo()) {
            case SELECAO -> "p.id = ANY (?)";
            case CATEGORIA -> "p.categoria_id = ?";
            case NCM -> "p.ncm LIKE ?";
        };
    }

    private static int definirAlvo(Connection con, PreparedStatement ps, int indice, AlvoPerfil alvo)
            throws SQLException {
        switch (alvo.tipo()) {
            case SELECAO -> {
                Array ids = con.createArrayOf("bigint", alvo.produtos().toArray());
                ps.setArray(indice, ids);
            }
            case CATEGORIA -> ps.setLong(indice, alvo.categoriaId());
            case NCM -> ps.setString(indice, alvo.ncm() + "%");
        }
        return indice + 1;
    }

    public Optional<String> nomeCategoria(Connection con, long categoriaId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT nome FROM categoria WHERE id = ?")) {
            ps.setLong(1, categoriaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    // ----- Tabelas NCM e CEST -----

    public long contarNcm(Connection con) throws SQLException {
        return contar(con, "SELECT count(*) FROM ncm");
    }

    public long contarCest(Connection con) throws SQLException {
        return contar(con, "SELECT count(DISTINCT cest) FROM cest");
    }

    public boolean ncmExiste(Connection con, String codigo) throws SQLException {
        return existe(con, "SELECT 1 FROM ncm WHERE codigo = ?", codigo);
    }

    public boolean cestExiste(Connection con, String cest) throws SQLException {
        return existe(con, "SELECT 1 FROM cest WHERE cest = ?", cest);
    }

    /** Descrição do NCM na tabela importada, se houver. */
    public Optional<String> descricaoNcm(Connection con, String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT descricao FROM ncm WHERE codigo = ?")) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** Troca a tabela NCM inteira pela lida. */
    public void substituirNcm(Connection con, List<CodigoNcm> codigos) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM ncm")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO ncm (codigo, descricao) VALUES (?, ?)")) {
            int n = 0;
            for (CodigoNcm c : codigos) {
                ps.setString(1, c.codigo());
                ps.setString(2, c.descricao());
                ps.addBatch();
                if (++n % LOTE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    /** Troca a tabela CEST inteira pela lida. */
    public void substituirCest(Connection con, List<CodigoCest> linhas) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM cest")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO cest (cest, ncm, descricao) VALUES (?, ?, ?)")) {
            int n = 0;
            for (CodigoCest c : linhas) {
                ps.setString(1, c.cest());
                ps.setString(2, c.ncm());
                ps.setString(3, c.descricao());
                ps.addBatch();
                if (++n % LOTE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    // ----- Tabela IBPT (F22.3) -----

    /** Situação da tabela IBPT importada: linhas, versão, vigência e fonte (vazia sem tabela). */
    public record SituacaoIbpt(long linhas, String versao, java.time.LocalDate inicio, java.time.LocalDate fim,
                               String fonte) {
    }

    public SituacaoIbpt situacaoIbpt(Connection con) throws SQLException {
        String sql = """
                SELECT count(*), min(vigencia_inicio), max(vigencia_fim),
                       (SELECT versao FROM ibpt ORDER BY codigo, ex LIMIT 1),
                       (SELECT fonte FROM ibpt ORDER BY codigo, ex LIMIT 1)
                  FROM ibpt
                """;
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new SituacaoIbpt(rs.getLong(1), rs.getString(4), rs.getObject(2, java.time.LocalDate.class),
                    rs.getObject(3, java.time.LocalDate.class), rs.getString(5));
        }
    }

    /** Troca a tabela IBPT inteira pelas linhas lidas. */
    public void substituirIbpt(Connection con, List<br.com.varesync.model.LinhaIbpt> linhas) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM ibpt")) {
            ps.executeUpdate();
        }
        String sql = """
                INSERT INTO ibpt (codigo, ex, descricao, aliquota_nacional_federal, aliquota_importados_federal,
                                  aliquota_estadual, aliquota_municipal, vigencia_inicio, vigencia_fim, chave, versao,
                                  fonte)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int n = 0;
            for (br.com.varesync.model.LinhaIbpt l : linhas) {
                ps.setString(1, l.codigo());
                ps.setString(2, l.ex());
                ps.setString(3, l.descricao());
                ps.setBigDecimal(4, l.nacionalFederal());
                ps.setBigDecimal(5, l.importadosFederal());
                ps.setBigDecimal(6, l.estadual());
                ps.setBigDecimal(7, l.municipal());
                ps.setObject(8, l.vigenciaInicio());
                ps.setObject(9, l.vigenciaFim());
                ps.setString(10, l.chave());
                ps.setString(11, l.versao());
                ps.setString(12, l.fonte());
                ps.addBatch();
                if (++n % LOTE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private static long contar(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean existe(Connection con, String sql, String valor) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
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

    private static ProdutoFiscal ler(ResultSet rs) throws SQLException {
        int origem = rs.getInt("origem");
        Integer origemOuNulo = rs.wasNull() ? null : origem;
        return new ProdutoFiscal(rs.getLong("id"), rs.getString("nome"), rs.getString("categoria_nome"),
                rs.getBoolean("ativo"), rs.getString("ncm"), rs.getString("cest"), origemOuNulo,
                Jdbc.lerLong(rs, "perfil_tributario_id"), rs.getString("perfil_nome"));
    }
}
