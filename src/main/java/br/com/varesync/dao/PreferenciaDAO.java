package br.com.varesync.dao;

import br.com.varesync.model.Preferencias;
import br.com.varesync.model.TamanhoFonte;
import br.com.varesync.model.Tema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/** Preferências de aparência por usuário ({@code usuario_preferencia}, F21.1). */
public class PreferenciaDAO {

    public Optional<Preferencias> buscar(Connection con, long usuarioId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT tema, fonte FROM usuario_preferencia WHERE usuario_id = ?
                """)) {
            ps.setLong(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Preferencias(Tema.valueOf(rs.getString("tema")),
                        TamanhoFonte.dePercentual(rs.getInt("fonte"))));
            }
        }
    }

    /** Grava (cria ou substitui) as preferências do usuário, com o horário do {@code Clock}. */
    public void gravar(Connection con, long usuarioId, Preferencias preferencias, OffsetDateTime agora)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO usuario_preferencia (usuario_id, tema, fonte, atualizado_em)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (usuario_id) DO UPDATE
                   SET tema = EXCLUDED.tema, fonte = EXCLUDED.fonte, atualizado_em = EXCLUDED.atualizado_em
                """)) {
            ps.setLong(1, usuarioId);
            ps.setString(2, preferencias.tema().name());
            ps.setInt(3, preferencias.fonte().percentual());
            ps.setObject(4, agora);
            ps.executeUpdate();
        }
    }
}
