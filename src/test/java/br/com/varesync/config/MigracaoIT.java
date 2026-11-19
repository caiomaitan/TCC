package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Com o banco vazio as migrations V1 a V24 rodam; rodar de novo não aplica nada.
 *
 * <p>O estado logo depois das migrations (nenhum usuário, as formas, contas e categorias da V2 e da V12…) é conferido
 * num schema próprio e vazio ({@value #SCHEMA}, no mesmo banco de teste), migrado pela {@link Migracao} do sistema, e
 * apagado no fim. Antes da 2.0.1 a conferência era feita no schema {@code public}, compartilhado: só passava se esta
 * classe rodasse antes das que gravam dados (a ordem alfabética do Windows); na ordem aleatória do Failsafe, os
 * usuários de outra classe já estavam lá.
 */
class MigracaoIT extends IntegracaoBase {

    static final String SCHEMA = "migracao_it";
    private static PGSimpleDataSource vazio;

    @BeforeAll
    static void criarSchemaVazio() throws SQLException {
        recriarOuApagar(true);
        BancoTeste.Credenciais c = BancoTeste.credenciais();
        vazio = new PGSimpleDataSource();
        vazio.setUrl(c.url() + (c.url().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        vazio.setUser(c.usuario());
        vazio.setPassword(c.senha());
    }

    @AfterAll
    static void apagarSchema() throws SQLException {
        recriarOuApagar(false);
    }

    private static void recriarOuApagar(boolean recriar) throws SQLException {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            if (recriar) {
                st.execute("CREATE SCHEMA " + SCHEMA);
            }
        }
    }

    @Test
    void bancoVazioRecebeV1aV24() throws Exception {
        assertThat(BancoTeste.migrationsNaPreparacao()).as("preparação do banco de teste").isEqualTo(24);
        assertThat(new Migracao(vazio).migrar()).as("schema vazio").isEqualTo(24);

        try (Connection con = vazio.getConnection()) {
            assertThat(BancoTeste.contar(con, "SELECT count(*) WHERE current_schema() = '" + SCHEMA + "'"))
                    .as("conferência no schema " + SCHEMA).isEqualTo(1);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM flyway_schema_history
                     WHERE success AND version IN ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13',
                                                   '14', '15', '16', '17', '18', '19', '20', '21', '22', '23', '24')
                    """)).isEqualTo(24);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                    """)).as("tabelas (24 da V1, cliente da V6, crédito da V7, devoluções da V8, crediário da V10 e "
                    + "devolucao_titulo da V11; 8 do financeiro da V12; produto_fornecedor da V13; pedido_compra e "
                    + "pedido_compra_item da V14; lote, lote_saldo, lote_movimento, lote_transferencia e "
                    + "entrada_mercadoria_item_lote da V15; etiqueta_impressao da V16; carga_balanca e carga_balanca_item da V17; perfil_tributario, ncm e cest da V18; produto_preco_quantidade da V19; usuario_preferencia da V21; venda_item_tributo e ibpt da V23; nfce_numeracao, nfce, nfce_evento e nfce_inutilizacao da V24)")
                    .isEqualTo(64);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.views
                     WHERE table_schema = current_schema()
                       AND table_name IN ('vw_venda_item_rentabilidade', 'vw_estoque_atual',
                                          'vw_estoque_inconsistente', 'vw_caixa_resumo',
                                          'vw_cliente_credito_inconsistente', 'vw_resultado_item',
                                          'vw_abatimento_inconsistente', 'vw_conta_inconsistente',
                                          'vw_lote_inconsistente', 'vw_nfce_inconsistente')
                    """)).as("views (vw_resultado_item da V9, vw_abatimento_inconsistente da V11, vw_conta_inconsistente da V12, "
                    + "vw_lote_inconsistente da V15, vw_nfce_inconsistente da V24)").isEqualTo(10);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave LIKE 'nfce.%'"))
                    .as("configurações da NFC-e da V24").isEqualTo(15);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = "
                    + "'venda' AND column_name = 'consumidor_documento'")).as("documento na nota da V24").isEqualTo(1);
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
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = "
                    + "'produto' AND column_name IN ('conteudo_quantidade', 'conteudo_unidade')"))
                    .as("conteúdo líquido do produto da V16").isEqualTo(2);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave = 'balanca.pasta_carga' "
                    + "AND valor = ''")).as("pasta da carga de balança da V17").isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = "
                    + "'produto' AND column_name = 'validade_balanca_dias'")).as("validade na balança da V17")
                    .isEqualTo(1);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM configuracao WHERE chave IN ('empresa.regime_tributario', "
                    + "'empresa.uf', 'empresa.ie')")).as("dados fiscais da empresa da V18").isEqualTo(3);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = "
                    + "'produto' AND column_name IN ('ncm', 'cest', 'origem', 'perfil_tributario_id')"))
                    .as("dados fiscais do produto da V18").isEqualTo(4);
            assertThat(BancoTeste.contar(con, """
                    SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = current_schema() AND (table_name, column_name) IN (('produto_codigo_barras', 'quantidade'),
                                                         ('produto_codigo_barras', 'preco_embalagem'),
                                                         ('venda_item', 'preco_quantidade_id'),
                                                         ('etiqueta_impressao', 'atacado_quantidade'),
                                                         ('etiqueta_impressao', 'atacado_preco'))
                    """)).as("embalagens e atacado da V19").isEqualTo(5);
            assertThat(BancoTeste.contar(con, "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = "
                    + "'venda_item' AND column_name = 'atacado_a_partir_de'")).as("faixa copiada no item da V20")
                    .isEqualTo(1);
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
