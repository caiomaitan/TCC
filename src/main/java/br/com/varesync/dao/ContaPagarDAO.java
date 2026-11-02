package br.com.varesync.dao;

import br.com.varesync.model.CategoriaDespesa;
import br.com.varesync.model.GrupoDespesa;
import br.com.varesync.model.PagamentoTitulo;
import br.com.varesync.model.StatusTituloPagar;
import br.com.varesync.model.TituloPagar;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Contas a pagar (F15.3): categorias de despesa, títulos e pagamentos. */
public class ContaPagarDAO {

    private static final String SELECT_TITULO = """
            SELECT t.id, t.categoria_id, c.nome AS categoria, t.descricao, t.competencia, t.vencimento, t.fornecedor_id,
                   f.razao_social AS fornecedor, t.entrada_id, t.documento, t.emissao, t.valor, t.valor_pago, t.status
              FROM titulo_pagar t
              JOIN categoria_despesa c ON c.id = t.categoria_id
              LEFT JOIN fornecedor f ON f.id = t.fornecedor_id
            """;

    private static final String SELECT_PAGAMENTO = """
            SELECT p.id, p.titulo_pagar_id, p.conta_id, c.nome AS conta, p.data, p.valor, p.juros, p.desconto,
                   u.nome AS usuario, p.estornado, p.motivo_estorno
              FROM titulo_pagar_pagamento p
              JOIN conta_financeira c ON c.id = p.conta_id
              JOIN usuario u ON u.id = p.usuario_id
            """;

    /** Filtro da lista: situação, categoria e vencimento de/até (nulos = todos). */
    public record Filtro(StatusTituloPagar status, Long categoriaId, LocalDate de, LocalDate ate) {
    }

    public record Novo(long categoriaId, String descricao, LocalDate competencia, LocalDate vencimento,
                       Long fornecedorId, Long entradaId, String documento, LocalDate emissao, BigDecimal valor,
                       OffsetDateTime criadoEm, long criadoPor) {
    }

    // ----- Categorias -----

