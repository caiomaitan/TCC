package br.com.varesync.dao;

import br.com.varesync.model.UnidadeMedida;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Unidades de medida ({@code unidade_medida}), só leitura nesta versão (vêm da V2). */
public class UnidadeMedidaDAO {

    private static final String COLUNAS = "SELECT sigla, descricao, fracionada FROM unidade_medida";

    /** UN primeiro (padrão do cadastro); as demais pela sigla. */
    public List<UnidadeMedida> listar(Connection con) throws SQLException {
        String sql = COLUNAS + " ORDER BY (sigla <> 'UN'), sigla";
        List<UnidadeMedida> unidades = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                unidades.add(ler(rs));
            }
        }
        return unidades;
    }

    public Optional<UnidadeMedida> buscar(Connection con, String sigla) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(COLUNAS + " WHERE sigla = ?")) {
            ps.setString(1, sigla);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    private static UnidadeMedida ler(ResultSet rs) throws SQLException {
        return new UnidadeMedida(rs.getString("sigla"), rs.getString("descricao"), rs.getBoolean("fracionada"));
    }
}
