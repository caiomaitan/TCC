package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class BancoTest {

    @Test
    void descreveFalhasDeConexaoSemJargao() {
        assertThat(Banco.descreverFalha(new SQLException("x", "28P01"))).isEqualTo("Usuário ou senha do banco inválidos.");
        assertThat(Banco.descreverFalha(new SQLException("x", "3D000"))).startsWith("O banco de dados informado não existe");
        assertThat(Banco.descreverFalha(new SQLException("x", "08001"))).contains("PostgreSQL está em execução");
        assertThat(Banco.descreverFalha(new SQLException("x"))).contains("registrados no log");
    }

    @Test
    void recusaEnderecoForaDoPadrao() {
        assertThatThrownBy(() -> Banco.testarConexao("localhost:5432/varesync", "varesync", ""))
                .isInstanceOf(FalhaConexaoException.class)
                .hasMessageStartingWith("Endereço inválido");
    }

    @Test
    void servidorInexistenteGeraFalhaAmigavel() {
        // Porta 1 em localhost: conexão recusada imediatamente, sem depender de banco instalado.
        assertThatThrownBy(() -> Banco.testarConexao("jdbc:postgresql://127.0.0.1:1/varesync", "varesync", "x"))
                .isInstanceOf(FalhaConexaoException.class)
                .hasMessageContaining("Não foi possível conectar ao servidor do banco")
                .hasCauseInstanceOf(SQLException.class);
    }
}
