package br.com.varesync.dao;

import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.RegistroAuditoria;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Tabela {@code auditoria}: inserção, consulta da tela de auditoria (6.13) e último registro de uma ação. */
public class AuditoriaDAO {

    /**
     * Filtro da consulta: {@code criado_em >= inicio AND criado_em < fim}; usuário e ação nulos = todos.
     *
     * @param limite quantidade máxima de linhas (as mais recentes)
     */
    public record Filtro(OffsetDateTime inicio, OffsetDateTime fim, Long usuarioId, AcaoAuditoria acao, int limite) {
    }

    public void inserir(Connection con, Long usuarioId, AcaoAuditoria acao, String entidade, Long entidadeId,
                        String descricao, String terminal, OffsetDateTime criadoEm) throws SQLException {
        String sql = """
                INSERT INTO auditoria (usuario_id, acao, entidade, entidade_id, descricao, terminal, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            Jdbc.definirLong(ps, 1, usuarioId);
            ps.setString(2, acao.name());
            ps.setString(3, entidade);
            Jdbc.definirLong(ps, 4, entidadeId);
            ps.setString(5, descricao);
            ps.setString(6, terminal);
            ps.setObject(7, criadoEm);
            ps.executeUpdate();
        }
    }

    /** Registros do filtro, do mais recente para o mais antigo, até {@code filtro.limite()}. */
    public List<RegistroAuditoria> consultar(Connection con, Filtro filtro) throws SQLException {
        String sql = """
                SELECT a.id, a.criado_em, u.nome, u.login, a.acao, a.entidade, a.entidade_id, a.descricao, a.terminal
                  FROM auditoria a
                  LEFT JOIN usuario u ON u.id = a.usuario_id
                 WHERE a.criado_em >= ?
                   AND a.criado_em < ?
                   AND (CAST(? AS BIGINT) IS NULL OR a.usuario_id = ?)
                   AND (CAST(? AS TEXT) IS NULL OR a.acao = ?)
                 ORDER BY a.criado_em DESC, a.id DESC
                 LIMIT ?
                """;
        List<RegistroAuditoria> registros = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, filtro.inicio());
            ps.setObject(2, filtro.fim());
            Jdbc.definirLong(ps, 3, filtro.usuarioId());
            Jdbc.definirLong(ps, 4, filtro.usuarioId());
            String acao = filtro.acao() == null ? null : filtro.acao().name();
            ps.setString(5, acao);
            ps.setString(6, acao);
            ps.setInt(7, filtro.limite());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nome = rs.getString("nome");
                    Long entidade = Jdbc.lerLong(rs, "entidade_id");
                    registros.add(new RegistroAuditoria(rs.getLong("id"),
                            rs.getObject("criado_em", OffsetDateTime.class),
                            nome == null ? null : nome + " (" + rs.getString("login") + ")",
                            AcaoAuditoria.valueOf(rs.getString("acao")), rs.getString("entidade"), entidade,
                            rs.getString("descricao"), rs.getString("terminal")));
                }
            }
        }
        return registros;
    }

    /** Data e hora do registro mais recente de uma ação (ex.: o último backup). */
    public Optional<OffsetDateTime> ultimo(Connection con, AcaoAuditoria acao) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT max(criado_em) FROM auditoria WHERE acao = ?")) {
            ps.setString(1, acao.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, OffsetDateTime.class));
            }
        }
    }
}
