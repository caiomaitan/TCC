package br.com.varesync.dao;

import br.com.varesync.model.Recebivel;
import br.com.varesync.model.StatusRecebivel;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Recebíveis de cartão, PIX e das outras formas com conta de destino (F15.2). */
public class RecebivelDAO {

    private static final String SELECT = """
            SELECT r.id, r.forma_pagamento_id, f.descricao AS forma, r.conta_id, c.nome AS conta, r.valor_bruto, r.taxa,
                   r.valor_liquido, r.previsto_para, r.status, r.recebido_em,
                   CASE WHEN r.venda_pagamento_id IS NOT NULL THEN 'Venda nº ' || vp.venda_id
                        ELSE 'Recebimento nº ' || rp.recebimento_id END AS origem
              FROM recebivel r
              JOIN forma_pagamento f ON f.id = r.forma_pagamento_id
              JOIN conta_financeira c ON c.id = r.conta_id
              LEFT JOIN venda_pagamento vp ON vp.id = r.venda_pagamento_id
              LEFT JOIN recebimento_pagamento rp ON rp.id = r.recebimento_pagamento_id
            """;

    /** Filtro da conciliação: forma, situação e previsão de/até (nulos = todos). */
    public record Filtro(Integer formaId, StatusRecebivel status, LocalDate de, LocalDate ate) {
    }

    public record Novo(Long vendaPagamentoId, Long recebimentoPagamentoId, int formaId, long contaId,
                       BigDecimal valorBruto, BigDecimal taxa, LocalDate previstoPara, StatusRecebivel status,
                       LocalDate recebidoEm, OffsetDateTime criadoEm) {
    }

    /** @return id do recebível */
    public long inserir(Connection con, Novo n) throws SQLException {
        String sql = """
                INSERT INTO recebivel (venda_pagamento_id, recebimento_pagamento_id, forma_pagamento_id, conta_id,
                                       valor_bruto, taxa, valor_liquido, previsto_para, status, recebido_em, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Jdbc.definirLong(ps, 1, n.vendaPagamentoId());
            Jdbc.definirLong(ps, 2, n.recebimentoPagamentoId());
            ps.setInt(3, n.formaId());
            ps.setLong(4, n.contaId());
            ps.setBigDecimal(5, n.valorBruto());
            ps.setBigDecimal(6, n.taxa());
            ps.setBigDecimal(7, n.valorBruto().subtract(n.taxa()));
            ps.setObject(8, n.previstoPara());
            ps.setString(9, n.status().name());
            ps.setObject(10, n.recebidoEm());
            ps.setObject(11, n.criadoEm());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<Recebivel> listar(Connection con, Filtro f) throws SQLException {
        String sql = SELECT + """
                 WHERE (?::int IS NULL OR r.forma_pagamento_id = ?::int)
                   AND (?::varchar IS NULL OR r.status = ?::varchar)
                   AND (?::date IS NULL OR r.previsto_para >= ?::date)
                   AND (?::date IS NULL OR r.previsto_para <= ?::date)
                 ORDER BY r.previsto_para, r.id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            String status = f.status() == null ? null : f.status().name();
            ps.setObject(1, f.formaId(), java.sql.Types.INTEGER);
            ps.setObject(2, f.formaId(), java.sql.Types.INTEGER);
            ps.setString(3, status);
            ps.setString(4, status);
            ps.setObject(5, f.de(), java.sql.Types.DATE);
            ps.setObject(6, f.de(), java.sql.Types.DATE);
            ps.setObject(7, f.ate(), java.sql.Types.DATE);
            ps.setObject(8, f.ate(), java.sql.Types.DATE);
            return lista(ps);
        }
    }

    /** Trava os recebíveis da venda, na ordem da conta (a do razão). */
    public List<Recebivel> travarDaVenda(Connection con, long vendaId) throws SQLException {
        String sql = SELECT + """
                 WHERE vp.venda_id = ?
                 ORDER BY r.conta_id, r.id
                   FOR UPDATE OF r
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            return lista(ps);
        }
    }

    /** Trava os recebíveis do recebimento do crediário, na ordem da conta (a do razão). */
    public List<Recebivel> travarDoRecebimento(Connection con, long recebimentoId) throws SQLException {
        String sql = SELECT + """
                 WHERE rp.recebimento_id = ?
                 ORDER BY r.conta_id, r.id
                   FOR UPDATE OF r
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, recebimentoId);
            return lista(ps);
        }
    }

    /** Trava os recebíveis escolhidos, na ordem da conta (a do razão). */
    public List<Recebivel> travar(Connection con, List<Long> ids) throws SQLException {
        String sql = SELECT + """
                 WHERE r.id = ANY (?)
                 ORDER BY r.conta_id, r.id
                   FOR UPDATE OF r
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            return lista(ps);
        }
    }

    public void cancelar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE recebivel SET status = 'CANCELADO' WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Marca como recebido, com o líquido que o banco pagou (a taxa passa a ser bruto − líquido). */
    public void marcarRecebido(Connection con, long id, BigDecimal liquido, LocalDate recebidoEm) throws SQLException {
        String sql = """
                UPDATE recebivel
                   SET status = 'RECEBIDO', recebido_em = ?, valor_liquido = ?, taxa = valor_bruto - ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, recebidoEm);
            ps.setBigDecimal(2, liquido);
            ps.setBigDecimal(3, liquido);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    private static List<Recebivel> lista(PreparedStatement ps) throws SQLException {
        List<Recebivel> recebiveis = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                recebiveis.add(new Recebivel(rs.getLong("id"), rs.getString("origem"),
                        rs.getInt("forma_pagamento_id"), rs.getString("forma"), rs.getLong("conta_id"),
                        rs.getString("conta"), rs.getBigDecimal("valor_bruto"), rs.getBigDecimal("taxa"),
                        rs.getBigDecimal("valor_liquido"), rs.getObject("previsto_para", LocalDate.class),
                        StatusRecebivel.valueOf(rs.getString("status")),
                        rs.getObject("recebido_em", LocalDate.class)));
            }
        }
        return recebiveis;
    }
}
