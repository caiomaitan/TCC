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
            cstat, motivo, protocolo, xml, qrcode, autorizada_em, substituta_id
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
                rs.getString("protocolo"), rs.getString("xml"), rs.getString("qrcode"),
                rs.getObject("autorizada_em", OffsetDateTime.class), rs.getObject("substituta_id", Long.class));
    }

    // ----- Transmissão (F23.5) -----

    /**
     * Pega a trava da transmissão: {@code transmitindo_ate} = {@code ate}, se ninguém tem uma trava válida. Um UPDATE só
     * (atômico): de duas tentativas ao mesmo tempo, uma pega e a outra recebe vazio.
     */
    public Optional<NfceGravada> travar(Connection con, long id, String dono, OffsetDateTime agora,
                                        OffsetDateTime ate) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET transmitindo_ate = ?, transmitindo_por = ?, tentativas = tentativas + 1,
                                atualizado_em = ?
                 WHERE id = ? AND status IN ('PENDENTE', 'CONTINGENCIA', 'SEM_RESPOSTA', 'REJEITADA')
                   AND (transmitindo_ate IS NULL OR transmitindo_ate <= ?)
                """)) {
            ps.setObject(1, ate);
            ps.setString(2, dono);
            ps.setObject(3, agora);
            ps.setLong(4, id);
            ps.setObject(5, agora);
            if (ps.executeUpdate() == 0) {
                return Optional.empty();
            }
        }
        return buscar(con, id, false);
    }

    /** Solta a trava, se ainda é de quem a pegou. */
    public void soltar(Connection con, long id, String dono, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET transmitindo_ate = NULL, transmitindo_por = NULL, atualizado_em = ?
                 WHERE id = ? AND transmitindo_por = ?
                """)) {
            ps.setObject(1, agora);
            ps.setLong(2, id);
            ps.setString(3, dono);
            ps.executeUpdate();
        }
    }

    /** AUTORIZADA (cStat 100 ou 150), com o protocolo e o {@code nfeProc}. */
    public void autorizar(Connection con, long id, String cstat, String motivo, String protocolo,
                          OffsetDateTime autorizadaEm, String nfeProc, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET status = 'AUTORIZADA', cstat = ?, motivo = ?, protocolo = ?, autorizada_em = ?,
                                xml = ?, atualizado_em = ?
                 WHERE id = ?
                """)) {
            ps.setString(1, cstat);
            ps.setString(2, motivo);
            ps.setString(3, protocolo);
            ps.setObject(4, autorizadaEm);
            ps.setString(5, nfeProc);
            ps.setObject(6, agora);
            ps.setLong(7, id);
            ps.executeUpdate();
        }
    }

    /** A resposta da SEFAZ com a situação nova (REJEITADA, DENEGADA) ou a mesma (só registra o cStat e o motivo). */
    public void responder(Connection con, long id, StatusNfce status, String cstat, String motivo,
                          OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET status = ?, cstat = ?, motivo = ?, atualizado_em = ? WHERE id = ?
                """)) {
            ps.setString(1, status.name());
            ps.setString(2, cstat);
            ps.setString(3, motivo);
            ps.setObject(4, agora);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** SEM_RESPOSTA: enviada sem resposta, ou largada no meio; pode existir ou não na SEFAZ. */
    public void semResposta(Connection con, long id, String motivo, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET status = 'SEM_RESPOSTA', cstat = NULL, motivo = ?, atualizado_em = ? WHERE id = ?
                """)) {
            ps.setString(1, motivo);
            ps.setObject(2, agora);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void ligarSubstituta(Connection con, long id, long substitutaId, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET substituta_id = ?, atualizado_em = ? WHERE id = ?
                """)) {
            ps.setLong(1, substitutaId);
            ps.setObject(2, agora);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /**
     * A PENDENTE vira contingência off-line no mesmo número (modo contingência): tpEmis 9, a chave nova, dhEmi e dhCont;
     * o XML e o QR Code são refeitos.
     */
    public void paraContingencia(Connection con, long id, String chave, OffsetDateTime emitidaEm,
                                 OffsetDateTime contingenciaEm, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET tipo_emissao = 9, chave = ?, emitida_em = ?, contingencia_em = ?,
                                status = 'CONTINGENCIA', xml = NULL, qrcode = NULL, atualizado_em = ?
                 WHERE id = ? AND status = 'PENDENTE'
                """)) {
            ps.setString(1, chave);
            ps.setObject(2, emitidaEm);
            ps.setObject(3, contingenciaEm);
            ps.setObject(4, agora);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /**
     * SUBSTITUIDA: chegou a ser autorizada (protocolo e data da consulta) e foi cancelada pelo evento 110112.
     */
    public void substituida(Connection con, long id, String protocolo, OffsetDateTime autorizadaEm, String cstat,
                            String motivo, OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET status = 'SUBSTITUIDA', protocolo = ?, autorizada_em = ?, cstat = ?, motivo = ?,
                                atualizado_em = ?
                 WHERE id = ?
                """)) {
            ps.setString(1, protocolo);
            ps.setObject(2, autorizadaEm);
            ps.setString(3, cstat);
            ps.setString(4, motivo);
            ps.setObject(5, agora);
            ps.setLong(6, id);
            ps.executeUpdate();
        }
    }

    public void inutilizada(Connection con, long id, String cstat, String motivo, OffsetDateTime agora)
            throws SQLException {
        responder(con, id, StatusNfce.INUTILIZADA, cstat, motivo, agora);
    }

    /** Evento da NFC-e ({@code tipo} = CANCELAMENTO ou CANCELAMENTO_SUBSTITUICAO); usuário nulo = o transmissor. */
    public long inserirEvento(Connection con, long nfceId, String tipo, String justificativa, String cstat,
                              String motivo, String protocolo, String xml, OffsetDateTime agora, Long usuarioId)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO nfce_evento (nfce_id, tipo, justificativa, cstat, motivo, protocolo, xml, registrado_em,
                                         usuario_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)) {
            ps.setLong(1, nfceId);
            ps.setString(2, tipo);
            ps.setString(3, justificativa);
            ps.setString(4, cstat);
            ps.setString(5, motivo);
            ps.setString(6, protocolo);
            ps.setString(7, xml);
            ps.setObject(8, agora);
            ps.setObject(9, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Faixa de números inutilizados (ano com 2 dígitos); usuário nulo = o transmissor. */
    public long inserirInutilizacao(Connection con, int serie, int inicial, int fim, int ano, String justificativa,
                                    String cstat, String motivo, String protocolo, String xml, OffsetDateTime agora,
                                    Long usuarioId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO nfce_inutilizacao (serie, numero_inicial, numero_final, ano, justificativa, cstat, motivo,
                                               protocolo, xml, registrada_em, usuario_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)) {
            ps.setInt(1, serie);
            ps.setInt(2, inicial);
            ps.setInt(3, fim);
            ps.setInt(4, ano);
            ps.setString(5, justificativa);
            ps.setString(6, cstat);
            ps.setString(7, motivo);
            ps.setString(8, protocolo);
            ps.setString(9, xml);
            ps.setObject(10, agora);
            ps.setObject(11, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ----- Filas do transmissor (F23.5) -----

    /** PENDENTE reservada antes de {@code limite} e sem trava válida: o sistema caiu no meio da emissão. */
    public List<Long> pendentesAbandonadas(Connection con, OffsetDateTime limite, OffsetDateTime agora)
            throws SQLException {
        return ids(con, """
                SELECT id FROM nfce
                 WHERE status = 'PENDENTE' AND criado_em < ? AND (transmitindo_ate IS NULL OR transmitindo_ate <= ?)
                 ORDER BY id
                """, limite, agora);
    }

    /** As de contingência, a transmitir (as mais antigas primeiro). */
    public List<Long> emContingencia(Connection con) throws SQLException {
        return ids(con, "SELECT id FROM nfce WHERE status = 'CONTINGENCIA' ORDER BY id");
    }

    /** SEM_RESPOSTA cuja substituta (a de contingência da mesma venda) já foi autorizada. */
    public List<Long> semRespostaComSubstitutaAutorizada(Connection con) throws SQLException {
        return ids(con, """
                SELECT n.id FROM nfce n JOIN nfce s ON s.id = n.substituta_id
                 WHERE n.status = 'SEM_RESPOSTA' AND s.status = 'AUTORIZADA'
                 ORDER BY n.id
                """);
    }

    /** REJEITADA de venda cancelada: o número é inutilizado. */
    public List<Long> rejeitadasDeVendaCancelada(Connection con) throws SQLException {
        return ids(con, """
                SELECT n.id FROM nfce n JOIN venda v ON v.id = n.venda_id
                 WHERE n.status = 'REJEITADA' AND v.status = 'CANCELADA'
                 ORDER BY n.id
                """);
    }

    private static List<Long> ids(Connection con, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
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
