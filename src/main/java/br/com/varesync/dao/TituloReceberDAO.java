package br.com.varesync.dao;

import br.com.varesync.model.AlertasPainel;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.ExtratoCrediario;
import br.com.varesync.model.InconsistenciaAbatimento;
import br.com.varesync.model.StatusTitulo;
import br.com.varesync.model.TituloReceber;
import br.com.varesync.util.CalculoEncargos;
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

    /** Grava quanto a devolução abateu da parcela (F14.4). */
    public void inserirAbatimento(Connection con, long devolucaoId, long tituloId, BigDecimal valor)
            throws SQLException {
        String sql = "INSERT INTO devolucao_titulo (devolucao_id, titulo_id, valor) VALUES (?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, devolucaoId);
            ps.setLong(2, tituloId);
            ps.setBigDecimal(3, valor);
            ps.executeUpdate();
        }
    }

    /**
     * Títulos em aberto do cliente, na ordem do id.
     *
     * @param travar trava as linhas até o fim da transação (recebimento): na ordem do id, como a devolução e o
     *               cancelamento, que travam as parcelas de uma venda na ordem da parcela
     */
    public List<TituloReceber> emAbertoDoClienteLista(Connection con, long clienteId, boolean travar)
            throws SQLException {
        String sql = """
                SELECT id, cliente_id, venda_id, parcela, total_parcelas, vencimento, valor, valor_pago, valor_abatido,
                       status
                  FROM titulo_receber
                 WHERE cliente_id = ? AND status = 'ABERTO'
                 ORDER BY id
                """ + (travar ? " FOR UPDATE" : "");
        List<TituloReceber> titulos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, clienteId);
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

    /** Soma o principal pago ao título, ou o tira no estorno (negativo); QUITADO quando não sobra nada em aberto. */
    public void baixar(Connection con, long id, BigDecimal principal) throws SQLException {
        String sql = """
                UPDATE titulo_receber
                   SET valor_pago = valor_pago + ?,
                       status = CASE WHEN valor_pago + ? + valor_abatido = valor THEN 'QUITADO' ELSE 'ABERTO' END
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, principal);
            ps.setBigDecimal(2, principal);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /**
     * As parcelas da venda como estavam num momento: valor − recebimentos que já valiam (estornado depois conta como
     * pago naquele dia) − abatimentos até ali. Parcela cancelada fica de fora.
     *
     * @param ateRecebimento considera só recebimentos até este número (nulo = todos os que vieram antes do momento)
     * @param ateDevolucao   considera só devoluções até este número (nulo = todas as que vieram antes do momento)
     */
    public List<Comprovante.ParcelaCrediario> posicaoEm(Connection con, long vendaId, OffsetDateTime momento,
                                                        Long ateRecebimento, Long ateDevolucao) throws SQLException {
        String sql = """
                SELECT t.parcela, t.total_parcelas, t.vencimento,
                       t.valor
                       - coalesce((SELECT sum(rt.principal)
                                     FROM recebimento_titulo rt
                                     JOIN recebimento r ON r.id = rt.recebimento_id
                                    WHERE rt.titulo_id = t.id AND r.data_hora <= ?
                                      AND (NOT r.estornado OR r.estornado_em > ?)
                                      AND (CAST(? AS BIGINT) IS NULL OR r.id <= ?)), 0)
                       - coalesce((SELECT sum(dt.valor)
                                     FROM devolucao_titulo dt
                                     JOIN devolucao d ON d.id = dt.devolucao_id
                                    WHERE dt.titulo_id = t.id AND d.data_hora <= ?
                                      AND (CAST(? AS BIGINT) IS NULL OR d.id <= ?)), 0) AS em_aberto
                  FROM titulo_receber t
                 WHERE t.venda_id = ? AND t.status <> 'CANCELADO'
                 ORDER BY t.parcela
                """;
        List<Comprovante.ParcelaCrediario> parcelas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, momento);
            ps.setObject(2, momento);
            ps.setObject(3, ateRecebimento, Types.BIGINT);
            ps.setObject(4, ateRecebimento, Types.BIGINT);
            ps.setObject(5, momento);
            ps.setObject(6, ateDevolucao, Types.BIGINT);
            ps.setObject(7, ateDevolucao, Types.BIGINT);
            ps.setLong(8, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parcelas.add(new Comprovante.ParcelaCrediario(rs.getInt("parcela"), rs.getInt("total_parcelas"),
                            rs.getObject("vencimento", LocalDate.class), rs.getBigDecimal("em_aberto")));
                }
            }
        }
        return parcelas;
    }

    /** Parcelas do cliente para o extrato (F14.4), do vencimento mais antigo para o mais novo. */
    public List<ExtratoCrediario.Parcela> parcelasDoCliente(Connection con, long clienteId, LocalDate hoje)
            throws SQLException {
        String sql = """
                SELECT id, venda_id, parcela, total_parcelas, emissao, vencimento, valor, valor_pago, valor_abatido,
                       status
                  FROM titulo_receber
                 WHERE cliente_id = ?
                 ORDER BY vencimento, venda_id, parcela
                """;
        List<ExtratoCrediario.Parcela> parcelas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, clienteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StatusTitulo status = StatusTitulo.valueOf(rs.getString("status"));
                    LocalDate vencimento = rs.getObject("vencimento", LocalDate.class);
                    BigDecimal valor = rs.getBigDecimal("valor");
                    BigDecimal pago = rs.getBigDecimal("valor_pago");
                    BigDecimal abatido = rs.getBigDecimal("valor_abatido");
                    boolean aberto = status == StatusTitulo.ABERTO;
                    parcelas.add(new ExtratoCrediario.Parcela(rs.getLong("id"), rs.getLong("venda_id"),
                            rs.getInt("parcela"), rs.getInt("total_parcelas"), rs.getObject("emissao", LocalDate.class),
                            vencimento, valor, pago, abatido,
                            aberto ? valor.subtract(pago).subtract(abatido) : BigDecimal.ZERO.setScale(2), status,
                            aberto ? CalculoEncargos.diasDeAtraso(vencimento, hoje) : 0));
                }
            }
        }
        return parcelas;
    }

    /** Efeitos no saldo devedor, sem o saldo acumulado: a ordem final é do service. */
    public record MovimentoExtrato(OffsetDateTime dataHora, int ordem, String tipo, String documento, BigDecimal valor,
                                   BigDecimal encargos) {
    }

    /** Vendas a prazo, cancelamentos, pagamentos, estornos e abatimentos do cliente (F14.4). */
    public List<MovimentoExtrato> movimentosDoCliente(Connection con, long clienteId) throws SQLException {
        String sql = """
                SELECT min(t.criado_em) AS data_hora, 0 AS ordem, 'Venda a prazo' AS tipo,
                       'Venda nº ' || t.venda_id || ' em ' || max(t.total_parcelas) || 'x' AS documento,
                       sum(t.valor) AS valor, 0 AS encargos
                  FROM titulo_receber t
                 WHERE t.cliente_id = ?
                 GROUP BY t.venda_id
                UNION ALL
                SELECT v.cancelada_em, 1, 'Venda cancelada', 'Venda nº ' || v.id, -sum(t.valor), 0
                  FROM titulo_receber t
                  JOIN venda v ON v.id = t.venda_id
                 WHERE t.cliente_id = ? AND t.status = 'CANCELADO'
                 GROUP BY v.id, v.cancelada_em
                UNION ALL
                SELECT r.data_hora, 2, 'Pagamento',
                       'Recebimento nº ' || r.id || ', parcela ' || t.parcela || '/' || t.total_parcelas
                           || ' da venda nº ' || t.venda_id,
                       -rt.principal, rt.multa + rt.juros - rt.desconto
                  FROM recebimento_titulo rt
                  JOIN recebimento r    ON r.id = rt.recebimento_id
                  JOIN titulo_receber t ON t.id = rt.titulo_id
                 WHERE t.cliente_id = ?
                UNION ALL
                SELECT r.estornado_em, 3, 'Pagamento estornado',
                       'Recebimento nº ' || r.id || ', parcela ' || t.parcela || '/' || t.total_parcelas
                           || ' da venda nº ' || t.venda_id,
                       rt.principal, -(rt.multa + rt.juros - rt.desconto)
                  FROM recebimento_titulo rt
                  JOIN recebimento r    ON r.id = rt.recebimento_id
                  JOIN titulo_receber t ON t.id = rt.titulo_id
                 WHERE t.cliente_id = ? AND r.estornado
                UNION ALL
                SELECT d.data_hora, 4, 'Abatimento',
                       'Devolução nº ' || d.id || ', parcela ' || t.parcela || '/' || t.total_parcelas
                           || ' da venda nº ' || t.venda_id,
                       -dt.valor, 0
                  FROM devolucao_titulo dt
                  JOIN devolucao d      ON d.id = dt.devolucao_id
                  JOIN titulo_receber t ON t.id = dt.titulo_id
                 WHERE t.cliente_id = ?
                 ORDER BY 1, 2, 4
                """;
        List<MovimentoExtrato> movimentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) {
                ps.setLong(i, clienteId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movimentos.add(new MovimentoExtrato(rs.getObject("data_hora", OffsetDateTime.class),
                            rs.getInt("ordem"), rs.getString("tipo"), rs.getString("documento"),
                            rs.getBigDecimal("valor"), rs.getBigDecimal("encargos")));
                }
            }
        }
        return movimentos;
    }

    /** Títulos em aberto com vencimento antes de {@code hoje}: quantos e quanto (alerta do painel, F14.4). */
    public AlertasPainel.TitulosVencidos vencidos(Connection con, LocalDate hoje) throws SQLException {
        String sql = """
                SELECT count(*), coalesce(sum(valor - valor_pago - valor_abatido), 0)
                  FROM titulo_receber
                 WHERE status = 'ABERTO' AND vencimento < ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, hoje);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new AlertasPainel.TitulosVencidos(rs.getInt(1), rs.getBigDecimal(2));
            }
        }
    }

    /** Diagnóstico: {@code vw_abatimento_inconsistente} (precisa vir vazia). */
    public List<InconsistenciaAbatimento> inconsistencias(Connection con) throws SQLException {
        String sql = "SELECT origem, id, valor_abatido, soma_linhas FROM vw_abatimento_inconsistente ORDER BY origem, id";
        List<InconsistenciaAbatimento> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new InconsistenciaAbatimento(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)));
            }
        }
        return lista;
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
