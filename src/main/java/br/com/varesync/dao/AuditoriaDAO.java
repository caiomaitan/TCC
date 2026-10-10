package br.com.varesync.dao;

import br.com.varesync.model.AcaoAuditoria;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;

/** Inserção na tabela {@code auditoria}. A consulta (tela de auditoria) entra na Fase 8. */
public class AuditoriaDAO {

    public void inserir(Connection con, Long usuarioId, AcaoAuditoria acao, String entidade, Long entidadeId,
                        String descricao, String terminal, OffsetDateTime criadoEm) throws SQLException {
        String sql = """
                INSERT INTO auditoria (usuario_id, acao, entidade, entidade_id, descricao, terminal, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            definirLong(ps, 1, usuarioId);
            ps.setString(2, acao.name());
            ps.setString(3, entidade);
            definirLong(ps, 4, entidadeId);
            ps.setString(5, descricao);
            ps.setString(6, terminal);
            ps.setObject(7, criadoEm);
            ps.executeUpdate();
        }
    }

    private static void definirLong(PreparedStatement ps, int indice, Long valor) throws SQLException {
        if (valor == null) {
            ps.setNull(indice, Types.BIGINT);
        } else {
            ps.setLong(indice, valor);
        }
    }
}
