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
    void versaoDoServidorSemOTextoDoEmpacotamento() {
        assertThat(Banco.versaoCurta("16.4 (Debian 16.4-1.pgdg120+1)")).isEqualTo("16.4");
        assertThat(Banco.versaoCurta(" 16.10 ")).isEqualTo("16.10");
        assertThat(Banco.versaoCurta(null)).isEqualTo("?");
    }

    @Test
    void testarConexaoMostraVersaoETempo() {
        assertThat(new ConexaoTestada("16.4", 12).descricao())
                .isEqualTo("Conexão bem-sucedida: PostgreSQL 16.4, resposta em 12 ms.");
    }

    @Test
    void recusaEnderecoForaDoPadrao() {
        assertThatThrownBy(() -> Banco.testarConexao("localhost:5432/varesync", "varesync", ""))
                .isInstanceOf(FalhaConexaoException.class)
                .hasMessageStartingWith("Endereço inválido");
    }

    /**
     * Proteção da banca (2.0.0): o varesync_demo é recusado pelo nome, antes de conectar. O servidor é um nome
     * {@code .invalid} (RFC 6761, nunca existe): se a recusa falhasse, a mensagem seria a de servidor fora do ar, e
     * nenhum banco de verdade seria aberto.
     */
    @Test
    void bancoDaBancaERecusadoSemConectar() {
        for (String url : new String[] {
                "jdbc:postgresql://banca.invalid:5432/varesync_demo",
                "jdbc:postgresql://banca.invalid:5432/VARESYNC_DEMO",
                "jdbc:postgresql://banca.invalid/varesync_demo?ApplicationName=x",
                "jdbc:postgresql://banca.invalid:5432/varesync%5Fdemo"}) {
            assertThatThrownBy(() -> Banco.testarConexao(url, "varesync", "x"))
                    .as(url)
                    .isInstanceOf(FalhaConexaoException.class)
                    .hasMessage(Banco.MENSAGEM_BANCO_DA_BANCA)
                    .hasNoCause();
        }
        assertThat(Banco.MENSAGEM_BANCO_DA_BANCA).contains("varesync_demo", "TCC", "Informe outro banco");
    }

    @Test
    void nomeDoBancoNoFimDoEndereco() {
        assertThat(Banco.nomeDoBanco("jdbc:postgresql://localhost:5432/varesync")).isEqualTo("varesync");
        assertThat(Banco.nomeDoBanco("jdbc:postgresql://localhost/varesync_amostra?ssl=false"))
                .isEqualTo("varesync_amostra");
        assertThat(Banco.nomeDoBanco("jdbc:postgresql://localhost:5432")).isEmpty();
        assertThat(Banco.ehOBancoDaBanca("varesync_demo")).isTrue();
        assertThat(Banco.ehOBancoDaBanca("varesync_demo2")).isFalse();
        assertThat(Banco.ehOBancoDaBanca("varesync_amostra")).isFalse();
        assertThat(Banco.ehOBancoDaBanca(null)).isFalse();
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
