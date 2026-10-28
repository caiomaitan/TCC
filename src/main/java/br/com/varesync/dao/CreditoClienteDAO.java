package br.com.varesync.dao;

import br.com.varesync.model.InconsistenciaCredito;
import br.com.varesync.model.MovimentoCredito;
import br.com.varesync.model.TipoCreditoCliente;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Crédito do cliente (F12.3): razão {@code cliente_credito_movimento} e saldo {@code cliente_credito}, que só o
 * trigger do razão altera (V7). Não informa {@code saldo_anterior}/{@code saldo_posterior} no INSERT.
 */
public class CreditoClienteDAO {

    /** @return id do movimento */
    public long inserir(Connection con, long clienteId, TipoCreditoCliente tipo, BigDecimal valor, Long vendaId,
                        Long devolucaoId, String motivo, long usuarioId, OffsetDateTime criadoEm)
            throws SQLException {
        String sql = """
                INSERT INTO cliente_credito_movimento (cliente_id, tipo, valor, venda_id, devolucao_id, motivo,
                                                       usuario_id, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, clienteId);
            ps.setString(2, tipo.name());
            ps.setBigDecimal(3, valor);
            Jdbc.definirLong(ps, 4, vendaId);
            Jdbc.definirLong(ps, 5, devolucaoId);
            ps.setString(6, motivo);
            ps.setLong(7, usuarioId);
            ps.setObject(8, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Optional<BigDecimal> saldo(Connection con, long clienteId) throws SQLException {
        return saldo(con, clienteId, false);
    }

    /** Saldo com a linha travada até o fim da transação (antes de conferir o uso do crédito, 0.2). */
    public Optional<BigDecimal> travarSaldo(Connection con, long clienteId) throws SQLException {
        return saldo(con, clienteId, true);
    }

    private Optional<BigDecimal> saldo(Connection con, long clienteId, boolean travar) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT saldo FROM cliente_credito WHERE cliente_id = ?"
                + (travar ? " FOR UPDATE" : ""))) {
            ps.setLong(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBigDecimal(1)) : Optional.empty();
            }
        }
    }

    /** Extrato do cliente, do mais antigo para o mais recente. */
    public List<MovimentoCredito> movimentos(Connection con, long clienteId) throws SQLException {
        String sql = """
                SELECT m.id, m.criado_em, m.tipo, m.valor, m.saldo_anterior, m.saldo_posterior, m.venda_id, m.motivo,
                       u.nome AS usuario
                  FROM cliente_credito_movimento m
                  JOIN usuario u ON u.id = m.usuario_id
                 WHERE m.cliente_id = ?
                 ORDER BY m.criado_em, m.id
                """;
        List<MovimentoCredito> movimentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movimentos.add(new MovimentoCredito(rs.getLong("id"),
                            rs.getObject("criado_em", OffsetDateTime.class),
                            TipoCreditoCliente.valueOf(rs.getString("tipo")), rs.getBigDecimal("valor"),
                            rs.getBigDecimal("saldo_anterior"), rs.getBigDecimal("saldo_posterior"),
                            Jdbc.lerLong(rs, "venda_id"), rs.getString("motivo"), rs.getString("usuario")));
                }
            }
        }
        return movimentos;
    }

    /** Movimento de crédito gravado por uma venda: cliente, tipo e valor (com o sinal). */
    public record DaVenda(long clienteId, TipoCreditoCliente tipo, BigDecimal valor) {
    }

    /**
     * Movimentos de crédito gravados pela própria venda (sem os das devoluções, F13), na ordem de gravação (no máximo
     * um por tipo, {@code ux_ccm_venda_tipo}).
     */
    public List<DaVenda> daVenda(Connection con, long vendaId) throws SQLException {
        List<DaVenda> movimentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT cliente_id, tipo, valor FROM cliente_credito_movimento
                 WHERE venda_id = ? AND devolucao_id IS NULL
                 ORDER BY id
                """)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movimentos.add(new DaVenda(rs.getLong(1), TipoCreditoCliente.valueOf(rs.getString(2)),
                            rs.getBigDecimal(3)));
                }
            }
        }
        return movimentos;
    }

    /** Movimentos de crédito gravados por uma devolução (vale-troca e estorno do cashback), na ordem de gravação. */
    public List<DaVenda> daDevolucao(Connection con, long devolucaoId) throws SQLException {
        List<DaVenda> movimentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT cliente_id, tipo, valor FROM cliente_credito_movimento WHERE devolucao_id = ? ORDER BY id
                """)) {
            ps.setLong(1, devolucaoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movimentos.add(new DaVenda(rs.getLong(1), TipoCreditoCliente.valueOf(rs.getString(2)),
                            rs.getBigDecimal(3)));
                }
            }
        }
        return movimentos;
    }

    /** Cashback da venda já estornado pelas devoluções dela (valor positivo; zero sem estorno). */
    public BigDecimal cashbackEstornadoEmDevolucoes(Connection con, long vendaId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT coalesce(-sum(valor), 0) FROM cliente_credito_movimento
                 WHERE venda_id = ? AND tipo = 'ESTORNO_CASHBACK' AND devolucao_id IS NOT NULL
                """)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Quantos movimentos de crédito o cliente tem: com algum, ele não pode ser excluído. */
    public int contarMovimentos(Connection con, long clienteId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM cliente_credito_movimento WHERE cliente_id = ?")) {
            ps.setLong(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** {@code vw_cliente_credito_inconsistente} com o nome do cliente (deve vir vazia). */
    public List<InconsistenciaCredito> inconsistencias(Connection con) throws SQLException {
        String sql = """
                SELECT v.cliente_id, c.nome, v.saldo_registrado, v.saldo_pelos_movimentos
                  FROM vw_cliente_credito_inconsistente v
                  JOIN cliente c ON c.id = v.cliente_id
                 ORDER BY v.cliente_id
                """;
        List<InconsistenciaCredito> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                linhas.add(new InconsistenciaCredito(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)));
            }
        }
        return linhas;
    }
}
