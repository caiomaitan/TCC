package br.com.varesync.dao;

import br.com.varesync.model.Lote;
import br.com.varesync.model.TipoLoteMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lotes (F17): {@code lote}, o saldo protegido {@code lote_saldo} (alterado só pelo trigger), o razão imutável
 * {@code lote_movimento} e as transferências. Grava somente pelo {@code EstoqueService}.
 */
public class LoteDAO {

    /** Uma linha do razão: o lote e a quantidade com sinal. */
    public record Linha(long loteId, BigDecimal quantidade) {
    }

    private static final String COLUNAS = """
            SELECT l.id, l.produto_id, l.codigo, l.validade, s.saldo, l.criado_em
              FROM lote l
              JOIN lote_saldo s ON s.lote_id = l.id
            """;

    /**
     * Lotes do produto: o "SEM LOTE" primeiro e os demais por validade (FEFO) e, no empate, o mais antigo.
     */
    public List<Lote> listar(Connection con, long produtoId) throws SQLException {
        String sql = COLUNAS + """
                 WHERE l.produto_id = ?
                 ORDER BY (l.validade IS NOT NULL), l.validade, l.criado_em, l.id
                """;
        List<Lote> lotes = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lotes.add(ler(rs));
                }
            }
        }
        return lotes;
    }

    public Optional<Lote> buscar(Connection con, long loteId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE l.id = ?")) {
            ps.setLong(1, loteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    /** Lote pelo código (já normalizado) e pela validade (nula = "SEM LOTE"). */
    public Optional<Long> buscarId(Connection con, long produtoId, String codigo, LocalDate validade)
            throws SQLException {
        String sql = """
                SELECT id FROM lote
                 WHERE produto_id = ? AND codigo = ? AND validade IS NOT DISTINCT FROM CAST(? AS DATE)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setString(2, codigo);
            ps.setObject(3, validade);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** Cria o lote (o trigger cria o saldo zero). */
    public long inserir(Connection con, long produtoId, String codigo, LocalDate validade, long usuarioId,
                        OffsetDateTime criadoEm) throws SQLException {
        String sql = """
                INSERT INTO lote (produto_id, codigo, validade, criado_em, criado_por)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setString(2, codigo);
            ps.setObject(3, validade);
            ps.setObject(4, criadoEm);
            ps.setLong(5, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Linha do razão (o trigger confere produto, sinal e controle e atualiza o saldo do lote). */
    public void inserirMovimento(Connection con, long loteId, TipoLoteMovimento tipo, Long movimentoEstoqueId,
                                 Long transferenciaId, BigDecimal quantidade, long usuarioId, OffsetDateTime criadoEm)
            throws SQLException {
        String sql = """
                INSERT INTO lote_movimento (lote_id, tipo, movimento_estoque_id, transferencia_id, quantidade,
                                            usuario_id, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, loteId);
            ps.setString(2, tipo.name());
            Jdbc.definirLong(ps, 3, movimentoEstoqueId);
            Jdbc.definirLong(ps, 4, transferenciaId);
            ps.setBigDecimal(5, quantidade);
            ps.setLong(6, usuarioId);
            ps.setObject(7, criadoEm);
            ps.executeUpdate();
        }
    }

    public long inserirTransferencia(Connection con, long produtoId, long origemId, long destinoId,
                                     BigDecimal quantidade, String motivo, long usuarioId, OffsetDateTime criadoEm)
            throws SQLException {
        String sql = """
                INSERT INTO lote_transferencia (produto_id, lote_origem_id, lote_destino_id, quantidade, motivo,
                                                usuario_id, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, produtoId);
            ps.setLong(2, origemId);
            ps.setLong(3, destinoId);
            ps.setBigDecimal(4, quantidade);
            ps.setString(5, motivo);
            ps.setLong(6, usuarioId);
            ps.setObject(7, criadoEm);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Linhas de um movimento de estoque, na ordem de gravação. */
    public List<Linha> doMovimento(Connection con, long movimentoEstoqueId) throws SQLException {
        return linhas(con, """
                SELECT lote_id, quantidade FROM lote_movimento
                 WHERE movimento_estoque_id = ?
                 ORDER BY id
                """, movimentoEstoqueId);
    }

    /** Linhas do movimento VENDA do item da venda, na ordem do consumo (vazio: venda sem controle). */
    public List<Linha> daVenda(Connection con, long vendaItemId) throws SQLException {
        return linhas(con, """
                SELECT lm.lote_id, lm.quantidade
                  FROM lote_movimento lm
                  JOIN movimento_estoque m ON m.id = lm.movimento_estoque_id
                 WHERE m.venda_item_id = ? AND m.tipo = 'VENDA'
                 ORDER BY lm.id
                """, vendaItemId);
    }

    /** Quanto já voltou para cada lote nas devoluções do item da venda. */
    public Map<Long, BigDecimal> devolvidoDaVenda(Connection con, long vendaItemId) throws SQLException {
        Map<Long, BigDecimal> devolvido = new LinkedHashMap<>();
        for (Linha l : linhas(con, """
                SELECT lm.lote_id, sum(lm.quantidade)
                  FROM lote_movimento lm
                  JOIN movimento_estoque m ON m.id = lm.movimento_estoque_id
                  JOIN devolucao_item di   ON di.id = m.devolucao_item_id
                 WHERE di.venda_item_id = ? AND m.tipo = 'DEVOLUCAO_VENDA'
                 GROUP BY lm.lote_id
                 ORDER BY lm.lote_id
                """, vendaItemId)) {
            devolvido.put(l.loteId(), l.quantidade());
        }
        return devolvido;
    }

    /** Item da venda de um item de devolução. */
    public Optional<Long> vendaItemDaDevolucao(Connection con, long devolucaoItemId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT venda_item_id FROM devolucao_item WHERE id = ?")) {
            ps.setLong(1, devolucaoItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** Linhas de {@code vw_lote_inconsistente} (esperado: nenhuma), como "PROBLEMA produto lote". */
    public List<String> inconsistencias(Connection con) throws SQLException {
        List<String> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT problema, produto_id, lote_id, transferencia_id, valor_registrado, valor_esperado
                  FROM vw_lote_inconsistente
                 ORDER BY produto_id, lote_id
                """); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                linhas.add(rs.getString(1) + " produto " + rs.getLong(2) + " lote " + Jdbc.lerLong(rs, "lote_id")
                        + " transferência " + Jdbc.lerLong(rs, "transferencia_id") + ": " + rs.getBigDecimal(5)
                        + " ≠ " + rs.getBigDecimal(6));
            }
        }
        return linhas;
    }

    private static List<Linha> linhas(Connection con, String sql, long parametro) throws SQLException {
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, parametro);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    linhas.add(new Linha(rs.getLong(1), rs.getBigDecimal(2)));
                }
            }
        }
        return linhas;
    }

    private static Lote ler(ResultSet rs) throws SQLException {
        return new Lote(rs.getLong("id"), rs.getLong("produto_id"), rs.getString("codigo"),
                rs.getObject("validade", LocalDate.class), rs.getBigDecimal("saldo"),
                rs.getObject("criado_em", OffsetDateTime.class));
    }
}