    public List<CategoriaDespesa> categorias(Connection con) throws SQLException {
        List<CategoriaDespesa> categorias = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT id, nome, grupo, ativa FROM categoria_despesa ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                categorias.add(categoria(rs));
            }
        }
        return categorias;
    }

    public Optional<CategoriaDespesa> categoria(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id, nome, grupo, ativa FROM categoria_despesa WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(categoria(rs)) : Optional.empty();
            }
        }
    }

    /** A categoria das parcelas da entrada: a primeira do grupo MERCADORIA, mesmo inativa. */
    public long categoriaDeMercadoria(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM categoria_despesa WHERE grupo = 'MERCADORIA' ORDER BY id LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("Sem categoria de despesa do grupo MERCADORIA");
            }
            return rs.getLong(1);
        }
    }

    public long inserirCategoria(Connection con, String nome, GrupoDespesa grupo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO categoria_despesa (nome, grupo) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, nome);
            ps.setString(2, grupo.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void alterarSituacaoCategoria(Connection con, long id, boolean ativa) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE categoria_despesa SET ativa = ? WHERE id = ?")) {
            ps.setBoolean(1, ativa);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ----- Títulos -----

    public long inserirTitulo(Connection con, Novo n) throws SQLException {
        String sql = """
                INSERT INTO titulo_pagar (categoria_id, descricao, competencia, vencimento, fornecedor_id, entrada_id,
                                          documento, emissao, valor, criado_em, criado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, n.categoriaId());
            ps.setString(2, n.descricao());
            ps.setObject(3, n.competencia());
            ps.setObject(4, n.vencimento());
            Jdbc.definirLong(ps, 5, n.fornecedorId());
            Jdbc.definirLong(ps, 6, n.entradaId());
            ps.setString(7, n.documento());
            ps.setObject(8, n.emissao());
            ps.setBigDecimal(9, n.valor());
            ps.setObject(10, n.criadoEm());
            ps.setLong(11, n.criadoPor());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<TituloPagar> listar(Connection con, Filtro f) throws SQLException {
        String sql = SELECT_TITULO + """
                 WHERE (?::varchar IS NULL OR t.status = ?::varchar)
                   AND (?::bigint IS NULL OR t.categoria_id = ?::bigint)
                   AND (?::date IS NULL OR t.vencimento >= ?::date)
                   AND (?::date IS NULL OR t.vencimento <= ?::date)
                 ORDER BY t.vencimento, t.id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            String status = f.status() == null ? null : f.status().name();
            ps.setString(1, status);
            ps.setString(2, status);
            ps.setObject(3, f.categoriaId(), Types.BIGINT);
            ps.setObject(4, f.categoriaId(), Types.BIGINT);
            ps.setObject(5, f.de(), Types.DATE);
            ps.setObject(6, f.de(), Types.DATE);
            ps.setObject(7, f.ate(), Types.DATE);
            ps.setObject(8, f.ate(), Types.DATE);
            return titulos(ps);
        }
    }

    /** @param travar trava o título até o fim da transação */
    public Optional<TituloPagar> buscar(Connection con, long id, boolean travar) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_TITULO + " WHERE t.id = ?"
                + (travar ? " FOR UPDATE OF t" : ""))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(titulo(rs)) : Optional.empty();
            }
        }
    }

    public List<TituloPagar> daEntrada(Connection con, long entradaId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_TITULO + " WHERE t.entrada_id = ? ORDER BY t.id")) {
            ps.setLong(1, entradaId);
            return titulos(ps);
        }
    }

    /** Soma {@code delta} ao pago (negativo no estorno) e acerta a situação. */
    public void baixar(Connection con, long id, BigDecimal delta) throws SQLException {
        String sql = """
                UPDATE titulo_pagar
                   SET valor_pago = valor_pago + ?,
                       status = CASE WHEN valor_pago + ? = valor THEN 'PAGO' ELSE 'ABERTO' END
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, delta);
            ps.setBigDecimal(2, delta);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void cancelar(Connection con, long id, String motivo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE titulo_pagar SET status = 'CANCELADO', motivo_cancelamento = ? WHERE id = ?")) {
            ps.setString(1, motivo);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ----- Pagamentos -----

    /** @return id do pagamento */
    public long inserirPagamento(Connection con, long tituloId, long contaId, LocalDate data, BigDecimal valor,
                                 BigDecimal juros, BigDecimal desconto, long usuarioId, OffsetDateTime criadoEm)
            throws SQLException {
        String sql = """
                INSERT INTO titulo_pagar_pagamento (titulo_pagar_id, conta_id, data, valor, juros, desconto, usuario_id,
                                                    criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, tituloId);
            ps.setLong(2, contaId);
            ps.setObject(3, data);
            ps.setBigDecimal(4, valor);
            ps.setBigDecimal(5, juros);
            ps.setBigDecimal(6, desconto);
            ps.setLong(7, usuarioId);
            ps.setObject(8, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<PagamentoTitulo> pagamentos(Connection con, long tituloId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_PAGAMENTO + " WHERE p.titulo_pagar_id = ? ORDER BY p.id")) {
            ps.setLong(1, tituloId);
            List<PagamentoTitulo> pagamentos = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pagamentos.add(pagamento(rs));
                }
            }
            return pagamentos;
        }
    }

    public Optional<PagamentoTitulo> pagamento(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_PAGAMENTO + " WHERE p.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(pagamento(rs)) : Optional.empty();
            }
        }
    }

    public void estornarPagamento(Connection con, long id, long usuarioId, OffsetDateTime em, String motivo)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE titulo_pagar_pagamento
                   SET estornado = TRUE, estornado_por = ?, estornado_em = ?, motivo_estorno = ?
                 WHERE id = ?
                """)) {
            ps.setLong(1, usuarioId);
            ps.setObject(2, em);
            ps.setString(3, motivo);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    private static List<TituloPagar> titulos(PreparedStatement ps) throws SQLException {
        List<TituloPagar> titulos = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                titulos.add(titulo(rs));
            }
        }
        return titulos;
    }

    private static PagamentoTitulo pagamento(ResultSet rs) throws SQLException {
        return new PagamentoTitulo(rs.getLong("id"), rs.getLong("titulo_pagar_id"), rs.getLong("conta_id"),
                rs.getString("conta"), rs.getObject("data", LocalDate.class), rs.getBigDecimal("valor"),
                rs.getBigDecimal("juros"), rs.getBigDecimal("desconto"), rs.getString("usuario"),
                rs.getBoolean("estornado"), rs.getString("motivo_estorno"));
    }

    private static CategoriaDespesa categoria(ResultSet rs) throws SQLException {
        return new CategoriaDespesa(rs.getLong("id"), rs.getString("nome"), GrupoDespesa.valueOf(rs.getString("grupo")),
                rs.getBoolean("ativa"));
    }

    private static TituloPagar titulo(ResultSet rs) throws SQLException {
        return new TituloPagar(rs.getLong("id"), rs.getLong("categoria_id"), rs.getString("categoria"),
                rs.getString("descricao"), rs.getObject("competencia", LocalDate.class),
                rs.getObject("vencimento", LocalDate.class), Jdbc.lerLong(rs, "fornecedor_id"),
                rs.getString("fornecedor"), Jdbc.lerLong(rs, "entrada_id"), rs.getString("documento"),
                rs.getObject("emissao", LocalDate.class), rs.getBigDecimal("valor"), rs.getBigDecimal("valor_pago"),
                StatusTituloPagar.valueOf(rs.getString("status")));
    }
}
