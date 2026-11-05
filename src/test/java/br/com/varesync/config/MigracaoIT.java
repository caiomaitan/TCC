package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

/** Com o banco vazio as migrations V1 a V13 rodam; rodar de novo não aplica nada. */
class MigracaoIT extends IntegracaoBase {

    @Test
    void bancoVazioRecebeV1aV14() throws Exception {
        assertThat(BancoTeste.migrationsNaPreparacao()).isEqualTo(14);

        try (Connection con = dataSource.getConnection()) {
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM flyway_schema_history
                     WHERE success AND version IN ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13',
                                                   '14')
                    """)).isEqualTo(14);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                    """)).as("tabelas (24 da V1, cliente da V6, crédito da V7, devoluções da V8, crediário da V10 e "
                    + "devolucao_titulo da V11; 8 do financeiro da V12; produto_fornecedor da V13; pedido_compra e "
                    + "pedido_compra_item da V14)")
                    .isEqualTo(45);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.views
                     WHERE table_schema = 'public'
                       AND table_name IN ('vw_venda_item_rentabilidade', 'vw_estoque_atual',
                                          'vw_estoque_inconsistente', 'vw_caixa_resumo',
                                          'vw_cliente_credito_inconsistente', 'vw_resultado_item',
                                          'vw_abatimento_inconsistente', 'vw_conta_inconsistente')
                    """)).as("views (vw_resultado_item da V9, vw_abatimento_inconsistente da V11, vw_conta_inconsistente da V12)").isEqualTo(8);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM permissao"))
                    .as("permissões do V2 + CLIENTES da V6 + DEVOLUCAO da V8 + RECEBIMENTO e LIMITE_CREDITO da V10 + FINANCEIRO da V12")
                    .isEqualTo(15);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM forma_pagamento WHERE tipo = 'CREDIARIO'"))
                    .as("forma \"Crediário\" da V10").isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave LIKE 'crediario.%'"))
                    .as("configurações do crediário da V10").isEqualTo(6);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave = 'devolucao.prazo_dias'"))
                    .as("prazo de devolução da V8").isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM forma_pagamento WHERE tipo = 'CREDITO_CLIENTE'"))
                    .as("forma \"Crédito do cliente\" da V7").isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave LIKE 'fidelidade.%'"))
                    .as("configurações da fidelidade da V7").isEqualTo(3);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave LIKE 'compras.%'"))
                    .as("configurações da sugestão de compra da V13").isEqualTo(2);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM conta_financeira")).as("Cofre e Banco da V12")
                    .isEqualTo(2);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM categoria_despesa")).as("categorias da V12")
                    .isEqualTo(12);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM forma_pagamento WHERE conta_destino_id IS NOT NULL"))
                    .as("formas da V2 com conta de destino (menos Dinheiro, Crédito do cliente e Crediário)").isEqualTo(4);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM usuario"))
                    .as("V2 não cria usuário: o supervisor vem do primeiro acesso").isZero();
        }
    }

    @Test
    void migrarDeNovoNaoAplicaNada() {
        assertThat(new Migracao(dataSource).migrar()).isZero();
    }
}
