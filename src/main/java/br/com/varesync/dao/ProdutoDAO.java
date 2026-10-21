package br.com.varesync.dao;

import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.HistoricoPreco;
import br.com.varesync.model.Produto;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.SituacaoProduto;
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

/**
 * Produtos ({@code produto}), seus códigos de barras e o histórico de preço.
 * Não escreve {@code nome_busca} (gerada) nem {@code produto_estoque} (trigger). Os horários vêm do Clock (V3).
 */
public class ProdutoDAO {

    /** Valores gravados no INSERT/UPDATE, já validados pelo service. */
    public record Gravacao(String nome, Long categoriaId, String unidade, BigDecimal precoVenda, BigDecimal custo,
                           BigDecimal estoqueMinimo, Integer plu) {
    }

    /** Produto que já usa um código de barras. */
    public record Dono(long id, String nome) {
    }

    /** Onde o produto já foi usado; qualquer vínculo impede a exclusão (6.3). */
    public record Vinculos(boolean vendas, boolean movimentos, boolean inventarios, boolean entradas,
                           boolean promocoes) {
        public boolean algum() {
            return vendas || movimentos || inventarios || entradas || promocoes;
        }
    }

    /** Filtros da busca. {@code termoNome} já normalizado (minúsculo e sem acento); {@code digitos} só dígitos. */
    public record Filtro(String termoNome, String digitos, Long categoriaId, SituacaoProduto situacao) {
    }

    private static final String COLUNAS = """
            SELECT p.id, p.nome, p.categoria_id, c.nome AS categoria_nome, p.unidade, u.fracionada,
                   p.preco_venda, p.custo, p.estoque_minimo, p.plu, p.ativo, pe.quantidade AS estoque,
                   p.atualizado_em, us.nome AS atualizado_por_nome
              FROM produto p
              JOIN unidade_medida u   ON u.sigla = p.unidade
              JOIN produto_estoque pe ON pe.produto_id = p.id
              JOIN usuario us         ON us.id = p.atualizado_por
              LEFT JOIN categoria c   ON c.id = p.categoria_id
            """;

