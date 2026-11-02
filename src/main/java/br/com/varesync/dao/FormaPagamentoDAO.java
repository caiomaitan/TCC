package br.com.varesync.dao;

import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.TipoFormaPagamento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Formas de pagamento ({@code forma_pagamento}). O cadastro (criar, ativar/desativar) é de Configurações. */
public class FormaPagamentoDAO {

    /** Formas ativas, na ordem de cadastro (Dinheiro primeiro, pela V2). */
    public List<FormaPagamento> listarAtivas(Connection con) throws SQLException {
        List<FormaPagamento> formas = new ArrayList<>();
        for (FormaPagamentoCadastro f : listar(con)) {
            if (f.ativa()) {
                formas.add(new FormaPagamento(f.id(), f.descricao(), f.tipo()));
            }
        }
        return formas;
    }

    /** Todas, ativas e desativadas, na ordem de cadastro. */
    public List<FormaPagamentoCadastro> listar(Connection con) throws SQLException {
        String sql = """
                SELECT id, descricao, tipo, ativa, taxa_pct, dias_recebimento, conta_destino_id
                  FROM forma_pagamento
                 ORDER BY id
                """;
        List<FormaPagamentoCadastro> formas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                formas.add(ler(rs));
            }
        }
        return formas;
    }

    /** Trava todas as formas: ativar/desativar confere "ao menos uma ativa" sem corrida entre dois computadores. */
    public List<FormaPagamentoCadastro> travar(Connection con) throws SQLException {
        String sql = """
                SELECT id, descricao, tipo, ativa, taxa_pct, dias_recebimento, conta_destino_id
                  FROM forma_pagamento
                 ORDER BY id
                   FOR UPDATE
                """;
        List<FormaPagamentoCadastro> formas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                formas.add(ler(rs));
            }
        }
        return formas;
    }

    /** Forma com a mesma descrição, sem diferenciar maiúsculas. */
    public Optional<FormaPagamentoCadastro> buscarPorDescricao(Connection con, String descricao) throws SQLException {
        String sql = """
                SELECT id, descricao, tipo, ativa, taxa_pct, dias_recebimento, conta_destino_id
                  FROM forma_pagamento
                 WHERE lower(descricao) = lower(?)
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, descricao);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    public int inserir(Connection con, String descricao, TipoFormaPagamento tipo) throws SQLException {
        String sql = "INSERT INTO forma_pagamento (descricao, tipo) VALUES (?, ?) RETURNING id";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, descricao);
            ps.setString(2, tipo.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void alterarSituacao(Connection con, int id, boolean ativa) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE forma_pagamento SET ativa = ? WHERE id = ?")) {
            ps.setBoolean(1, ativa);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    /** Forma com o recebimento (taxa, dias e conta de destino, F15.2). */
    public Optional<FormaPagamentoCadastro> buscar(Connection con, int id) throws SQLException {
        String sql = """
                SELECT id, descricao, tipo, ativa, taxa_pct, dias_recebimento, conta_destino_id
                  FROM forma_pagamento
                 WHERE id = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    public void alterarRecebimento(Connection con, int id, BigDecimal taxaPct, int dias, Long contaId)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE forma_pagamento SET taxa_pct = ?, dias_recebimento = ?, conta_destino_id = ? WHERE id = ?")) {
            ps.setBigDecimal(1, taxaPct);
            ps.setInt(2, dias);
            Jdbc.definirLong(ps, 3, contaId);
            ps.setInt(4, id);
            ps.executeUpdate();
        }
    }

    private static FormaPagamentoCadastro ler(ResultSet rs) throws SQLException {
        return new FormaPagamentoCadastro(rs.getInt("id"), rs.getString("descricao"),
                TipoFormaPagamento.valueOf(rs.getString("tipo")), rs.getBoolean("ativa"),
                rs.getBigDecimal("taxa_pct"), rs.getInt("dias_recebimento"), Jdbc.lerLong(rs, "conta_destino_id"));
    }
}
