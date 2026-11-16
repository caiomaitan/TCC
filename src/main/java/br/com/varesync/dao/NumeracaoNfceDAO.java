package br.com.varesync.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Numeração da NFC-e por série (F23.3, tabela {@code nfce_numeracao}). */
public class NumeracaoNfceDAO {

    /**
     * Reserva o próximo número da série: cria a série no primeiro uso, trava a linha ({@code FOR UPDATE}), devolve o
     * número e soma 1. Duas reservas ao mesmo tempo ficam uma atrás da outra e recebem números seguidos.
     */
    public int reservar(Connection con, int serie) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO nfce_numeracao (serie) VALUES (?) ON CONFLICT (serie) DO NOTHING
                """)) {
            ps.setInt(1, serie);
            ps.executeUpdate();
        }
        int numero;
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT proximo_numero FROM nfce_numeracao WHERE serie = ? FOR UPDATE
                """)) {
            ps.setInt(1, serie);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                numero = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = con.prepareStatement("""
                UPDATE nfce_numeracao SET proximo_numero = proximo_numero + 1 WHERE serie = ?
                """)) {
            ps.setInt(1, serie);
            ps.executeUpdate();
        }
        return numero;
    }
}
