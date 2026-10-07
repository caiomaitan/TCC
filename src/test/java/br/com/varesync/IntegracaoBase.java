package br.com.varesync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/** Base dos testes de integração: banco de teste migrado e estoque consistente ao fim de cada teste (9.2). */
public abstract class IntegracaoBase {

    protected static DataSource dataSource;

    @BeforeAll
    static void prepararBanco() {
        dataSource = BancoTeste.dataSource();
    }

    @AfterEach
    void estoqueContinuaConsistente() throws Exception {
        try (Connection con = dataSource.getConnection()) {
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_estoque_inconsistente"))
                    .as("vw_estoque_inconsistente deve estar vazia")
                    .isZero();
        }
    }
}
