package br.com.varesync.dao;

import br.com.varesync.model.StatusTitulo;
import br.com.varesync.model.TituloReceber;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Títulos a receber do crediário ({@code titulo_receber}, V10). */
public class TituloReceberDAO {

    /** @return id do título */
    public long inserir(Connection con, long clienteId, long vendaId, int parcela, int totalParcelas, LocalDate emissao,
                        LocalDate vencimento, BigDecimal valor, OffsetDateTime agora, long usuarioId)
            throws SQLException {
        String sql = """
                INSERT INTO titulo_receber (cliente_id, venda_id, parcela, total_parcelas, emissao, vencimento, valor,
                                            criado_em, criado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, clienteId);
            ps.setLong(2, vendaId);
            ps.setInt(3, parcela);
            ps.setInt(4, totalParcelas);
            ps.setObject(5, emissao);
            ps.setObject(6, vencimento);
            ps.setBigDecimal(7, valor);
            ps.setObject(8, agora);
            ps.setLong(9, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Títulos da venda, da primeira parcela para a última.
     *
     * @param travar trava as linhas até o fim da transação (devolução e cancelamento)
     */
    public List<TituloReceber> daVenda(Connection con, long vendaId, boolean travar) throws SQLException {
        String sql = """
                SELECT id, cliente_id, venda_id, parcela, total_parcelas, vencimento, valor, valor_pago, valor_abatido,
                       status
                  FROM titulo_receber
                 WHERE venda_id = ?
                 ORDER BY parcela
                """ + (travar ? " FOR UPDATE" : "");
        List<TituloReceber> titulos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    titulos.add(new TituloReceber(rs.getLong("id"), rs.getLong("cliente_id"), rs.getLong("venda_id"),
                            rs.getInt("parcela"), rs.getInt("total_parcelas"),
                            rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("valor"),
                            rs.getBigDecimal("valor_pago"), rs.getBigDecimal("valor_abatido"),
                            StatusTitulo.valueOf(rs.getString("status"))));
                }
            }
        }
        return titulos;
    }

    /** Soma do que o cliente tem em aberto. */
    public BigDecimal emAbertoDoCliente(Connection con, long clienteId) throws SQLException {
        String sql = """
                SELECT coalesce(sum(valor - valor_pago - valor_abatido), 0)
                  FROM titulo_receber
                 WHERE cliente_id = ? AND status = 'ABERTO'
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** O cliente tem título em aberto com vencimento antes de {@code data}? */
    public boolean temVencidoAntesDe(Connection con, long clienteId, LocalDate data) throws SQLException {
        String sql = """
                SELECT EXISTS (SELECT 1 FROM titulo_receber
                                WHERE cliente_id = ? AND status = 'ABERTO' AND vencimento < ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, clienteId);
            ps.setObject(2, data);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** Soma o abatimento ao título; {@code status} já é o resultado (QUITADO quando não sobra nada em aberto). */
    public void abater(Connection con, long id, BigDecimal valor, StatusTitulo status) throws SQLException {
        String sql = "UPDATE titulo_receber SET valor_abatido = valor_abatido + ?, status = ? WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, valor);
            ps.setString(2, status.name());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** Cancela os títulos da venda (cancelamento da venda, F14.2). */
    public void cancelarDaVenda(Connection con, long vendaId, String motivo) throws SQLException {
        String sql = "UPDATE titulo_receber SET status = 'CANCELADO', motivo_cancelamento = ? WHERE venda_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, motivo);
            ps.setLong(2, vendaId);
            ps.executeUpdate();
        }
    }
}
