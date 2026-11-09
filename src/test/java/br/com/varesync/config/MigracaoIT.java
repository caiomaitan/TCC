package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

/** Com o banco vazio as migrations V1 a V19 rodam; rodar de novo não aplica nada. */
class MigracaoIT extends IntegracaoBase {

    @Test
    void bancoVazioRecebeV1aV19() throws Exception {
        assertThat(BancoTeste.migrationsNaPreparacao()).isEqualTo(19);

        try (Connection con = dataSource.getConnection()) {
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM flyway_schema_history
                     WHERE success AND version IN ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13',
                                                   '14', '15', '16', '17', '18', '19')
                    """)).isEqualTo(19);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                    """)).as("tabelas (24 da V1, cliente da V6, crédito da V7, devoluções da V8, crediário da V10 e "
                    + "devolucao_titulo da V11; 8 do financeiro da V12; produto_fornecedor da V13; pedido_compra e "
                    + "pedido_compra_item da V14; lote, lote_saldo, lote_movimento, lote_transferencia e "
                    + "entrada_mercadoria_item_lote da V15; etiqueta_impressao da V16; carga_balanca e carga_balanca_item da V17; perfil_tributario, ncm e cest da V18; produto_preco_quantidade da V19)")
                    .isEqualTo(57);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.views
                     WHERE table_schema = 'public'
                       AND table_name IN ('vw_venda_item_rentabilidade', 'vw_estoque_atual',
                                          'vw_estoque_inconsistente', 'vw_caixa_resumo',
                                          'vw_cliente_credito_inconsistente', 'vw_resultado_item',
                                          'vw_abatimento_inconsistente', 'vw_conta_inconsistente',
                                          'vw_lote_inconsistente')
                    """)).as("views (vw_resultado_item da V9, vw_abatimento_inconsistente da V11, vw_conta_inconsistente da V12, "
                    + "vw_lote_inconsistente da V15)").isEqualTo(9);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM permissao"))
                    .as("permissões do V2 + CLIENTES da V6 + DEVOLUCAO da V8 + RECEBIMENTO e LIMITE_CREDITO da V10 + FINANCEIRO da V12 + FISCAL da V18")
                    .isEqualTo(16);
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
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave = 'validade.alerta_dias' "
                    + "AND valor = '7'")).as("alerta de validade da V15").isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave LIKE 'etiqueta.%'"))
                    .as("configurações das etiquetas da V16").isEqualTo(4);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_name = "
                    + "'produto' AND column_name IN ('conteudo_quantidade', 'conteudo_unidade')"))
                    .as("conteúdo líquido do produto da V16").isEqualTo(2);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave = 'balanca.pasta_carga' "
                    + "AND valor = ''")).as("pasta da carga de balança da V17").isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_name = "
                    + "'produto' AND column_name = 'validade_balanca_dias'")).as("validade na balança da V17")
                    .isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave IN ('empresa.regime_tributario', "
                    + "'empresa.uf', 'empresa.ie')")).as("dados fiscais da empresa da V18").isEqualTo(3);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_name = "
                    + "'produto' AND column_name IN ('ncm', 'cest', 'origem', 'perfil_tributario_id')"))
                    .as("dados fiscais do produto da V18").isEqualTo(4);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.columns
                     WHERE (table_name, column_name) IN (('produto_codigo_barras', 'quantidade'),
                                                         ('produto_codigo_barras', 'preco_embalagem'),
                                                         ('venda_item', 'preco_quantidade_id'),
                                                         ('etiqueta_impressao', 'atacado_quantidade'),
                                                         ('etiqueta_impressao', 'atacado_preco'))
                    """)).as("embalagens e atacado da V19").isEqualTo(5);
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
