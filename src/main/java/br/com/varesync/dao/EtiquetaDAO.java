package br.com.varesync.dao;

import br.com.varesync.model.Etiqueta;
import br.com.varesync.model.OrigemEtiqueta;
import br.com.varesync.model.TipoCodigoBarras;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Etiquetas de gôndola (F18.1): os produtos de cada critério de seleção, com o preço vigente na data (a regra do
 * PDV, {@link PromocaoDAO#VIGENTE_DO_PRODUTO}: promoção ativa cujo período contém a data e menor que o preço normal, a
 * de menor preço), a faixa de atacado que sai na etiqueta (F20.3: a de menor quantidade, só se o preço dela é menor
 * que o da etiqueta) e a última impressão ({@code etiqueta_impressao}); e o registro de cada impressão. Só produtos
 * ativos.
 */
public class EtiquetaDAO {

    /** O primeiro parâmetro é a data da promoção vigente (duas vezes). */
    private static final String BASE = """
            SELECT p.id, p.nome, p.unidade, p.preco_venda, p.plu, p.conteudo_quantidade, p.conteudo_unidade,
                   pe.quantidade AS saldo,
                   (SELECT b.codigo FROM produto_codigo_barras b WHERE b.produto_id = p.id AND b.principal) AS barras,
                   pr.id AS promocao_id, pr.preco_promocional, pr.data_fim,
                   CASE WHEN fx.preco < coalesce(pr.preco_promocional, p.preco_venda) THEN fx.quantidade_minima END
                       AS atacado_quantidade,
                   CASE WHEN fx.preco < coalesce(pr.preco_promocional, p.preco_venda) THEN fx.preco END
                       AS atacado_preco,
                   ue.preco AS ultimo_preco, ue.promocao_id AS ultima_promocao_id, ue.criado_em AS ultima_em,
                   ue.atacado_quantidade AS ultimo_atacado_quantidade, ue.atacado_preco AS ultimo_atacado_preco
              FROM produto p
              JOIN produto_estoque pe ON pe.produto_id = p.id
            """ + PromocaoDAO.VIGENTE_DO_PRODUTO + """
              LEFT JOIN LATERAL (SELECT quantidade_minima, preco
                                   FROM produto_preco_quantidade
                                  WHERE produto_id = p.id
                                  ORDER BY quantidade_minima
                                  LIMIT 1) fx ON TRUE
              LEFT JOIN LATERAL (SELECT preco, promocao_id, criado_em, atacado_quantidade, atacado_preco
                                   FROM etiqueta_impressao
                                  WHERE produto_id = p.id
                                  ORDER BY criado_em DESC, id DESC
                                  LIMIT 1) ue ON TRUE
             WHERE p.ativo
            """;

    /**
     * Pendente: com estoque, e nunca impressa ou com preço, promoção ou faixa de atacado (F20.3: criada, mudada ou
     * tirada) diferente da última etiqueta.
     */
    private static final String PENDENTE = """
            pe.quantidade > 0
            AND (ue.criado_em IS NULL
                 OR ue.preco <> coalesce(pr.preco_promocional, p.preco_venda)
                 OR ue.promocao_id IS DISTINCT FROM pr.id
                 OR ue.atacado_quantidade IS DISTINCT FROM
                    (CASE WHEN fx.preco < coalesce(pr.preco_promocional, p.preco_venda) THEN fx.quantidade_minima END)
                 OR ue.atacado_preco IS DISTINCT FROM
                    (CASE WHEN fx.preco < coalesce(pr.preco_promocional, p.preco_venda) THEN fx.preco END))
            """;

    private static final String ORDEM = " ORDER BY p.nome, p.id";

    public List<Etiqueta> pendentes(Connection con, LocalDate data) throws SQLException {
        return listar(con, BASE + " AND " + PENDENTE + ORDEM, data);
    }

    public int contarPendentes(Connection con, LocalDate data) throws SQLException {
        String sql = "SELECT count(*) FROM (" + BASE + " AND " + PENDENTE + ") t";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, data);
            ps.setObject(2, data);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Preço de venda alterado a partir de {@code desde} (pelo histórico, como o R15). */
    public List<Etiqueta> alteradosDesde(Connection con, LocalDate data, OffsetDateTime desde) throws SQLException {
        String sql = BASE + """
                 AND EXISTS (SELECT 1 FROM produto_historico_preco h
                              WHERE h.produto_id = p.id AND h.criado_em >= ?
                                AND h.preco_anterior IS NOT NULL AND h.preco_anterior <> h.preco_novo)
                """ + ORDEM;
        return listar(con, sql, data, desde);
    }

    public List<Etiqueta> daEntrada(Connection con, LocalDate data, long entradaId) throws SQLException {
        String sql = BASE + " AND p.id IN (SELECT i.produto_id FROM entrada_mercadoria_item i WHERE i.entrada_id = ?)"
                + ORDEM;
        return listar(con, sql, data, entradaId);
    }

    public List<Etiqueta> daCategoria(Connection con, LocalDate data, long categoriaId) throws SQLException {
        return listar(con, BASE + " AND p.categoria_id = ?" + ORDEM, data, categoriaId);
    }

    /** Os produtos ativos destes ids, na ordem do nome. */
    public List<Etiqueta> produtos(Connection con, LocalDate data, Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return List.of();
        }
        return listar(con, BASE + " AND p.id = ANY (?)" + ORDEM, data, (Object) ids.toArray(Long[]::new));
    }

    public boolean entradaExiste(Connection con, long entradaId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM entrada_mercadoria WHERE id = ?")) {
            ps.setLong(1, entradaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Registra a etiqueta que saiu: o preço, a promoção e a faixa de atacado dela (F20.3). */
    public void registrar(Connection con, Etiqueta e, OrigemEtiqueta origem, long usuarioId, OffsetDateTime agora)
            throws SQLException {
        String sql = """
                INSERT INTO etiqueta_impressao (produto_id, preco, promocao_id, origem, usuario_id, criado_em,
                                                atacado_quantidade, atacado_preco)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, e.produtoId());
            ps.setBigDecimal(2, e.preco());
            Jdbc.definirLong(ps, 3, e.promocaoId());
            ps.setString(4, origem.name());
            ps.setLong(5, usuarioId);
            ps.setObject(6, agora);
            ps.setBigDecimal(7, e.atacadoQuantidade());
            ps.setBigDecimal(8, e.atacadoPreco());
            ps.executeUpdate();
        }
    }

    private static List<Etiqueta> listar(Connection con, String sql, LocalDate data, Object... extras)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, data);
            ps.setObject(2, data);
            for (int i = 0; i < extras.length; i++) {
                if (extras[i] instanceof Long[] ids) {
                    ps.setArray(3 + i, con.createArrayOf("bigint", ids));
                } else {
                    ps.setObject(3 + i, extras[i]);
                }
            }
            List<Etiqueta> etiquetas = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    etiquetas.add(ler(rs, data));
                }
            }
            return etiquetas;
        }
    }

    private static Etiqueta ler(ResultSet rs, LocalDate data) throws SQLException {
        BigDecimal precoVenda = rs.getBigDecimal("preco_venda");
        Long promocaoId = Jdbc.lerLong(rs, "promocao_id");
        BigDecimal preco = promocaoId == null ? precoVenda : rs.getBigDecimal("preco_promocional");
        int plu = rs.getInt("plu");
        Integer pluOuNulo = rs.wasNull() ? null : plu;
        String barras = rs.getString("barras");
        String desenhado = pluOuNulo == null && TipoCodigoBarras.de(barras).isPresent() ? barras : null;
        OffsetDateTime ultima = rs.getObject("ultima_em", OffsetDateTime.class);
        BigDecimal ultimoPreco = rs.getBigDecimal("ultimo_preco");
        Long ultimaPromocao = Jdbc.lerLong(rs, "ultima_promocao_id");
        BigDecimal atacadoQuantidade = rs.getBigDecimal("atacado_quantidade");
        BigDecimal atacadoPreco = rs.getBigDecimal("atacado_preco");
        boolean pendente = rs.getBigDecimal("saldo").signum() > 0 && (ultima == null
                || ultimoPreco.compareTo(preco) != 0 || !java.util.Objects.equals(ultimaPromocao, promocaoId)
                || !mesmoValor(rs.getBigDecimal("ultimo_atacado_quantidade"), atacadoQuantidade)
                || !mesmoValor(rs.getBigDecimal("ultimo_atacado_preco"), atacadoPreco));
        return new Etiqueta(rs.getLong("id"), rs.getString("nome"), rs.getString("unidade"), preco,
                promocaoId == null ? null : precoVenda, promocaoId,
                promocaoId == null ? null : rs.getObject("data_fim", LocalDate.class), desenhado, pluOuNulo,
                ProdutoDAO.lerConteudo(rs), data, ultima, pendente, 1, atacadoQuantidade, atacadoPreco);
    }

    /** Os dois nulos ou o mesmo número (6 = 6,000). */
    private static boolean mesmoValor(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
