package br.com.varesync.dao;

import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.InconsistenciaConta;
import br.com.varesync.model.MovimentoConta;
import br.com.varesync.model.TipoConta;
import br.com.varesync.model.TipoContaMovimento;
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
 * Contas financeiras, saldo e razão (F15.1). O saldo ({@code conta_saldo}) só é lido: quem escreve é o trigger do razão,
 * que também preenche {@code saldo_anterior} e {@code saldo_posterior}.
 */
public class ContaFinanceiraDAO {

    private static final String SELECT_CONTA = """
            SELECT c.id, c.nome, c.tipo, c.ativa, c.padrao_caixa, s.saldo
              FROM conta_financeira c
              JOIN conta_saldo s ON s.conta_id = c.id
            """;

    /** Origem do movimento: no máximo uma ({@code ck_cm2_uma_origem}). */
    public record Origem(Long caixaSessaoId, Long caixaMovimentoId, Long recebivelId, Long pagamentoId,
                         Long devolucaoId, Long transferenciaId) {

        public static final Origem NENHUMA = new Origem(null, null, null, null, null, null);

        public static Origem caixaSessao(long id) {
            return new Origem(id, null, null, null, null, null);
        }

        public static Origem caixaMovimento(long id) {
            return new Origem(null, id, null, null, null, null);
        }

        public static Origem recebivel(long id) {
            return new Origem(null, null, id, null, null, null);
        }

        public static Origem pagamento(long id) {
            return new Origem(null, null, null, id, null, null);
        }

        public static Origem devolucao(long id) {
            return new Origem(null, null, null, null, id, null);
        }

        public static Origem transferencia(long id) {
            return new Origem(null, null, null, null, null, id);
        }
    }

    public List<ContaFinanceira> listar(Connection con) throws SQLException {
        List<ContaFinanceira> contas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(SELECT_CONTA + " ORDER BY c.id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                contas.add(ler(rs));
            }
        }
        return contas;
    }

    public Optional<ContaFinanceira> buscar(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_CONTA + " WHERE c.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        }
    }

    public Optional<ContaFinanceira> padraoDoCaixa(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_CONTA + " WHERE c.padrao_caixa");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
        }
    }

    public long inserir(Connection con, String nome, TipoConta tipo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO conta_financeira (nome, tipo) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, nome);
            ps.setString(2, tipo.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Desmarca a conta padrão antes de marcar outra: {@code ux_conta_padrao} aceita só uma. */
    public void desmarcarPadrao(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE conta_financeira SET padrao_caixa = FALSE WHERE padrao_caixa")) {
            ps.executeUpdate();
        }
    }

    public void alterar(Connection con, long id, String nome, TipoConta tipo, boolean ativa, boolean padrao)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE conta_financeira SET nome = ?, tipo = ?, ativa = ?, padrao_caixa = ? WHERE id = ?")) {
            ps.setString(1, nome);
            ps.setString(2, tipo.name());
            ps.setBoolean(3, ativa);
            ps.setBoolean(4, padrao);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** Formas de pagamento que recebem nesta conta (a conta não pode ser inativada enquanto houver). */
    public List<String> formasDeDestino(Connection con, long contaId) throws SQLException {
        List<String> formas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT descricao FROM forma_pagamento WHERE conta_destino_id = ? ORDER BY id")) {
            ps.setLong(1, contaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    formas.add(rs.getString(1));
                }
            }
        }
        return formas;
    }

    /** Número que agrupa as duas pernas de uma transferência. */
    public long proximaTransferencia(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT nextval('conta_transferencia_seq')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public boolean temSaldoInicial(Connection con, long contaId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM conta_movimento WHERE conta_id = ? AND tipo = 'SALDO_INICIAL'")) {
            ps.setLong(1, contaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** O trigger aplica o valor ao saldo. @return id do movimento */
    public long inserirMovimento(Connection con, long contaId, TipoContaMovimento tipo, BigDecimal valor,
                                 OffsetDateTime dataHora, String descricao, long usuarioId, Origem origem)
            throws SQLException {
        String sql = """
                INSERT INTO conta_movimento (conta_id, tipo, valor, saldo_anterior, saldo_posterior, data_hora,
                                             descricao, usuario_id, caixa_sessao_id, caixa_movimento_id, recebivel_id,
                                             titulo_pagar_pagamento_id, devolucao_id, transferencia_id)
                VALUES (?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, contaId);
            ps.setString(2, tipo.name());
            ps.setBigDecimal(3, valor);
            ps.setObject(4, dataHora);
            ps.setString(5, descricao);
            ps.setLong(6, usuarioId);
            Jdbc.definirLong(ps, 7, origem.caixaSessaoId());
            Jdbc.definirLong(ps, 8, origem.caixaMovimentoId());
            Jdbc.definirLong(ps, 9, origem.recebivelId());
            Jdbc.definirLong(ps, 10, origem.pagamentoId());
            Jdbc.definirLong(ps, 11, origem.devolucaoId());
            Jdbc.definirLong(ps, 12, origem.transferenciaId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Extrato de um período, do mais antigo para o mais novo. */
    public List<MovimentoConta> extrato(Connection con, long contaId, OffsetDateTime inicio, OffsetDateTime fimExclusivo)
            throws SQLException {
        String sql = """
                SELECT m.id, m.data_hora, m.tipo, m.valor, m.saldo_posterior, m.descricao, u.nome AS usuario
                  FROM conta_movimento m
                  JOIN usuario u ON u.id = m.usuario_id
                 WHERE m.conta_id = ? AND m.data_hora >= ? AND m.data_hora < ?
                 ORDER BY m.id
                """;
        List<MovimentoConta> movimentos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, contaId);
            ps.setObject(2, inicio);
            ps.setObject(3, fimExclusivo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    movimentos.add(new MovimentoConta(rs.getLong("id"),
                            rs.getObject("data_hora", OffsetDateTime.class),
                            TipoContaMovimento.valueOf(rs.getString("tipo")), rs.getBigDecimal("valor"),
                            rs.getBigDecimal("saldo_posterior"), rs.getString("descricao"), rs.getString("usuario")));
                }
            }
        }
        return movimentos;
    }

    public List<InconsistenciaConta> inconsistencias(Connection con) throws SQLException {
        String sql = """
                SELECT v.conta_id, c.nome, v.saldo_registrado, v.saldo_pelos_movimentos
                  FROM vw_conta_inconsistente v
                  JOIN conta_financeira c ON c.id = v.conta_id
                 ORDER BY v.conta_id
                """;
        List<InconsistenciaConta> linhas = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                linhas.add(new InconsistenciaConta(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)));
            }
        }
        return linhas;
    }

    private static ContaFinanceira ler(ResultSet rs) throws SQLException {
        return new ContaFinanceira(rs.getLong("id"), rs.getString("nome"), TipoConta.valueOf(rs.getString("tipo")),
                rs.getBoolean("ativa"), rs.getBoolean("padrao_caixa"), rs.getBigDecimal("saldo"));
    }
}
