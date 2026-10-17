package br.com.varesync.dao;

import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.TipoFormaPagamento;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Formas de pagamento ({@code forma_pagamento}). O cadastro (criar, ativar/desativar) é de Configurações. */
public class FormaPagamentoDAO {

    /** Formas ativas, na ordem de cadastro (Dinheiro primeiro, pela V2). */
    public List<FormaPagamento> listarAtivas(Connection con) throws SQLException {
        String sql = """
                SELECT id, descricao, tipo
                  FROM forma_pagamento
                 WHERE ativa
                 ORDER BY id
                """;
        List<FormaPagamento> formas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                formas.add(new FormaPagamento(rs.getInt("id"), rs.getString("descricao"),
                        TipoFormaPagamento.valueOf(rs.getString("tipo"))));
            }
        }
        return formas;
    }
}
