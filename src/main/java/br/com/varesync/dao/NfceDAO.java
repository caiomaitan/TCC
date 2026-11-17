package br.com.varesync.dao;

import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.NotaFiscal;
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

    /** CANCELADA pelo evento 110111 (F23.8): só a AUTORIZADA; o protocolo de autorização continua. */
    public boolean cancelada(Connection con, long id, String cstat, String motivo, OffsetDateTime agora)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET status = 'CANCELADA', cstat = ?, motivo = ?, atualizado_em = ?
                 WHERE id = ? AND status = 'AUTORIZADA'
                """)) {
            ps.setString(1, cstat);
            ps.setString(2, motivo);
            ps.setObject(3, agora);
            ps.setLong(4, id);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Corrigir e reenviar (F23.9): a REJEITADA volta a PENDENTE (emissão normal, com a chave e o dhEmi novos) ou a
     * CONTINGENCIA (a de contingência, com a chave e o dhEmi de antes), sem o XML, o QR Code e a resposta anterior.
     */
    public boolean reabrir(Connection con, long id, StatusNfce status, String chave, OffsetDateTime emitidaEm,
                           OffsetDateTime agora) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce SET status = ?, chave = ?, emitida_em = ?, xml = NULL, qrcode = NULL, cstat = NULL,
                                motivo = NULL, atualizado_em = ?
                 WHERE id = ? AND status = 'REJEITADA'
                """)) {
            ps.setString(1, status.name());
            ps.setString(2, chave);
            ps.setObject(3, emitidaEm);
            ps.setObject(4, agora);
            ps.setLong(5, id);
            return ps.executeUpdate() == 1;
        }
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

    // ----- Aba Notas fiscais (F23.9) e painel (F23.10) -----

    /** As colunas da {@link NotaFiscal}, com a venda ({@code n} = nfce, {@code v} = venda). */
    public static final String COLUNAS_NOTA = """
            n.id, n.venda_id, n.serie, n.numero, n.emitida_em, v.valor_total, v.consumidor_documento, n.status,
            n.protocolo, n.cstat, n.motivo, n.chave, n.tipo_emissao, n.xml IS NOT NULL AS tem_xml
            """;

    public static NotaFiscal lerNota(ResultSet rs) throws SQLException {
        return new NotaFiscal(rs.getLong("id"), rs.getLong("venda_id"), rs.getInt("serie"), rs.getInt("numero"),
                rs.getObject("emitida_em", OffsetDateTime.class), rs.getBigDecimal("valor_total"),
                rs.getString("consumidor_documento"), StatusNfce.valueOf(rs.getString("status")),
                rs.getString("protocolo"), NotaFiscal.mensagem(rs.getString("cstat"), rs.getString("motivo")),
                rs.getString("chave"), TipoEmissaoNfce.de(rs.getInt("tipo_emissao")), rs.getBoolean("tem_xml"));
    }

    /**
     * As NFC-e para a aba Notas fiscais, das mais novas para as mais antigas.
     *
     * @param inicio     emitida a partir de (nulo = sem limite)
     * @param fim        emitida antes de (o dia seguinte ao fim do período; nulo = sem limite)
     * @param situacoes  vazio = todas
     * @param numero     nulo = todos
     */
    public List<NotaFiscal> listar(Connection con, OffsetDateTime inicio, OffsetDateTime fim,
                                   java.util.Set<StatusNfce> situacoes, Integer numero) throws SQLException {
        String sql = "SELECT " + COLUNAS_NOTA + """
                  FROM nfce n JOIN venda v ON v.id = n.venda_id
                 WHERE (CAST(? AS timestamptz) IS NULL OR n.emitida_em >= ?)
                   AND (CAST(? AS timestamptz) IS NULL OR n.emitida_em < ?)
                   AND (CAST(? AS integer) IS NULL OR n.numero = ?)
                   AND (cardinality(CAST(? AS varchar[])) = 0 OR n.status = ANY (CAST(? AS varchar[])))
                 ORDER BY n.emitida_em DESC, n.id DESC
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, inicio);
            ps.setObject(2, inicio);
            ps.setObject(3, fim);
            ps.setObject(4, fim);
            ps.setObject(5, numero, java.sql.Types.INTEGER);
            ps.setObject(6, numero, java.sql.Types.INTEGER);
            java.sql.Array array = con.createArrayOf("varchar", situacoes.stream().map(Enum::name).toArray());
            ps.setArray(7, array);
            ps.setArray(8, array);
            List<NotaFiscal> lista = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(lerNota(rs));
                }
            }
            return lista;
        }
    }

    /** Quantas NFC-e (em qualquer situação) usam números da faixa da série. */
    public int contarNaFaixa(Connection con, int serie, int inicial, int fim) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(*) FROM nfce WHERE serie = ? AND numero BETWEEN ? AND ?
                """)) {
            ps.setInt(1, serie);
            ps.setInt(2, inicial);
            ps.setInt(3, fim);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Se algum número da faixa da série já foi inutilizado. */
    public boolean faixaJaInutilizada(Connection con, int serie, int inicial, int fim) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM nfce_inutilizacao
                                WHERE serie = ? AND numero_inicial <= ? AND numero_final >= ?)
                """)) {
            ps.setInt(1, serie);
            ps.setInt(2, fim);
            ps.setInt(3, inicial);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** A emissão da primeira NFC-e da série com número depois de {@code numero} (quando a numeração passou dele). */
    public Optional<OffsetDateTime> primeiraEmitidaDepois(Connection con, int serie, int numero)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT emitida_em FROM nfce WHERE serie = ? AND numero > ? ORDER BY numero LIMIT 1
                """)) {
            ps.setInt(1, serie);
            ps.setInt(2, numero);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, OffsetDateTime.class)) : Optional.empty();
            }
        }
    }

    /** Um XML do ZIP do contador (F23.9). */
    public record XmlGuardado(String nome, String xml) {
    }

    /**
     * Os XML do mês para o contador: as notas autorizadas, canceladas e substituídas emitidas no período (o nfeProc),
     * os eventos registrados no período (o procEventoNFe) e as inutilizações do período (o procInutNFe).
     */
    public List<XmlGuardado> xmlsDoPeriodo(Connection con, OffsetDateTime inicio, OffsetDateTime fim)
            throws SQLException {
        List<XmlGuardado> xmls = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT chave, xml FROM nfce
                 WHERE status IN ('AUTORIZADA', 'CANCELADA', 'SUBSTITUIDA') AND xml IS NOT NULL
                   AND emitida_em >= ? AND emitida_em < ?
                 ORDER BY serie, numero
                """)) {
            ps.setObject(1, inicio);
            ps.setObject(2, fim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    xmls.add(new XmlGuardado("NFC-e/" + rs.getString(1) + "-procNFe.xml", rs.getString(2)));
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT n.chave, e.tipo, e.id, e.xml FROM nfce_evento e JOIN nfce n ON n.id = e.nfce_id
                 WHERE e.xml IS NOT NULL AND e.registrado_em >= ? AND e.registrado_em < ?
                 ORDER BY e.id
                """)) {
            ps.setObject(1, inicio);
            ps.setObject(2, fim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String codigo = "CANCELAMENTO".equals(rs.getString(2)) ? "110111" : "110112";
                    xmls.add(new XmlGuardado("Eventos/" + rs.getString(1) + "-" + codigo + "-" + rs.getLong(3)
                            + "-procEventoNFe.xml", rs.getString(4)));
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT serie, numero_inicial, numero_final, id, xml FROM nfce_inutilizacao
                 WHERE xml IS NOT NULL AND registrada_em >= ? AND registrada_em < ?
                 ORDER BY id
                """)) {
            ps.setObject(1, inicio);
            ps.setObject(2, fim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    xmls.add(new XmlGuardado(String.format("Inutilizacoes/serie%03d-%09d-%09d-%d-procInutNFe.xml",
                            rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)), rs.getString(5)));
                }
            }
        }
        return xmls;
    }

    /**
     * Alertas do painel (F23.10).
     *
     * @param aTransmitir         em contingência ou sem resposta
     * @param aTransmitirAntigas  dessas, as emitidas antes de hoje
     * @param rejeitadas          rejeitadas de venda finalizada (a corrigir; as de venda cancelada o transmissor
     *                            inutiliza)
     */
    public record Pendentes(int aTransmitir, int aTransmitirAntigas, int rejeitadas) {
    }

    public Pendentes pendentes(Connection con, OffsetDateTime inicioDeHoje) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT count(*) FILTER (WHERE n.status IN ('CONTINGENCIA', 'SEM_RESPOSTA')),
                       count(*) FILTER (WHERE n.status IN ('CONTINGENCIA', 'SEM_RESPOSTA') AND n.emitida_em < ?),
                       count(*) FILTER (WHERE n.status = 'REJEITADA' AND v.status = 'FINALIZADA')
                  FROM nfce n JOIN venda v ON v.id = n.venda_id
                 WHERE n.status IN ('CONTINGENCIA', 'SEM_RESPOSTA', 'REJEITADA')
                """)) {
            ps.setObject(1, inicioDeHoje);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Pendentes(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
        }
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
