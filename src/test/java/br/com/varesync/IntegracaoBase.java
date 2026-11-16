package br.com.varesync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base dos testes de integração: banco de teste migrado e, ao fim de cada teste, estoque (9.2) e crédito dos clientes
 * (F12.3) consistentes.
 */
public abstract class IntegracaoBase {

    protected static DataSource dataSource;

    @BeforeAll
    static void prepararBanco() {
        dataSource = BancoTeste.dataSource();
    }

    @AfterEach
    protected void estoqueContinuaConsistente() throws Exception {
        try (Connection con = dataSource.getConnection()) {
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_estoque_inconsistente"))
                    .as("vw_estoque_inconsistente deve estar vazia")
                    .isZero();
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_cliente_credito_inconsistente"))
                    .as("vw_cliente_credito_inconsistente deve estar vazia (0.2 das ampliações)")
                    .isZero();
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_abatimento_inconsistente"))
                    .as("vw_abatimento_inconsistente deve estar vazia (F14.4)")
                    .isZero();
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_conta_inconsistente"))
                    .as("vw_conta_inconsistente deve estar vazia (0.2 das ampliações)")
                    .isZero();
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_lote_inconsistente"))
                    .as("vw_lote_inconsistente deve estar vazia (F17.1)")
                    .isZero();
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_nfce_inconsistente"))
                    .as("vw_nfce_inconsistente deve estar vazia (F23.1)")
                    .isZero();
        }
    }
}
