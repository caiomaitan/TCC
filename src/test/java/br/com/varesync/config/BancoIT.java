package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.BancoTeste;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** "Testar conexão" (F11.5) contra o servidor de verdade do banco de teste. */
class BancoIT {

    @Test
    void testarConexaoDevolveAVersaoDoServidorEOTempo() throws Exception {
        BancoTeste.Credenciais cr = BancoTeste.credenciais();
        ConexaoTestada testada = Banco.testarConexao(cr.url(), cr.usuario(), cr.senha());

        String esperada;
        try (Connection con = BancoTeste.dataSource().getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SHOW server_version")) {
            rs.next();
            esperada = Banco.versaoCurta(rs.getString(1));
        }
        assertThat(testada.versaoServidor()).isEqualTo(esperada).matches("\\d+(\\.\\d+)*");
        assertThat(testada.milissegundos()).isBetween(0L, 10_000L);
        assertThat(testada.descricao()).startsWith("Conexão bem-sucedida: PostgreSQL " + esperada + ", resposta em ")
                .endsWith(" ms.");
    }

    /** No CI o servidor está em "trust" e aceita qualquer senha: lá o teste não se aplica. */
    @Test
    void senhaErradaContinuaComAMensagemDeSempre() {
        BancoTeste.Credenciais cr = BancoTeste.credenciais();
        boolean aceitaQualquerSenha;
        try {
            Banco.testarConexao(cr.url(), cr.usuario(), cr.senha() + "-errada");
            aceitaQualquerSenha = true;
        } catch (FalhaConexaoException esperada) {
            aceitaQualquerSenha = false;
        }
        Assumptions.assumeFalse(aceitaQualquerSenha, "servidor em trust: a senha não é conferida");
        assertThatThrownBy(() -> Banco.testarConexao(cr.url(), cr.usuario(), cr.senha() + "-errada"))
                .isInstanceOf(FalhaConexaoException.class)
                .hasMessage("Usuário ou senha do banco inválidos.");
    }
}
