package br.com.varesync.dao;

import br.com.varesync.model.ComprovanteRecebimento;
import br.com.varesync.model.RecebimentoSessao;
import br.com.varesync.model.VendasPorForma;
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

/** Recebimentos do crediário ({@code recebimento}, {@code recebimento_titulo}, {@code recebimento_pagamento}, V10). */
public class RecebimentoDAO {

    /** Valores gravados em {@code recebimento}. */
    public record Novo(long clienteId, long caixaSessaoId, long usuarioId, Long liberadoPor, OffsetDateTime dataHora,
                       BigDecimal principal, BigDecimal multa, BigDecimal juros, BigDecimal desconto, BigDecimal total,
                       BigDecimal troco) {
    }

    /** Recebimento lido para o estorno e o comprovante. */
    public record Cabecalho(long id, long clienteId, String clienteNome, String clienteDocumento, boolean anonimizado,
                            long caixaSessaoId, String usuario, String liberadoPor, OffsetDateTime dataHora,
                            BigDecimal principal, BigDecimal multa, BigDecimal juros, BigDecimal desconto,
                            BigDecimal total, BigDecimal troco, boolean estornado, OffsetDateTime estornadoEm) {
    }

    /** Parte de um título num recebimento. */
    public record Parte(long tituloId, long vendaId, int parcela, int totalParcelas, java.time.LocalDate vencimento,
                        BigDecimal principal, BigDecimal multa, BigDecimal juros, BigDecimal desconto) {
    }

