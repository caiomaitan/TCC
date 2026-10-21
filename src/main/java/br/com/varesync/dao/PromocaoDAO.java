package br.com.varesync.dao;

import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.FiltroPromocao;
import br.com.varesync.model.Promocao;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Promoções ({@code promocao}, 6.8). A regra de não sobrepor promoções ativas fica no service. */
public class PromocaoDAO {

    /** Preço promocional que vale numa data. */
    public record Vigente(long id, BigDecimal preco) {
    }

    private static final String COLUNAS = """
            SELECT pr.id, pr.produto_id, p.nome AS produto_nome, p.unidade, p.preco_venda, p.custo,
                   pr.preco_promocional, pr.data_inicio, pr.data_fim, pr.ativa, u.nome AS criado_por, pr.criado_em
              FROM promocao pr
              JOIN produto p ON p.id = pr.produto_id
              JOIN usuario u ON u.id = pr.criado_por
            """;

    /**
     * @param nomeBusca termo já normalizado (sem acento, minúsculo); nulo = todos os produtos
     * @param produtoId nulo = todos
     */
    public List<Promocao> listar(Connection con, FiltroPromocao filtro, LocalDate hoje, String nomeBusca,
                                 Long produtoId) throws SQLException {
        String condicao = switch (filtro) {
            case VIGENTES -> "pr.ativa AND pr.data_inicio <= ? AND pr.data_fim >= ?";
            case FUTURAS -> "pr.ativa AND pr.data_inicio > ? AND CAST(? AS DATE) IS NOT NULL";
            case ENCERRADAS -> "(pr.data_fim < ? OR NOT pr.ativa) AND CAST(? AS DATE) IS NOT NULL";
            case TODAS -> "CAST(? AS DATE) IS NOT NULL AND CAST(? AS DATE) IS NOT NULL";
        };
        String sql = COLUNAS + " WHERE " + condicao + """

                   AND (CAST(? AS TEXT) IS NULL OR p.nome_busca LIKE ? ESCAPE '\\')
                   AND (CAST(? AS BIGINT) IS NULL OR pr.produto_id = ?)
                 ORDER BY pr.data_inicio DESC, p.nome_busca, pr.id DESC
                """;
        List<Promocao> promocoes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, hoje);
            ps.setObject(2, hoje);
            String like = nomeBusca == null ? null : "%" + escaparLike(nomeBusca) + "%";
            ps.setString(3, nomeBusca);
            ps.setString(4, like);
            definirLong(ps, 5, produtoId);
            definirLong(ps, 6, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    promocoes.add(ler(rs));
                }
            }
        }
        return promocoes;
    }

    public Optional<Promocao> buscar(Connection con, long id, boolean travar) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE pr.id = ?"
                + (travar ? " FOR UPDATE OF pr" : ""))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Promoções ativas do produto cujo período cruza [inicio, fim], fora {@code ignorarId}. */
    public List<Promocao> sobrepostas(Connection con, long produtoId, LocalDate inicio, LocalDate fim, Long ignorarId)
            throws SQLException {
        String sql = COLUNAS + """
                 WHERE pr.produto_id = ?
                   AND pr.ativa
                   AND pr.data_inicio <= ?
                   AND pr.data_fim >= ?
                   AND (CAST(? AS BIGINT) IS NULL OR pr.id <> ?)
                 ORDER BY pr.data_inicio
                """;
        List<Promocao> promocoes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setObject(2, fim);
            ps.setObject(3, inicio);
            definirLong(ps, 4, ignorarId);
            definirLong(ps, 5, ignorarId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    promocoes.add(ler(rs));
                }
            }
        }
        return promocoes;
    }

    /**
     * Promoção ativa cujo período contém a data (6.8). Se houver mais de uma (só por alteração feita fora do sistema),
     * vale o menor preço.
     */
    public Optional<Vigente> vigente(Connection con, long produtoId, LocalDate data) throws SQLException {
        String sql = """
                SELECT id, preco_promocional
                  FROM promocao
                 WHERE produto_id = ?
                   AND ativa
                   AND data_inicio <= ?
                   AND data_fim >= ?
                 ORDER BY preco_promocional, id DESC
                 LIMIT 1
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setObject(2, data);
            ps.setObject(3, data);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Vigente(rs.getLong(1), rs.getBigDecimal(2))) : Optional.empty();
            }
        }
    }

    public long inserir(Connection con, DadosPromocao dados, long criadoPor, OffsetDateTime criadoEm)
            throws SQLException {
        String sql = """
                INSERT INTO promocao (produto_id, preco_promocional, data_inicio, data_fim, ativa, criado_por,
                                      criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, dados.produtoId());
            ps.setBigDecimal(2, dados.precoPromocional());
            ps.setObject(3, dados.inicio());
            ps.setObject(4, dados.fim());
            ps.setBoolean(5, dados.ativa());
            ps.setLong(6, criadoPor);
            ps.setObject(7, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Preço, período e situação (o produto não muda). */
    public void alterar(Connection con, long id, DadosPromocao dados) throws SQLException {
        String sql = """
                UPDATE promocao
                   SET preco_promocional = ?, data_inicio = ?, data_fim = ?, ativa = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, dados.precoPromocional());
            ps.setObject(2, dados.inicio());
            ps.setObject(3, dados.fim());
            ps.setBoolean(4, dados.ativa());
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** A promoção já foi aplicada em alguma venda (o item guarda {@code promocao_id})? */
    public boolean usadaEmVenda(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM venda_item WHERE promocao_id = ?)")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    public void excluir(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM promocao WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static Promocao ler(ResultSet rs) throws SQLException {
        return new Promocao(rs.getLong("id"), rs.getLong("produto_id"), rs.getString("produto_nome"),
                rs.getString("unidade"), rs.getBigDecimal("preco_venda"), rs.getBigDecimal("custo"),
                rs.getBigDecimal("preco_promocional"), rs.getObject("data_inicio", LocalDate.class),
                rs.getObject("data_fim", LocalDate.class), rs.getBoolean("ativa"), rs.getString("criado_por"),
                rs.getObject("criado_em", OffsetDateTime.class));
    }

    private static String escaparLike(String termo) {
        return termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void definirLong(PreparedStatement ps, int indice, Long valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, java.sql.Types.BIGINT);
        } else {
            ps.setLong(indice, valor);
        }
    }
}
