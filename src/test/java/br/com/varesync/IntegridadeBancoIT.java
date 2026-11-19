package br.com.varesync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.service.DadosTela;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Executa {@code scripts/teste_integridade_banco.sql} (15 cenários) no banco de teste (5.5).
 * O script controla a própria transação com BEGIN/ROLLBACK; aqui o bloco DO é executado dentro de uma
 * transação JDBC que termina em rollback, o mesmo efeito sem misturar os dois controles de transação.
 *
 * <p>O script parte do banco limpo (o terminal CAIXA-TESTE sem caixa aberto, por exemplo); como os outros testes de
 * integração limpam o banco antes de cada teste, e não depois, este também limpa antes (2.0.1: na ordem aleatória do
 * Failsafe, um caixa aberto deixado por outra classe fazia o cenário 6 falhar).
 */
class IntegridadeBancoIT extends IntegracaoBase {

    private static final Path SCRIPT = Path.of("scripts", "teste_integridade_banco.sql");
    private static final int CENARIOS = 15;

    @BeforeEach
    void limparBanco() throws Exception {
        new DadosTela(dataSource).limpar();
    }

    @Test
    void scriptDeIntegridadePassaNosQuinzeCenarios() throws Exception {
        String bloco = extrairBlocoDo(Files.readString(SCRIPT, StandardCharsets.UTF_8));
        List<String> avisos = new ArrayList<>();

        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute(bloco); // ASSERT que falhar vira PSQLException com a mensagem do cenário
                for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
                    avisos.add(w.getMessage());
                }
            } finally {
                con.rollback();
                con.setAutoCommit(true);
            }

            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM usuario WHERE login = 'teste.sup'"))
                    .as("o script não pode deixar dados gravados")
                    .isZero();
        }

        for (int i = 1; i <= CENARIOS; i++) {
            String prefixo = "OK " + i + " ";
            assertThat(avisos).as("cenário %d", i).anyMatch(a -> a.startsWith(prefixo));
        }
        assertThat(avisos).contains("Todos os 15 cenários passaram.");
    }

    /** Trecho de "DO $$" até "END $$;" — o script em si não é alterado. */
    static String extrairBlocoDo(String script) {
        int inicio = script.indexOf("DO $$");
        int fim = script.lastIndexOf("END $$;");
        assertThat(inicio).as("bloco DO no script").isNotNegative();
        assertThat(fim).as("fim do bloco DO no script").isGreaterThan(inicio);
        return script.substring(inicio, fim + "END $$;".length());
    }
}
