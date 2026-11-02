package br.com.varesync.dao;

import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaResumo;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.StatusCaixa;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.VendasPorForma;
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
 * Sessões de caixa ({@code caixa_sessao}), sangrias e suprimentos ({@code caixa_movimento}) e o resumo de
 * {@code vw_caixa_resumo}. A coluna {@code diferenca} é gerada pelo banco e nunca é escrita.
 */
public class CaixaDAO {

    private static final String SELECT_SESSAO = """
            SELECT s.id, s.terminal, s.status, s.usuario_id, ua.nome AS aberto_por_nome, s.aberto_em,
                   s.valor_abertura, uf.nome AS fechado_por_nome, s.fechado_em, s.valor_esperado,
                   s.valor_contado, s.diferenca, s.conta_origem_id, s.conta_destino_id
              FROM caixa_sessao s
              JOIN usuario ua ON ua.id = s.usuario_id
              LEFT JOIN usuario uf ON uf.id = s.fechado_por
            """;

    /** @return id da sessão criada */
    public long abrir(Connection con, String terminal, long usuarioId, BigDecimal valorAbertura,
                      OffsetDateTime abertoEm, Long contaOrigemId) throws SQLException {
        String sql = """
                INSERT INTO caixa_sessao (terminal, usuario_id, aberto_em, valor_abertura, status, conta_origem_id)
                VALUES (?, ?, ?, ?, 'ABERTO', ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, terminal);
            ps.setLong(2, usuarioId);
            ps.setObject(3, abertoEm);
            ps.setBigDecimal(4, valorAbertura);
            Jdbc.definirLong(ps, 5, contaOrigemId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Optional<CaixaSessao> buscar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_SESSAO + " WHERE s.id = ?")) {
            ps.setLong(1, id);
            return uma(ps);
        }
    }

    /** Sessão aberta no terminal (no máximo uma, por {@code ux_caixa_aberto_por_terminal}). */
    public Optional<CaixaSessao> buscarAberta(Connection con, String terminal) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_SESSAO
                + " WHERE s.terminal = ? AND s.status = 'ABERTO'")) {
            ps.setString(1, terminal);
            return uma(ps);
        }
    }

    /**
     * Sessão aberta no terminal com a linha travada até o fim da transação: sangria, fechamento e venda não correm
     * em paralelo na mesma sessão.
     */
    public Optional<CaixaSessao> travarAberta(Connection con, String terminal) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_SESSAO
                + " WHERE s.terminal = ? AND s.status = 'ABERTO' FOR UPDATE OF s")) {
            ps.setString(1, terminal);
            return uma(ps);
        }
    }

    /** Última sessão fechada no terminal. */
    public Optional<CaixaSessao> buscarUltimaFechada(Connection con, String terminal) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_SESSAO
                + " WHERE s.terminal = ? AND s.status = 'FECHADO' ORDER BY s.fechado_em DESC, s.id DESC LIMIT 1")) {
            ps.setString(1, terminal);
            return uma(ps);
        }
    }

    public CaixaResumo resumo(Connection con, long caixaSessaoId) throws SQLException {
        String sql = """
                SELECT caixa_sessao_id, valor_abertura, suprimentos, sangrias, vendas_dinheiro, qtd_vendas,
                       total_vendido, qtd_canceladas, dinheiro_esperado, devolucoes_dinheiro, recebimentos_dinheiro
                  FROM vw_caixa_resumo
                 WHERE caixa_sessao_id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Sessão de caixa " + caixaSessaoId + " não está em vw_caixa_resumo");
                }
                return new CaixaResumo(rs.getLong("caixa_sessao_id"), rs.getBigDecimal("valor_abertura"),
                        rs.getBigDecimal("suprimentos"), rs.getBigDecimal("sangrias"),
                        rs.getBigDecimal("vendas_dinheiro"), rs.getLong("qtd_vendas"),
                        rs.getBigDecimal("total_vendido"), rs.getLong("qtd_canceladas"),
                        rs.getBigDecimal("dinheiro_esperado"), rs.getBigDecimal("devolucoes_dinheiro"),
                        rs.getBigDecimal("recebimentos_dinheiro"));
            }
        }
    }

    /** @return id do movimento */
    public long inserirMovimento(Connection con, long caixaSessaoId, TipoCaixaMovimento tipo, BigDecimal valor,
                                 String motivo, long usuarioId, OffsetDateTime criadoEm, Long contaId)
            throws SQLException {
        String sql = """
                INSERT INTO caixa_movimento (caixa_sessao_id, tipo, valor, motivo, usuario_id, criado_em, conta_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            ps.setString(2, tipo.name());
            ps.setBigDecimal(3, valor);
            ps.setString(4, motivo);
            ps.setLong(5, usuarioId);
            ps.setObject(6, criadoEm);
            Jdbc.definirLong(ps, 7, contaId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Sangrias e suprimentos da sessão, na ordem em que foram registrados. */
    public List<CaixaMovimento> listarMovimentos(Connection con, long caixaSessaoId) throws SQLException {
        String sql = """
                SELECT m.id, m.tipo, m.valor, m.motivo, u.nome AS usuario_nome, m.criado_em
                  FROM caixa_movimento m
                  JOIN usuario u ON u.id = m.usuario_id
                 WHERE m.caixa_sessao_id = ?
                 ORDER BY m.criado_em, m.id
                """;
        List<CaixaMovimento> movimentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movimentos.add(new CaixaMovimento(rs.getLong("id"),
                            TipoCaixaMovimento.valueOf(rs.getString("tipo")), rs.getBigDecimal("valor"),
                            rs.getString("motivo"), rs.getString("usuario_nome"),
                            rs.getObject("criado_em", OffsetDateTime.class)));
                }
            }
        }
        return movimentos;
    }

    /** Grava o fechamento às cegas; só altera se a sessão ainda estiver aberta. @return false se não estava */
    public boolean fechar(Connection con, long caixaSessaoId, long fechadoPor, OffsetDateTime fechadoEm,
                          BigDecimal valorEsperado, BigDecimal valorContado, Long contaDestinoId)
            throws SQLException {
        String sql = """
                UPDATE caixa_sessao
                   SET status = 'FECHADO', fechado_por = ?, fechado_em = ?, valor_esperado = ?, valor_contado = ?,
                       conta_destino_id = ?
                 WHERE id = ? AND status = 'ABERTO'
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, fechadoPor);
            ps.setObject(2, fechadoEm);
            ps.setBigDecimal(3, valorEsperado);
            ps.setBigDecimal(4, valorContado);
            Jdbc.definirLong(ps, 5, contaDestinoId);
            ps.setLong(6, caixaSessaoId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Vendas finalizadas da sessão agrupadas por forma de pagamento, todas as formas usadas, pela descrição. */
    public List<VendasPorForma> vendasPorForma(Connection con, long caixaSessaoId) throws SQLException {
        String sql = """
                SELECT fp.descricao, count(DISTINCT v.id) AS vendas, sum(vp.valor) AS valor
                  FROM venda v
                  JOIN venda_pagamento vp ON vp.venda_id = v.id
                  JOIN forma_pagamento fp ON fp.id = vp.forma_pagamento_id
                 WHERE v.caixa_sessao_id = ? AND v.status = 'FINALIZADA'
                 GROUP BY fp.id, fp.descricao
                 ORDER BY fp.id
                """;
        List<VendasPorForma> formas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    formas.add(new VendasPorForma(rs.getString("descricao"), rs.getLong("vendas"),
                            rs.getBigDecimal("valor")));
                }
            }
        }
        return formas;
    }

    /** Descontos concedidos (nos itens e na venda) nas vendas finalizadas da sessão. */
    public BigDecimal descontos(Connection con, long caixaSessaoId) throws SQLException {
        String sql = """
                SELECT coalesce(sum(desconto_itens + desconto_venda), 0)
                  FROM venda
                 WHERE caixa_sessao_id = ? AND status = 'FINALIZADA'
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, caixaSessaoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private static Optional<CaixaSessao> uma(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(sessao(rs)) : Optional.empty();
        }
    }

    private static CaixaSessao sessao(ResultSet rs) throws SQLException {
        return new CaixaSessao(rs.getLong("id"), rs.getString("terminal"), StatusCaixa.valueOf(rs.getString("status")),
                rs.getLong("usuario_id"), rs.getString("aberto_por_nome"),
                rs.getObject("aberto_em", OffsetDateTime.class), rs.getBigDecimal("valor_abertura"),
                rs.getString("fechado_por_nome"), rs.getObject("fechado_em", OffsetDateTime.class),
                rs.getBigDecimal("valor_esperado"), rs.getBigDecimal("valor_contado"), rs.getBigDecimal("diferenca"),
                Jdbc.lerLong(rs, "conta_origem_id"), Jdbc.lerLong(rs, "conta_destino_id"));
    }
}
