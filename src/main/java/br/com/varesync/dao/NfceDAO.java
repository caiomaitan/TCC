package br.com.varesync.dao;

import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.TipoEmissaoNfce;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** NFC-e (F23.1): tabela {@code nfce} e a leitura da venda para a montagem (F23.3). */
public class NfceDAO {

    /** Dados da venda para a NFC-e: itens não cancelados, na ordem, e os pagamentos com o tPag da forma. */
    public record VendaNfce(long id, OffsetDateTime dataHora, StatusVenda status, BigDecimal total, BigDecimal troco,
                            String consumidorDocumento, List<ItemVenda> itens, List<PagamentoVenda> pagamentos) {
    }

    /** @param desconto o desconto do item + o rateado */
    public record ItemVenda(long vendaItemId, int sequencia, long produtoId, String nome, String unidade,
                            String codigoBarras, BigDecimal quantidade, BigDecimal valorBruto, BigDecimal desconto) {
    }

    public record PagamentoVenda(String tpag, String descricao, BigDecimal valor) {
    }

    private static final String COLUNAS = """
            id, venda_id, serie, numero, codigo_numerico, chave, tipo_emissao, emitida_em, contingencia_em, status,
            cstat, motivo, protocolo, xml, qrcode
            """;
    private static final String BUSCAR = "SELECT " + COLUNAS + " FROM nfce WHERE id = ?";
    private static final String BUSCAR_TRAVANDO = "SELECT " + COLUNAS + " FROM nfce WHERE id = ? FOR UPDATE";

    public long inserir(Connection con, long vendaId, int serie, int numero, String codigoNumerico, String chave,
                        TipoEmissaoNfce tipo, OffsetDateTime emitidaEm, OffsetDateTime contingenciaEm,
                        StatusNfce status, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO nfce (venda_id, serie, numero, codigo_numerico, chave, tipo_emissao, emitida_em,
                                  contingencia_em, status, criado_em, atualizado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)) {
            ps.setLong(1, vendaId);
            ps.setInt(2, serie);
            ps.setInt(3, numero);
            ps.setString(4, codigoNumerico);
            ps.setString(5, chave);
            ps.setInt(6, tipo.codigo());
            ps.setObject(7, emitidaEm);
            ps.setObject(8, contingenciaEm);
            ps.setString(9, status.name());
            ps.setObject(10, agora);
            ps.setObject(11, agora);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Optional<NfceGravada> buscar(Connection con, long id, boolean travar) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(travar ? BUSCAR_TRAVANDO : BUSCAR)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    public List<NfceGravada> daVenda(Connection con, long vendaId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT " + COLUNAS
                + " FROM nfce WHERE venda_id = ? ORDER BY id")) {
            ps.setLong(1, vendaId);
            List<NfceGravada> lista = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(ler(rs));
                }
            }
            return lista;
        }
    }

    private static NfceGravada ler(ResultSet rs) throws SQLException {
        return new NfceGravada(rs.getLong("id"), rs.getLong("venda_id"), rs.getInt("serie"), rs.getInt("numero"),
                rs.getString("codigo_numerico"), rs.getString("chave"), TipoEmissaoNfce.de(rs.getInt("tipo_emissao")),
                rs.getObject("emitida_em", OffsetDateTime.class), rs.getObject("contingencia_em", OffsetDateTime.class),
                StatusNfce.valueOf(rs.getString("status")), rs.getString("cstat"), rs.getString("motivo"),
                rs.getString("protocolo"), rs.getString("xml"), rs.getString("qrcode"));
    }

    /** O XML assinado e validado e o QR Code (a situação não muda). */
    public void gravarXml(Connection con, long id, String xml, String qrcode, OffsetDateTime agora)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET xml = ?, qrcode = ?, cstat = NULL, motivo = NULL, atualizado_em = ? WHERE id = ?
                """)) {
            ps.setString(1, xml);
            ps.setString(2, qrcode);
            ps.setObject(3, agora);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    /** Recusada pela validação local (sem cStat da SEFAZ): REJEITADA, com a mensagem. */
    public void rejeitarLocal(Connection con, long id, String motivo, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET status = 'REJEITADA', cstat = NULL, motivo = ?, atualizado_em = ? WHERE id = ?
                """)) {
            ps.setString(1, motivo);
            ps.setObject(2, agora);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public VendaNfce venda(Connection con, long vendaId) throws SQLException {
        OffsetDateTime dataHora;
        StatusVenda status;
        BigDecimal total;
        BigDecimal troco;
        String documento;
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT data_hora, status, valor_total, troco, consumidor_documento FROM venda WHERE id = ?
                """)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Venda " + vendaId + " não encontrada");
                }
                dataHora = rs.getObject(1, OffsetDateTime.class);
                status = StatusVenda.valueOf(rs.getString(2));
                total = rs.getBigDecimal(3);
                troco = rs.getBigDecimal(4);
                documento = rs.getString(5);
            }
        }
        List<ItemVenda> itens = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT vi.id, vi.sequencia, vi.produto_id, p.nome, p.unidade,
                       (SELECT cb.codigo FROM produto_codigo_barras cb
                         WHERE cb.produto_id = p.id AND cb.principal ORDER BY cb.id LIMIT 1) AS codigo_barras,
                       vi.quantidade, vi.valor_bruto, vi.desconto + vi.desconto_rateado AS desconto
                  FROM venda_item vi
                  JOIN produto p ON p.id = vi.produto_id
                 WHERE vi.venda_id = ? AND NOT vi.cancelado
                 ORDER BY vi.sequencia
                """)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    itens.add(new ItemVenda(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                            rs.getBigDecimal(9)));
                }
            }
        }
        List<PagamentoVenda> pagamentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT fp.tpag, fp.descricao, vp.valor
                  FROM venda_pagamento vp
                  JOIN forma_pagamento fp ON fp.id = vp.forma_pagamento_id
                 WHERE vp.venda_id = ?
                 ORDER BY vp.id
                """)) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pagamentos.add(new PagamentoVenda(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)));
                }
            }
        }
        return new VendaNfce(vendaId, dataHora, status, total, troco, documento, itens, pagamentos);
    }

    /** As linhas de {@code vw_nfce_inconsistente}, prontas para o Diagnóstico. */
    public List<String> inconsistencias(Connection con) throws SQLException {
        List<String> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT serie, numero, venda_id, problema FROM vw_nfce_inconsistente ORDER BY serie, numero
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                linhas.add("NFC-e nº " + rs.getInt(2) + " (série " + rs.getInt(1) + "), venda nº " + rs.getLong(3)
                        + ": " + rs.getString(4));
            }
        }
        return linhas;
    }
}