    /** @return id do recebimento */
    public long inserir(Connection con, Novo n) throws SQLException {
        String sql = """
                INSERT INTO recebimento (cliente_id, caixa_sessao_id, usuario_id, liberado_por, data_hora,
                                         valor_principal, multa, juros, desconto, valor_total, troco)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, n.clienteId());
            ps.setLong(2, n.caixaSessaoId());
            ps.setLong(3, n.usuarioId());
            if (n.liberadoPor() == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, n.liberadoPor());
            }
            ps.setObject(5, n.dataHora());
            ps.setBigDecimal(6, n.principal());
            ps.setBigDecimal(7, n.multa());
            ps.setBigDecimal(8, n.juros());
            ps.setBigDecimal(9, n.desconto());
            ps.setBigDecimal(10, n.total());
            ps.setBigDecimal(11, n.troco());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void inserirTitulo(Connection con, long recebimentoId, long tituloId, BigDecimal principal, BigDecimal multa,
                              BigDecimal juros, BigDecimal desconto) throws SQLException {
        String sql = """
                INSERT INTO recebimento_titulo (recebimento_id, titulo_id, principal, multa, juros, desconto)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, recebimentoId);
            ps.setLong(2, tituloId);
            ps.setBigDecimal(3, principal);
            ps.setBigDecimal(4, multa);
            ps.setBigDecimal(5, juros);
            ps.setBigDecimal(6, desconto);
            ps.executeUpdate();
        }
    }

    public long inserirPagamento(Connection con, long recebimentoId, int formaId, BigDecimal valor)
            throws SQLException {
        String sql = """
                INSERT INTO recebimento_pagamento (recebimento_id, forma_pagamento_id, valor)
                VALUES (?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, recebimentoId);
            ps.setInt(2, formaId);
            ps.setBigDecimal(3, valor);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** @param travar trava o recebimento até o fim da transação (estorno) */
    public Optional<Cabecalho> buscar(Connection con, long id, boolean travar) throws SQLException {
        String sql = """
                SELECT r.id, r.cliente_id, c.nome AS cliente_nome, c.cpf_cnpj, c.anonimizado, r.caixa_sessao_id,
                       u.nome AS usuario, l.nome AS liberado_por, r.data_hora, r.valor_principal, r.multa, r.juros,
                       r.desconto, r.valor_total, r.troco, r.estornado, r.estornado_em
                  FROM recebimento r
                  JOIN cliente c      ON c.id = r.cliente_id
                  JOIN usuario u      ON u.id = r.usuario_id
                  LEFT JOIN usuario l ON l.id = r.liberado_por
                 WHERE r.id = ?
                """ + (travar ? " FOR UPDATE OF r" : "");
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Cabecalho(rs.getLong("id"), rs.getLong("cliente_id"),
                        rs.getString("cliente_nome"), rs.getString("cpf_cnpj"), rs.getBoolean("anonimizado"),
                        rs.getLong("caixa_sessao_id"), rs.getString("usuario"), rs.getString("liberado_por"),
                        rs.getObject("data_hora", OffsetDateTime.class), rs.getBigDecimal("valor_principal"),
                        rs.getBigDecimal("multa"), rs.getBigDecimal("juros"), rs.getBigDecimal("desconto"),
                        rs.getBigDecimal("valor_total"), rs.getBigDecimal("troco"), rs.getBoolean("estornado"),
                        rs.getObject("estornado_em", OffsetDateTime.class)));
            }
        }
    }

    /** Títulos do recebimento, na ordem do título (a mesma das travas). */
    public List<Parte> partes(Connection con, long recebimentoId) throws SQLException {
        String sql = """
                SELECT rt.titulo_id, t.venda_id, t.parcela, t.total_parcelas, t.vencimento, rt.principal, rt.multa,
                       rt.juros, rt.desconto
                  FROM recebimento_titulo rt
                  JOIN titulo_receber t ON t.id = rt.titulo_id
                 WHERE rt.recebimento_id = ?
                 ORDER BY rt.titulo_id
                """;
        List<Parte> partes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, recebimentoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    partes.add(new Parte(rs.getLong("titulo_id"), rs.getLong("venda_id"), rs.getInt("parcela"),
                            rs.getInt("total_parcelas"), rs.getObject("vencimento", java.time.LocalDate.class),
                            rs.getBigDecimal("principal"), rs.getBigDecimal("multa"), rs.getBigDecimal("juros"),
                            rs.getBigDecimal("desconto")));
                }
            }
        }
        return partes;
    }

    public List<ComprovanteRecebimento.Pagamento> pagamentos(Connection con, long recebimentoId) throws SQLException {
        String sql = """
                SELECT f.descricao, f.tipo, rp.valor
                  FROM recebimento_pagamento rp
                  JOIN forma_pagamento f ON f.id = rp.forma_pagamento_id
                 WHERE rp.recebimento_id = ?
                 ORDER BY rp.id
                """;
        List<ComprovanteRecebimento.Pagamento> pagamentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, recebimentoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pagamentos.add(new ComprovanteRecebimento.Pagamento(rs.getString("descricao"),
                            "DINHEIRO".equals(rs.getString("tipo")), rs.getBigDecimal("valor")));
                }
            }
        }
        return pagamentos;
    }

    /** Dinheiro que o recebimento pôs na gaveta (já sem o troco). */
    public BigDecimal dinheiro(Connection con, long recebimentoId) throws SQLException {
        String sql = """
                SELECT coalesce(sum(rp.valor), 0)
                  FROM recebimento_pagamento rp
                  JOIN forma_pagamento f ON f.id = rp.forma_pagamento_id
                 WHERE rp.recebimento_id = ? AND f.tipo = 'DINHEIRO'
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, recebimentoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    public void estornar(Connection con, long id, long usuarioId, OffsetDateTime agora) throws SQLException {
        String sql = "UPDATE recebimento SET estornado = TRUE, estornado_por = ?, estornado_em = ? WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, usuarioId);
            ps.setObject(2, agora);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** Recebimentos da sessão de caixa, do mais recente para o mais antigo. */
    public List<RecebimentoSessao> daSessao(Connection con, long caixaSessaoId) throws SQLException {
        String sql = """
                SELECT r.id, r.data_hora, c.nome AS cliente, u.nome AS usuario, r.valor_principal,
                       r.multa + r.juros AS encargos, r.desconto, r.valor_total, r.troco, r.estornado,
                       (SELECT string_agg(f.descricao, ' + ' ORDER BY rp.id)
                          FROM recebimento_pagamento rp
                          JOIN forma_pagamento f ON f.id = rp.forma_pagamento_id
                         WHERE rp.recebimento_id = r.id) AS formas
                  FROM recebimento r
                  JOIN cliente c ON c.id = r.cliente_id
                  JOIN usuario u ON u.id = r.usuario_id
                 WHERE r.caixa_sessao_id = ?
                 ORDER BY r.data_hora DESC, r.id DESC
                """;
        List<RecebimentoSessao> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new RecebimentoSessao(rs.getLong("id"), rs.getObject("data_hora", OffsetDateTime.class),
                            rs.getString("cliente"), rs.getString("usuario"), rs.getBigDecimal("valor_principal"),
                            rs.getBigDecimal("encargos"), rs.getBigDecimal("desconto"),
                            rs.getBigDecimal("valor_total"), rs.getBigDecimal("troco"), rs.getString("formas"),
                            rs.getBoolean("estornado")));
                }
            }
        }
        return lista;
    }

    /** Recebimentos não estornados da sessão por forma de pagamento (R11). */
    public List<VendasPorForma> porForma(Connection con, long caixaSessaoId) throws SQLException {
        String sql = """
                SELECT f.descricao, count(*) AS quantidade, sum(rp.valor) AS valor
                  FROM recebimento r
                  JOIN recebimento_pagamento rp ON rp.recebimento_id = r.id
                  JOIN forma_pagamento f        ON f.id = rp.forma_pagamento_id
                 WHERE r.caixa_sessao_id = ? AND NOT r.estornado
                 GROUP BY f.id, f.descricao
                 ORDER BY f.id
                """;
        List<VendasPorForma> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new VendasPorForma(rs.getString("descricao"), rs.getLong("quantidade"),
                            rs.getBigDecimal("valor")));
                }
            }
        }
        return lista;
    }
}
