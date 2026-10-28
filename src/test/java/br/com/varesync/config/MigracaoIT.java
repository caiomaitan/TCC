package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

/** Com o banco vazio as migrations V1 a V7 rodam; rodar de novo não aplica nada. */
class MigracaoIT extends IntegracaoBase {

    @Test
    void bancoVazioRecebeV1aV7() throws Exception {
        assertThat(BancoTeste.migrationsNaPreparacao()).isEqualTo(7);

        try (Connection con = dataSource.getConnection()) {
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM flyway_schema_history
                     WHERE success AND version IN ('1', '2', '3', '4', '5', '6', '7')
                    """)).isEqualTo(7);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                    """)).as("tabelas do sistema (24 da V1 + cliente da V6 + crédito do cliente da V7)").isEqualTo(27);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.views
                     WHERE table_schema = 'public'
                       AND table_name IN ('vw_venda_item_rentabilidade', 'vw_estoque_atual',
                                          'vw_estoque_inconsistente', 'vw_caixa_resumo',
                                          'vw_cliente_credito_inconsistente')
                    """)).as("views").isEqualTo(5);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM permissao"))
                    .as("permissões do V2 + CLIENTES da V6").isEqualTo(11);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM forma_pagamento WHERE tipo = 'CREDITO_CLIENTE'"))
                    .as("forma \"Crédito do cliente\" da V7").isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave LIKE 'fidelidade.%'"))
                    .as("configurações da fidelidade da V7").isEqualTo(3);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM usuario"))
                    .as("V2 não cria usuário: o supervisor vem do primeiro acesso").isZero();
        }
    }

    @Test
    void migrarDeNovoNaoAplicaNada() {
        assertThat(new Migracao(dataSource).migrar()).isZero();
    }
}