    public long inserir(Connection con, Gravacao g, long usuarioId, OffsetDateTime agora) throws SQLException {
        String sql = """
                INSERT INTO produto (nome, categoria_id, unidade, preco_venda, custo, estoque_minimo, plu,
                                     criado_em, criado_por, atualizado_em, atualizado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definirGravacao(ps, g);
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

    /** Sempre grava {@code atualizado_por} e {@code atualizado_em} (pelo Clock): o trigger de histórico usa os dois (5.3). */
    public void alterar(Connection con, long id, Gravacao g, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        String sql = """
                UPDATE produto
                   SET nome = ?, categoria_id = ?, unidade = ?, preco_venda = ?, custo = ?, estoque_minimo = ?,
                       plu = ?, atualizado_por = ?, atualizado_em = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int i = definirGravacao(ps, g);
            ps.setLong(i++, usuarioId);
            ps.setObject(i++, agora);
            ps.setLong(i, id);
            ps.executeUpdate();
        }
    }

    public void alterarSituacao(Connection con, long id, boolean ativo, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        String sql = """
                UPDATE produto
                   SET ativo = ?, atualizado_por = ?, atualizado_em = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBoolean(1, ativo);
            ps.setLong(2, usuarioId);
            ps.setObject(3, agora);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    /** Nome, custo médio e preço do produto, lidos com a linha travada (confirmação da entrada de mercadoria). */
    public record CustoTravado(String nome, BigDecimal custo, BigDecimal precoVenda) {
    }

    public Optional<CustoTravado> travarCusto(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT nome, custo, preco_venda FROM produto WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new CustoTravado(rs.getString(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3))) : Optional.empty();
            }
        }
    }

    /**
     * Custo médio e último custo da entrada de mercadoria (6.10). Grava {@code atualizado_por} e {@code atualizado_em}
     * (pelo Clock): o trigger registra a mudança de custo no histórico com essa data e esse usuário.
     */
    public void atualizarCusto(Connection con, long id, BigDecimal custo, BigDecimal ultimoCusto, long usuarioId,
                               OffsetDateTime agora) throws SQLException {
        String sql = """
                UPDATE produto
                   SET custo = ?, ultimo_custo = ?, atualizado_por = ?, atualizado_em = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, custo);
            ps.setBigDecimal(2, ultimoCusto);
            ps.setLong(3, usuarioId);
            ps.setObject(4, agora);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** Apaga o produto; códigos, histórico e o saldo zerado saem em cascata. */
    public void excluir(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM produto WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Produto> buscar(Connection con, long id) throws SQLException {
        return buscar(con, id, false);
    }

    /** Busca e trava a linha do produto até o fim da transação. */
    public Optional<Produto> buscarParaAtualizar(Connection con, long id) throws SQLException {
        return buscar(con, id, true);
    }

    private Optional<Produto> buscar(Connection con, long id, boolean travar) throws SQLException {
        String sql = COLUNAS + " WHERE p.id = ?" + (travar ? " FOR UPDATE OF p" : "");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(lerProduto(rs, listarCodigos(con, id)));
            }
        }
    }

    /** A unidade do produto aceita quantidade fracionada? Vazio se o produto não existe. */
    public Optional<Boolean> unidadeFracionada(Connection con, long id) throws SQLException {
        String sql = """
                SELECT u.fracionada
                  FROM produto p
                  JOIN unidade_medida u ON u.sigla = p.unidade
                 WHERE p.id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBoolean(1)) : Optional.empty();
            }
        }
    }

    private static final String SELECT_VENDA = """
            SELECT p.id, p.nome, p.unidade, u.fracionada, p.preco_venda, p.custo, p.ativo, pe.quantidade AS saldo,
                   (SELECT b.codigo FROM produto_codigo_barras b
                     WHERE b.produto_id = p.id AND b.principal) AS codigo_principal
              FROM produto p
              JOIN unidade_medida u   ON u.sigla = p.unidade
              JOIN produto_estoque pe ON pe.produto_id = p.id
            """;

    /** Produto como o PDV o usa (preço, custo atual, situação, saldo), pelo código interno. */
    public Optional<ProdutoVenda> buscarParaVenda(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_VENDA + " WHERE p.id = ?")) {
            ps.setLong(1, id);
            return produtoVenda(ps, null);
        }
    }

    /** Produto como o PDV o usa, pelo código de barras exato; o código lido fica como código do item. */
    public Optional<ProdutoVenda> buscarParaVendaPorCodigo(Connection con, String codigoBarras) throws SQLException {
        String sql = SELECT_VENDA + """
                 WHERE p.id = (SELECT b.produto_id FROM produto_codigo_barras b WHERE b.codigo = ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, codigoBarras);
            return produtoVenda(ps, codigoBarras);
        }
    }

    private static Optional<ProdutoVenda> produtoVenda(PreparedStatement ps, String codigoLido) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            String principal = rs.getString("codigo_principal");
            String codigo = codigoLido != null ? codigoLido
                    : principal != null ? principal : String.valueOf(rs.getLong("id"));
            return Optional.of(new ProdutoVenda(rs.getLong("id"), codigo, rs.getString("nome"),
                    rs.getString("unidade"), rs.getBoolean("fracionada"), rs.getBigDecimal("preco_venda"),
                    rs.getBigDecimal("custo"), rs.getBoolean("ativo"), rs.getBigDecimal("saldo")));
        }
    }

    /** Principal primeiro, depois na ordem de cadastro. */
    public List<CodigoBarras> listarCodigos(Connection con, long produtoId) throws SQLException {
        String sql = """
                SELECT codigo, principal
                  FROM produto_codigo_barras
                 WHERE produto_id = ?
                 ORDER BY principal DESC, id
                """;
        List<CodigoBarras> codigos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codigos.add(new CodigoBarras(rs.getString("codigo"), rs.getBoolean("principal")));
                }
            }
        }
        return codigos;
    }

    /** Substitui os códigos de barras do produto. */
    public void definirCodigos(Connection con, long produtoId, List<CodigoBarras> codigos) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM produto_codigo_barras WHERE produto_id = ?")) {
            ps.setLong(1, produtoId);
            ps.executeUpdate();
        }
        if (codigos.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO produto_codigo_barras (produto_id, codigo, principal)
                VALUES (?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (CodigoBarras codigo : codigos) {
                ps.setLong(1, produtoId);
                ps.setString(2, codigo.codigo());
                ps.setBoolean(3, codigo.principal());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Produto (diferente de {@code ignorarId}) que já usa o código de barras. */
    public Optional<Dono> donoDoCodigo(Connection con, String codigo, Long ignorarId) throws SQLException {
        String sql = """
                SELECT p.id, p.nome
                  FROM produto_codigo_barras b
                  JOIN produto p ON p.id = b.produto_id
                 WHERE b.codigo = ?
                   AND (CAST(? AS BIGINT) IS NULL OR p.id <> ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, codigo);
            definirLong(ps, 2, ignorarId);
            definirLong(ps, 3, ignorarId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Dono(rs.getLong(1), rs.getString(2))) : Optional.empty();
            }
        }
    }

    /** Produto (diferente de {@code ignorarId}) que já usa o PLU. */
    public Optional<Dono> donoDoPlu(Connection con, int plu, Long ignorarId) throws SQLException {
        String sql = """
                SELECT id, nome
                  FROM produto
                 WHERE plu = ?
                   AND (CAST(? AS BIGINT) IS NULL OR id <> ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, plu);
            definirLong(ps, 2, ignorarId);
            definirLong(ps, 3, ignorarId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Dono(rs.getLong(1), rs.getString(2))) : Optional.empty();
            }
        }
    }

    /** Outros produtos com o mesmo nome, sem diferenciar acento nem maiúsculas ({@code nomeBusca} normalizado). */
    public List<Dono> mesmoNome(Connection con, String nomeBusca, Long ignorarId) throws SQLException {
        String sql = """
                SELECT id, nome
                  FROM produto
                 WHERE nome_busca = ?
                   AND (CAST(? AS BIGINT) IS NULL OR id <> ?)
                 ORDER BY id
                """;
        List<Dono> produtos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, nomeBusca);
            definirLong(ps, 2, ignorarId);
            definirLong(ps, 3, ignorarId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    produtos.add(new Dono(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        return produtos;
    }

    public Vinculos vinculos(Connection con, long id) throws SQLException {
        String sql = """
                SELECT EXISTS (SELECT 1 FROM venda_item WHERE produto_id = ?),
                       EXISTS (SELECT 1 FROM movimento_estoque WHERE produto_id = ?),
                       EXISTS (SELECT 1 FROM inventario_item WHERE produto_id = ?),
                       EXISTS (SELECT 1 FROM entrada_mercadoria_item WHERE produto_id = ?),
                       EXISTS (SELECT 1 FROM promocao WHERE produto_id = ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) {
                ps.setLong(i, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Vinculos(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getBoolean(5));
            }
        }
    }

    /**
     * Busca da tela de produtos: nome contém o termo (sem acento nem maiúsculas) ou, se só dígitos,
     * código interno ou código de barras exato.
     */
    public List<ProdutoResumo> pesquisar(Connection con, Filtro filtro) throws SQLException {
        StringBuilder sql = new StringBuilder(COLUNAS).append(" WHERE TRUE");
        List<Object> parametros = new ArrayList<>();
        if (filtro.digitos() != null) {
            sql.append("""
                     AND (p.id = ? OR EXISTS (SELECT 1 FROM produto_codigo_barras b
                                               WHERE b.produto_id = p.id AND b.codigo = ?))
                    """);
            parametros.add(idOuNenhum(filtro.digitos()));
            parametros.add(filtro.digitos());
        } else if (filtro.termoNome() != null && !filtro.termoNome().isEmpty()) {
            sql.append(" AND p.nome_busca LIKE ? ESCAPE '\\'");
            parametros.add("%" + escaparLike(filtro.termoNome()) + "%");
        }
        if (filtro.categoriaId() != null) {
            sql.append(" AND p.categoria_id = ?");
            parametros.add(filtro.categoriaId());
        }
        switch (filtro.situacao()) {
            case ATIVOS -> sql.append(" AND p.ativo");
            case INATIVOS -> sql.append(" AND NOT p.ativo");
            case TODOS -> { }
            case ABAIXO_DO_CUSTO -> sql.append(" AND p.ativo AND p.preco_venda < p.custo");
        }
        sql.append(" ORDER BY p.nome_busca, p.id");

        List<ProdutoResumo> produtos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < parametros.size(); i++) {
                ps.setObject(i + 1, parametros.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    produtos.add(new ProdutoResumo(
                            rs.getLong("id"),
                            rs.getString("nome"),
                            rs.getString("categoria_nome"),
                            rs.getString("unidade"),
                            rs.getBoolean("fracionada"),
                            rs.getBigDecimal("preco_venda"),
                            rs.getBigDecimal("estoque"),
                            rs.getBoolean("ativo"),
                            rs.getObject("atualizado_em", OffsetDateTime.class),
                            rs.getString("atualizado_por_nome")));
                }
            }
        }
        return produtos;
    }

    /** Mais recente primeiro. */
    public List<HistoricoPreco> historico(Connection con, long produtoId) throws SQLException {
        String sql = """
                SELECT h.criado_em, u.nome AS usuario_nome, h.preco_anterior, h.preco_novo,
                       h.custo_anterior, h.custo_novo
                  FROM produto_historico_preco h
                  JOIN usuario u ON u.id = h.usuario_id
                 WHERE h.produto_id = ?
                 ORDER BY h.criado_em DESC, h.id DESC
                """;
        List<HistoricoPreco> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new HistoricoPreco(
                            rs.getObject("criado_em", OffsetDateTime.class),
                            rs.getString("usuario_nome"),
                            rs.getBigDecimal("preco_anterior"),
                            rs.getBigDecimal("preco_novo"),
                            rs.getBigDecimal("custo_anterior"),
                            rs.getBigDecimal("custo_novo")));
                }
            }
        }
        return linhas;
    }

    /** "%", "_" e "\" digitados valem como texto, não como curinga do LIKE. */
    static String escaparLike(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Código interno, se os dígitos cabem num BIGINT; senão -1 (nenhum produto). */
    public static long idOuNenhum(String digitos) {
        if (digitos.length() > 18) {
            return -1;
        }
        return Long.parseLong(digitos);
    }

    private static int definirGravacao(PreparedStatement ps, Gravacao g) throws SQLException {
        ps.setString(1, g.nome());
        definirLong(ps, 2, g.categoriaId());
        ps.setString(3, g.unidade());
        ps.setBigDecimal(4, g.precoVenda());
        ps.setBigDecimal(5, g.custo());
        ps.setBigDecimal(6, g.estoqueMinimo());
        if (g.plu() == null) {
            ps.setNull(7, Types.INTEGER);
        } else {
            ps.setInt(7, g.plu());
        }
        return 8;
    }

    private static Produto lerProduto(ResultSet rs, List<CodigoBarras> codigos) throws SQLException {
        long categoriaId = rs.getLong("categoria_id");
        Long categoria = rs.wasNull() ? null : categoriaId;
        int plu = rs.getInt("plu");
        Integer pluOuNulo = rs.wasNull() ? null : plu;
        return new Produto(
                rs.getLong("id"),
                rs.getString("nome"),
                categoria,
                rs.getString("categoria_nome"),
                rs.getString("unidade"),
                rs.getBoolean("fracionada"),
                rs.getBigDecimal("preco_venda"),
                rs.getBigDecimal("custo"),
                rs.getBigDecimal("estoque_minimo"),
                pluOuNulo,
                rs.getBoolean("ativo"),
                rs.getBigDecimal("estoque"),
                rs.getObject("atualizado_em", OffsetDateTime.class),
                rs.getString("atualizado_por_nome"),
                codigos);
    }

    private static void definirLong(PreparedStatement ps, int indice, Long valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.BIGINT);
        } else {
            ps.setLong(indice, valor);
        }
    }
}
