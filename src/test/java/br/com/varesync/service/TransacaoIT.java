package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Commit, rollback e tradução de erros reais do PostgreSQL pela {@link Transacao}. */
class TransacaoIT extends IntegracaoBase {

    private Transacao transacao;

    @BeforeEach
    void criar() {
        transacao = new Transacao(dataSource);
    }

    @AfterEach
    void limpar() throws SQLException {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM usuario WHERE login LIKE 'tx.%'")) {
            ps.executeUpdate();
        }
    }

    private static long inserirUsuario(Connection con, String login) throws SQLException {
        String sql = """
                INSERT INTO usuario (login, nome, senha_hash)
                VALUES (?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, login);
            ps.setString(2, "Usuário " + login);
            ps.setString(3, "hash-apenas-para-teste");
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long contarLogin(String login) throws SQLException {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM usuario WHERE login = ?")) {
            ps.setString(1, login);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void commitGravaEDevolveOResultado() throws Exception {
        long id = transacao.executar(con -> inserirUsuario(con, "tx.commit"));

        assertThat(id).isPositive();
        assertThat(contarLogin("tx.commit")).isEqualTo(1);
    }

    @Test
    void negocioFazRollbackEPassaSemAlteracao() throws Exception {
        NegocioException regra = new NegocioException("Regra violada no meio da operação");

        assertThatThrownBy(() -> transacao.executar(con -> {
            inserirUsuario(con, "tx.rollback");
            throw regra;
        })).isSameAs(regra);

        assertThat(contarLogin("tx.rollback")).isZero();
    }

    @Test
    void unicoViraMensagemDaConstraintEDesfazTudo() throws Exception {
        assertThatThrownBy(() -> transacao.executar(con -> {
            inserirUsuario(con, "tx.primeiro");
            inserirUsuario(con, "tx.repetido");
            return inserirUsuario(con, "tx.repetido");
        })).isInstanceOf(NegocioException.class)
                .hasMessage("Este login já está em uso por outro usuário");

        assertThat(contarLogin("tx.primeiro")).as("rollback de toda a transação").isZero();
    }

    @Test
    void checkViraMensagemDaConstraint() {
        assertThatThrownBy(() -> transacao.executar(con -> inserirUsuario(con, "Maria Silva")))
                .isInstanceOf(NegocioException.class)
                .hasMessageStartingWith("O login deve ter de 3 a 30 caracteres");
    }

    @Test
    void triggerViraAMensagemDoBanco() {
        assertThatThrownBy(() -> transacao.executar(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO produto_estoque (produto_id, quantidade) VALUES (?, ?)")) {
                ps.setLong(1, 1);
                ps.setInt(2, 99);
                return ps.executeUpdate();
            }
        })).isInstanceOf(NegocioException.class)
                .hasMessage("O estoque só pode ser alterado registrando um movimento de estoque.");
    }

    @Test
    void erroDeSqlSemTraducaoEhInesperado() {
        assertThatThrownBy(() -> transacao.executar(con -> BancoTeste.contar(con, "SELECT count(*) FROM tabela_inexistente")))
                .isInstanceOf(ErroInesperadoException.class)
                .hasMessage(ErroInesperadoException.MENSAGEM);
    }

    @Test
    void conexaoVoltaAoPoolUtilizavelDepoisDoErro() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> transacao.executar(con -> {
                throw new NegocioException("falha");
            })).isInstanceOf(NegocioException.class);
        }
        long resultado = transacao.executar(con -> BancoTeste.contar(con, "SELECT 1"));
        assertThat(resultado).isEqualTo(1);
    }
}
