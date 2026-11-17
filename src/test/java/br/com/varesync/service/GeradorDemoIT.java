package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import br.com.varesync.config.Servicos;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.AlertasPainel;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DiaPainel;
import br.com.varesync.model.Painel;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.report.Agrupamento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.FiltroFinanceiro;
import br.com.varesync.report.FiltroValidade;
import br.com.varesync.report.FluxoCaixa;
import br.com.varesync.report.RelDre;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelAlteracoesPreco;
import br.com.varesync.report.RelCancelamentos;
import br.com.varesync.report.RelClientes;
import br.com.varesync.report.RelComprasFornecedor;
import br.com.varesync.report.RelCurvaAbc;
import br.com.varesync.report.RelDevolucoes;
import br.com.varesync.report.RelFormasPagamento;
import br.com.varesync.report.RelMovimentacao;
import br.com.varesync.report.RelPedidosPendentes;
import br.com.varesync.report.RelPerdasAjustes;
import br.com.varesync.report.RelRentabilidadeCategoria;
import br.com.varesync.report.RelSemGiro;
import br.com.varesync.report.RelVendasHoraDia;
import br.com.varesync.report.RelVendasOperador;
import br.com.varesync.report.Relatorio;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.Relatorios;
import br.com.varesync.report.RelRentabilidadeProduto;
import br.com.varesync.report.RelSaldosCredito;
import br.com.varesync.report.RelVendasPeriodo;
import br.com.varesync.report.FiltroCrediario;
import br.com.varesync.report.RelRecebimentos;
import br.com.varesync.report.RelTitulosReceber;
import br.com.varesync.report.RelInconsistenciasFiscais;
import br.com.varesync.report.RelValidade;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.SituacaoTitulo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gerador de dados de demonstração (6.14), rodado uma vez para a classe toda (60 dias, pelos próprios services):
 * o que a especificação pede foi criado (inclusive as devoluções da F13), o banco recusa uma segunda geração, só o
 * supervisor gera, R01 e R05 batem no período e o relatório de 60 dias fica abaixo de 3 s (9.4). O estoque continua
 * consistente ({@link IntegracaoBase}).
 */
class GeradorDemoIT extends IntegracaoBase {

    private static final Logger LOG = LoggerFactory.getLogger(GeradorDemoIT.class);
    private static final Clock HOJE = Clock.fixed(Instant.parse("2026-10-17T15:00:00Z"), ZoneId.systemDefault());

    private static AcessoTeste t;
    private static Sessao admin;
    private static GeradorDemo gerador;
    private static GeradorDemo.Resultado resultado;
    private static long passos;
    private static long segundosGeracao;

    @BeforeAll
    static void gerar() throws Exception {
        t = new AcessoTeste(BancoTeste.dataSource());
        t.limpar();
        admin = t.supervisor();
        gerador = Servicos.geradorDemo(BancoTeste.dataSource(), HOJE, AcessoTeste.TERMINAL,
                new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        AtomicLong ultimo = new AtomicLong();
        long inicio = System.nanoTime();
        resultado = gerador.gerar(admin, (feito, total, mensagem) -> {
            assertThat(feito).isLessThanOrEqualTo(total);
            ultimo.set(feito);
        });
        segundosGeracao = (System.nanoTime() - inicio) / 1_000_000_000L;
        passos = ultimo.get();
        LOG.info("Dados de demonstração gerados em {} s: {} vendas", segundosGeracao, resultado.vendas());
    }

    private static long contar(String sql) throws Exception {
        try (Connection con = dataSource.getConnection()) {
            return BancoTeste.contar(con, sql);
        }
    }

    private static List<Long> coluna(String sql) throws Exception {
        List<Long> valores = new ArrayList<>();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                valores.add(rs.getLong(1));
            }
        }
        return valores;
    }

    private static BigDecimal umValor(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private static Filtros periodo() {
        return Filtros.vazios().comPeriodo(new Periodo(resultado.inicio(), resultado.fim()));
    }

    @Test
    void criaSessentaDiasTerminandoOntem() throws Exception {
        assertThat(resultado.inicio()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(resultado.fim()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(passos).isEqualTo(resultado.produtos() + resultado.vendas() + resultado.entradas()
                + resultado.clientes());
        assertThat(contar("SELECT count(*) FROM caixa_sessao")).isEqualTo(GeradorDemo.DIAS);
        assertThat(contar("SELECT count(*) FROM caixa_sessao WHERE status <> 'FECHADO'")).isZero();
        assertThat(contar("SELECT count(*) FROM caixa_sessao WHERE diferenca <> 0"))
                .as("fechamento às vezes com pequena diferença").isBetween(3L, 30L);
        assertThat(contar("SELECT count(*) FROM caixa_movimento WHERE tipo = 'SANGRIA'")).isGreaterThan(30);
        assertThat(contar("""
                SELECT count(*) FROM venda
                 WHERE data_hora < TIMESTAMPTZ '2026-08-18 00:00' OR data_hora >= TIMESTAMPTZ '2026-10-17 00:00'
                """)).as("todas as vendas no período").isZero();
    }

    @Test
    void vendasComOsVolumesDaEspecificacao() throws Exception {
        List<Long> porDia = coluna("SELECT count(*) FROM venda GROUP BY caixa_sessao_id");
        assertThat(porDia).hasSize(GeradorDemo.DIAS).allSatisfy(n -> assertThat(n).isBetween(20L, 60L));
        assertThat(coluna("SELECT count(*) FROM venda_item GROUP BY venda_id"))
                .allSatisfy(n -> assertThat(n).isBetween(1L, 15L));
        long vendas = contar("SELECT count(*) FROM venda");
        assertThat(vendas).isEqualTo(resultado.vendas());
        long canceladas = contar("SELECT count(*) FROM venda WHERE status = 'CANCELADA'");
        assertThat(canceladas).isEqualTo(resultado.vendasCanceladas()).isBetween(vendas / 300, vendas / 30);
        long itens = contar("SELECT count(*) FROM venda_item");
        long itensCancelados = contar("SELECT count(*) FROM venda_item WHERE cancelado");
        assertThat(itensCancelados).isEqualTo(resultado.itensCancelados()).isBetween(itens / 200, itens / 20);
        assertThat(coluna("SELECT count(DISTINCT vp.forma_pagamento_id) FROM venda_pagamento vp"))
                .as("as 5 formas da V2, o crédito do cliente e o crediário").containsExactly(7L);
        assertThat(contar("SELECT count(*) FROM venda WHERE desconto_venda > 0 OR desconto_itens > 0"))
                .isGreaterThan(vendas / 50);
        // Picos entre 11h–13h e 17h–19h
        long pico = contar("SELECT count(*) FROM venda WHERE extract(hour FROM data_hora) IN (11, 12, 17, 18)");
        long manha = contar("SELECT count(*) FROM venda WHERE extract(hour FROM data_hora) IN (8, 9)");
        assertThat(pico).isGreaterThan(3 * manha);
    }

    @Test
    void cadastrosEstoqueEOcorrencias() throws Exception {
        assertThat(resultado.usuarios()).extracting(GeradorDemo.UsuarioDemo::login)
                .containsExactly("maria", "jose", "carlos");
        // Semente fixa: as senhas provisórias são sempre as mesmas (estão no README, "Usuários de demonstração").
        assertThat(resultado.usuarios()).extracting(GeradorDemo.UsuarioDemo::senha)
                .containsExactly("demo1835", "demo9076", "demo6999");
        assertThat(contar("SELECT count(*) FROM usuario WHERE deve_trocar_senha")).isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM produto")).isEqualTo(resultado.produtos()).isBetween(75L, 85L);
        assertThat(contar("SELECT count(DISTINCT categoria_id) FROM produto")).isEqualTo(9);
        assertThat(contar("SELECT count(*) FROM produto WHERE plu IS NOT NULL")).isGreaterThanOrEqualTo(10);
        assertThat(contar("SELECT count(*) FROM produto WHERE preco_venda < custo")).isGreaterThanOrEqualTo(2);
        assertThat(contar("""
                SELECT count(*) FROM produto p
                 WHERE NOT EXISTS (SELECT 1 FROM venda_item vi WHERE vi.produto_id = p.id)
                """)).as("produtos sem venda (sem giro)").isGreaterThanOrEqualTo(3);
        assertThat(contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'PERDA'")).isGreaterThan(5);
        assertThat(contar("SELECT count(*) FROM vw_estoque_atual WHERE saldo < 0")).isZero();
        assertThat(contar("SELECT count(*) FROM vw_estoque_atual WHERE abaixo_do_minimo")).isGreaterThan(0);
        assertThat(contar("SELECT count(*) FROM produto_historico_preco WHERE preco_anterior IS NOT NULL"))
                .as("reajustes de preço").isGreaterThanOrEqualTo(10);
        assertThat(contar("SELECT count(*) FROM inventario WHERE status = 'APLICADO' AND id = "
                + resultado.inventarioId())).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'INVENTARIO'")).isGreaterThan(0);
        assertThat(contar("SELECT count(*) FROM inventario_item WHERE quantidade_contada IS NULL")).isEqualTo(1);
        assertThat(t.auditoria(AcaoAuditoria.DADOS_DEMO_GERADOS)).hasSize(1);
    }

    @Test
    void fornecedoresEEntradasSemanaisComCustoVariando() throws Exception {
        assertThat(resultado.fornecedores()).isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM fornecedor")).isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM fornecedor WHERE cpf_cnpj ~ '[A-Z]'"))
                .as("CNPJs alfanuméricos (A.3)").isEqualTo(2);
        long entradas = contar("SELECT count(*) FROM entrada_mercadoria");
        assertThat(entradas).isEqualTo(resultado.entradas()).as("uma por semana de cada fornecedor")
                .isBetween(24L, 27L);
        assertThat(contar("SELECT count(*) FROM entrada_mercadoria WHERE status <> 'CONFIRMADA'")).isZero();
        assertThat(coluna("""
                SELECT count(*) FROM entrada_mercadoria GROUP BY fornecedor_id, extract(isodow FROM confirmado_em)
                """)).as("cada fornecedor sempre no mesmo dia da semana").hasSize(3);
        assertThat(contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'ENTRADA_COMPRA'"))
                .isEqualTo(contar("SELECT count(*) FROM entrada_mercadoria_item"));
        assertThat(contar("""
                SELECT count(*) FROM produto_historico_preco
                 WHERE custo_anterior IS NOT NULL AND custo_novo <> custo_anterior
                """)).as("histórico de custo pelas entradas").isGreaterThan(100);
        assertThat(contar("""
                SELECT count(*) FROM entrada_mercadoria e
                 WHERE e.valor_total_nota <> (SELECT sum(valor_total) FROM entrada_mercadoria_item i
                                               WHERE i.entrada_id = e.id)
                """)).as("nota igual à soma dos itens").isZero();
        assertThat(t.auditoria(AcaoAuditoria.ENTRADA_CONFIRMADA)).hasSize((int) entradas);
        // Os de preço abaixo do custo continuam assim (o custo só sobe) e o R17 bate com as notas.
        assertThat(contar("SELECT count(*) FROM produto WHERE preco_venda < custo")).isGreaterThanOrEqualTo(2);
        ResultadoRelatorio<RelComprasFornecedor.Linha> r17 = t.relatorios.gerar(admin, new RelComprasFornecedor(),
                periodo());
        assertThat(r17.linhas()).hasSize(3);
        assertThat(Coluna.soma(r17.linhas(), RelComprasFornecedor.Linha::valor))
                .isEqualByComparingTo(umValor("SELECT sum(valor_total_nota) FROM entrada_mercadoria"));
    }

    /**
     * F16.4: vínculos produto × fornecedor com fator e prazo, um pedido por entrada semanal (enviado 3 dias antes,
     * vinculado e recebido) e o pedido da próxima entrega de cada fornecedor pendente, que aparece no R27 e reduz a
     * sugestão; o último custo do vínculo é o da última entrada.
     */
    @Test
    void vinculosEPedidosDaFase16() throws Exception {
        assertThat(resultado.vinculos()).isEqualTo(contar("SELECT count(*) FROM produto_fornecedor")).isPositive();
        assertThat(contar("SELECT count(*) FROM produto_fornecedor WHERE NOT principal")).isZero();
        assertThat(contar("SELECT count(*) FROM produto_fornecedor WHERE fator > 1")).as("caixas de 6, 12 e 24")
                .isPositive();
        assertThat(coluna("SELECT DISTINCT fator::int FROM produto_fornecedor ORDER BY 1")).containsExactly(1L, 6L,
                12L, 24L);
        assertThat(contar("""
                SELECT count(*) FROM produto_fornecedor pf
                 WHERE pf.ultimo_custo <> (SELECT i.custo_unitario FROM entrada_mercadoria_item i
                                             JOIN entrada_mercadoria e ON e.id = i.entrada_id
                                            WHERE i.produto_id = pf.produto_id ORDER BY e.confirmado_em DESC LIMIT 1)
                """)).as("último custo = o da última entrada").isZero();

        assertThat(resultado.pedidos()).isEqualTo(contar("SELECT count(*) FROM pedido_compra"))
                .isEqualTo(resultado.entradas() + resultado.pedidosPendentes());
        assertThat(resultado.pedidosPendentes()).isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM entrada_mercadoria WHERE pedido_id IS NULL")).isZero();
        assertThat(contar("SELECT count(*) FROM pedido_compra WHERE status = 'RECEBIDO'"))
                .isEqualTo(resultado.entradas());
        assertThat(contar("SELECT count(*) FROM pedido_compra WHERE status = 'ENVIADO'")).isEqualTo(3);
        assertThat(contar("""
                SELECT count(*) FROM pedido_compra p JOIN entrada_mercadoria e ON e.pedido_id = p.id
                 WHERE p.enviado_em >= e.confirmado_em
                    OR p.enviado_em::date < (e.confirmado_em - interval '3 days')::date
                """)).as("pedido enviado até 3 dias antes da entrega").isZero();

        ResultadoRelatorio<RelPedidosPendentes.Linha> r27 = t.relatorios.gerar(admin, new RelPedidosPendentes(),
                Filtros.vazios());
        assertThat(r27.linhas().stream().map(RelPedidosPendentes.Linha::pedidoId).distinct().count()).isEqualTo(3);
        assertThat(t.sugestao.consultar(admin, null, null).linhas()).as("o pendente reduz a sugestão")
                .anySatisfy(l -> assertThat(l.pendente()).isPositive());
        assertThat(t.auditoria(AcaoAuditoria.PEDIDO_ENVIADO)).hasSize(resultado.pedidos());
    }

    /** F12.8: 150 clientes, cerca de 40% das vendas identificadas, fidelidade ligada com 1% e alguns usos de crédito. */
    @Test
    void clientesFidelidadeECreditoDaFase12() throws Exception {
        assertThat(resultado.clientes()).isEqualTo(150);
        assertThat(contar("SELECT count(*) FROM cliente")).isEqualTo(150);
        long vendas = contar("SELECT count(*) FROM venda");
        long identificadas = contar("SELECT count(*) FROM venda WHERE cliente_id IS NOT NULL");
        assertThat(identificadas).isEqualTo(resultado.vendasComCliente()).isBetween(vendas * 35 / 100,
                vendas * 45 / 100);
        assertThat(contar("SELECT count(DISTINCT cliente_id) FROM venda")).as("quase todos compraram")
                .isGreaterThan(140);
        Configuracoes c = t.configuracoes.carregar(admin);
        assertThat(c.fidelidade()).isEqualTo(new Configuracoes.Fidelidade(true, BigDecimal.ONE, BigDecimal.ZERO));
        long usos = contar("SELECT count(*) FROM cliente_credito_movimento WHERE tipo = 'USO_EM_VENDA'");
        assertThat(usos).isEqualTo(resultado.usosDeCredito()).isBetween(20L, identificadas / 4);
        assertThat(contar("SELECT count(*) FROM cliente_credito_movimento WHERE tipo = 'CASHBACK'"))
                .as("uma por venda identificada com valor a pagar").isGreaterThan(identificadas * 9 / 10);
        assertThat(contar("SELECT count(*) FROM cliente_credito_movimento WHERE tipo LIKE 'ESTORNO%'"))
                .as("vendas identificadas canceladas").isPositive();
        // Saldo = cashback − uso ± estornos, conferido pela view; o R19 soma o mesmo que a tabela de saldos.
        assertThat(contar("SELECT count(*) FROM vw_cliente_credito_inconsistente")).isZero();
        ResultadoRelatorio<RelSaldosCredito.Linha> r19 = t.relatorios.gerar(admin, new RelSaldosCredito(),
                Filtros.vazios());
        assertThat(Coluna.soma(r19.linhas(), RelSaldosCredito.Linha::saldo))
                .isEqualByComparingTo(umValor("SELECT sum(saldo) FROM cliente_credito"));
        ResultadoRelatorio<RelClientes.Linha> r18 = t.relatorios.gerar(admin, new RelClientes(), periodo());
        assertThat(r18.linhas()).hasSizeGreaterThan(140);
    }

    /** F13.4: cerca de 0,5% dos itens devolvidos, com formas de reembolso variadas e alguns avariados. */
    @Test
    void devolucoesDaFase13() throws Exception {
        long devolucoes = contar("SELECT count(*) FROM devolucao");
        assertThat(devolucoes).isEqualTo(resultado.devolucoes()).isPositive();
        long itensVendidos = contar("SELECT count(*) FROM venda_item vi JOIN venda v ON v.id = vi.venda_id "
                + "WHERE v.status = 'FINALIZADA' AND NOT vi.cancelado");
        long itensDevolvidos = contar("SELECT count(*) FROM devolucao_item");
        assertThat(itensDevolvidos).as("cerca de 0,5% dos itens").isBetween(itensVendidos / 400, itensVendidos / 100);
        assertThat(coluna("SELECT count(DISTINCT forma_reembolso) FROM devolucao"))
                .as("dinheiro, vale-troca, outro e abatimento").containsExactly(4L);
        assertThat(contar("SELECT count(*) FROM devolucao_item WHERE NOT volta_estoque")).as("avariados")
                .isPositive();
        assertThat(contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'DEVOLUCAO_VENDA'"))
                .isEqualTo(itensDevolvidos);
        assertThat(contar("SELECT count(*) FROM cliente_credito_movimento WHERE tipo = 'CREDITO_DEVOLUCAO'"))
                .as("vale-troca").isEqualTo(contar("SELECT count(*) FROM devolucao WHERE forma_reembolso = "
                        + "'CREDITO_CLIENTE'"));
        assertThat(contar("""
                SELECT count(*) FROM devolucao d JOIN venda v ON v.id = d.venda_id
                 WHERE d.data_hora::date - v.data_hora::date NOT BETWEEN 1 AND 5
                """)).as("de 1 a 5 dias depois da venda").isZero();
        assertThat(contar("SELECT count(*) FROM devolucao WHERE data_hora >= TIMESTAMPTZ '2026-10-17 00:00'"))
                .as("todas no período").isZero();
        // O R20 lista todas, e a soma das devoluções do R01 é a mesma.
        ResultadoRelatorio<RelDevolucoes.Linha> r20 = t.relatorios.gerar(admin, new RelDevolucoes(), periodo());
        assertThat(r20.linhas()).hasSize((int) devolucoes);
        assertThat(t.auditoria(AcaoAuditoria.DEVOLUCAO_REGISTRADA)).hasSize((int) devolucoes);
    }

    /**
     * F20.3: o fardo de 6 do refrigerante cola com preço próprio e as faixas de mercearia, com vendas que usam os dois;
     * nenhuma etiqueta fica pendente só por causa das faixas (impressas na véspera, depois do cadastro).
     */
    @Test
    void embalagensEAtacadoDaFase20() throws Exception {
        assertThat(contar("SELECT count(*) FROM produto_codigo_barras WHERE quantidade = 6 AND preco_embalagem = 49.90 "
                + "AND codigo = '" + AtacadoDemo.CODIGO_FARDO + "'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM produto_preco_quantidade")).isEqualTo(4);
        assertThat(contar("SELECT count(DISTINCT produto_id) FROM produto_preco_quantidade ppq "
                + "JOIN produto p ON p.id = ppq.produto_id JOIN categoria c ON c.id = p.categoria_id "
                + "WHERE c.nome = 'Mercearia'")).as("faixas em mercearia").isEqualTo(3);
        long fardos = contar("""
                SELECT count(*) FROM venda_item vi JOIN venda v ON v.id = vi.venda_id
                 WHERE v.status = 'FINALIZADA' AND NOT vi.cancelado AND vi.quantidade = 6 AND vi.valor_bruto = 49.90
                   AND vi.produto_id = (SELECT produto_id FROM produto_codigo_barras WHERE codigo = ?)
                """.replace("?", "'" + AtacadoDemo.CODIGO_FARDO + "'"));
        assertThat(fardos).as("vendas com o fardo pelo preço dele").isGreaterThan(20);
        long atacado = contar("SELECT count(*) FROM venda_item WHERE preco_quantidade_id IS NOT NULL "
                + "AND atacado_a_partir_de IS NOT NULL AND NOT cancelado");
        assertThat(atacado).as("itens vendidos no preço de atacado").isGreaterThan(20);
        assertThat(contar("SELECT count(*) FROM venda_item WHERE preco_quantidade_id IS NOT NULL "
                + "AND atacado_a_partir_de IS NULL")).isZero();
        assertThat(contar("""
                SELECT count(*) FROM venda_item vi JOIN produto_preco_quantidade ppq ON ppq.id = vi.preco_quantidade_id
                 WHERE vi.preco_unitario <> ppq.preco OR vi.atacado_a_partir_de <> ppq.quantidade_minima
                """)).as("o item no preço da faixa e com a quantidade dela").isZero();
    }

    @Test
    void crediarioDaFase14() throws Exception {
        assertThat(contar("SELECT count(*) FROM cliente WHERE limite_credito > 0")).isEqualTo(30);
        assertThat(resultado.clientesComLimite()).isEqualTo(30);
        assertThat(contar("SELECT count(DISTINCT venda_id) FROM titulo_receber")).as("vendas a prazo")
                .isEqualTo(resultado.vendasCrediario()).isPositive();
        assertThat(contar("SELECT count(*) FROM recebimento")).isEqualTo(resultado.recebimentos()).isPositive();
        assertThat(contar("SELECT count(*) FROM recebimento WHERE multa + juros = 0")).as("em dia").isPositive();
        assertThat(contar("SELECT count(*) FROM recebimento WHERE multa + juros > 0")).as("com atraso").isPositive();
        assertThat(contar("SELECT count(*) FROM titulo_receber WHERE status = 'ABERTO' AND vencimento < DATE '" + LocalDate.now(HOJE) + "'"))
                .as("alguns títulos vencidos em aberto").isEqualTo(resultado.titulosVencidos()).isPositive();
        assertThat(contar("""
                SELECT count(*) FROM titulo_receber t
                 WHERE t.valor_pago <> coalesce((SELECT sum(rt.principal) FROM recebimento_titulo rt
                                                  WHERE rt.titulo_id = t.id), 0)
                """)).as("valor_pago = soma dos principais").isZero();
        assertThat(t.relatorios.gerar(admin, new RelRecebimentos(), periodo()).linhas())
                .hasSize((int) contar("SELECT count(*) FROM recebimento"));
        Servicos hoje = Servicos.montar(dataSource, HOJE, AcessoTeste.TERMINAL, new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        assertThat(hoje.relatorios().gerar(admin, new RelTitulosReceber(), Filtros.vazios()
                .comCrediario(new FiltroCrediario(SituacaoTitulo.VENCIDOS, null, null, null, null))).linhas())
                .hasSize(resultado.titulosVencidos());
    }

    /**
     * F15.8: integração ligada, saldo inicial no Cofre, taxas da especificação, recebíveis conciliados até a semana
     * anterior, despesas mensais com os impostos de 6% e entradas com 2 duplicatas. Aceite F15 (consistência): com os
     * dados de demonstração, as linhas 4 e 6 do DRE batem com o R01 e o R05 do mesmo mês.
     */
    @Test
    void financeiroDaFase15() throws Exception {
        assertThat(contar("SELECT count(*) FROM configuracao WHERE chave = '" + Configuracoes.FINANCEIRO_INTEGRAR_CAIXA
                + "' AND valor = 'true'")).isEqualTo(1);
        assertThat(umValor("""
                SELECT m.valor FROM conta_movimento m JOIN conta_financeira c ON c.id = m.conta_id
                 WHERE m.tipo = 'SALDO_INICIAL' AND c.nome = 'Cofre'
                """)).isEqualByComparingTo(GeradorDemo.SALDO_INICIAL_COFRE);
        assertThat(contar("SELECT count(*) FROM conta_movimento WHERE tipo = 'SALDO_INICIAL'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM conta_movimento WHERE tipo IN ('ABERTURA_CAIXA', 'FECHAMENTO_CAIXA')"))
                .as("abertura e fechamento de cada dia").isEqualTo(2L * GeradorDemo.DIAS);
        assertThat(contar("SELECT count(*) FROM conta_movimento WHERE tipo = 'TRANSFERENCIA'"))
                .as("depósitos semanais, duas pernas cada").isPositive().isEven();
        assertThat(coluna("""
                SELECT taxa_pct * 100 + dias_recebimento FROM forma_pagamento
                 WHERE tipo IN ('CARTAO_DEBITO', 'CARTAO_CREDITO', 'PIX') ORDER BY tipo
                """)).as("crédito 3% D+30, débito 1% D+1, PIX 0% D+0").containsExactly(330L, 101L, 0L);

        LocalDate ultimaSegunda = resultado.fim().with(TemporalAdjusters
                .previousOrSame(DayOfWeek.MONDAY));
        assertThat(contar("SELECT count(*) FROM recebivel WHERE status = 'PREVISTO' AND previsto_para < DATE '"
                + ultimaSegunda + "'")).as("conciliados até a semana anterior").isZero();
        assertThat(contar("SELECT count(*) FROM recebivel WHERE status = 'PREVISTO'")).isPositive();
        assertThat(contar("SELECT count(*) FROM recebivel WHERE status = 'RECEBIDO'")).isPositive();
        assertThat(resultado.recebiveisConciliados()).isEqualTo((int) contar("""
                SELECT count(*) FROM recebivel r JOIN forma_pagamento f ON f.id = r.forma_pagamento_id
                 WHERE r.status <> 'PREVISTO' AND f.dias_recebimento > 0 AND r.recebido_em IS NOT NULL
                """));

        assertThat(coluna("""
                SELECT count(*) FROM titulo_pagar t JOIN categoria_despesa c ON c.id = t.categoria_id
                 WHERE c.nome IN ('Aluguel', 'Energia', 'Salários e encargos', 'Impostos sobre vendas')
                 GROUP BY c.nome ORDER BY c.nome
                """)).as("as quatro despesas mensais").hasSize(4).allSatisfy(n -> assertThat(n).isPositive());
        assertThat(contar("SELECT count(*) FROM entrada_mercadoria_parcela")).isEqualTo(2L * resultado.entradas());
        assertThat(contar("""
                SELECT count(*) FROM titulo_pagar t JOIN categoria_despesa c ON c.id = t.categoria_id
                 WHERE c.grupo = 'MERCADORIA'
                """)).isEqualTo(2L * resultado.entradas());
        assertThat(resultado.contasPagas()).isEqualTo((int) contar(
                "SELECT count(*) FROM titulo_pagar_pagamento WHERE NOT estornado")).isPositive();
        LocalDate agosto = resultado.inicio().withDayOfMonth(1);
        BigDecimal receitaAgosto = umValor("SELECT sum(receita) FROM vw_resultado_item WHERE data_hora >= DATE '"
                + agosto + "' AND data_hora < DATE '" + agosto.plusMonths(1) + "'");
        assertThat(umValor("""
                SELECT t.valor FROM titulo_pagar t JOIN categoria_despesa c ON c.id = t.categoria_id
                 WHERE c.grupo = 'IMPOSTO' AND t.competencia = DATE '""" + agosto + "'"))
                .isEqualByComparingTo(receitaAgosto.multiply(new BigDecimal("0.06")).setScale(2,
                        RoundingMode.HALF_UP));

        // Aceite F15: linhas 4 e 6 do DRE = R01 e R05 do mesmo mês (setembro inteiro e outubro até ontem).
        for (YearMonth mes : List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 10))) {
            ResultadoRelatorio<RelDre.Linha> dre = t.relatorios.gerar(admin,
                    new RelDre(), Filtros.vazios()
                            .comFinanceiro(new FiltroFinanceiro(null, null, mes)));
            Filtros doMes = Filtros.vazios().comPeriodo(new Periodo(mes.atDay(1), mes.atEndOfMonth()));
            BigDecimal receitaR01 = Coluna.soma(t.relatorios.gerar(admin, new RelVendasPeriodo(), doMes).linhas(),
                    RelVendasPeriodo.Linha::receita);
            BigDecimal lucroR05 = Coluna.soma(t.relatorios.gerar(admin, new RelRentabilidadeProduto(), doMes)
                    .linhas(), RelRentabilidadeProduto.Linha::lucro);
            assertThat(receitaR01).isPositive();
            assertThat(dre.linhas().stream().filter(l -> l.chave().equals("4")).findFirst().orElseThrow().valor())
                    .as("receita líquida de " + mes).isEqualByComparingTo(receitaR01);
            assertThat(dre.linhas().stream().filter(l -> l.chave().equals("6")).findFirst().orElseThrow().valor())
                    .as("lucro bruto de " + mes).isEqualByComparingTo(lucroR05);
            LOG.info("DRE de {}: {}", mes, dre.linhas().stream().filter(l -> !l.detalhe())
                    .map(l -> l.chave() + "=" + l.valor()).toList());
        }

        Servicos hoje = Servicos.montar(dataSource, HOJE, AcessoTeste.TERMINAL, new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        FluxoCaixa fluxo = hoje.fluxoCaixa().calcular(admin, new Periodo(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30)), Agrupamento.SEMANA);
        assertThat(fluxo.lancamentos()).extracting(FluxoCaixa.Lancamento::descricao)
                .contains(FluxoCaixa.EM_ATRASO, FluxoCaixa.RECEBIVEIS,
                        FluxoCaixa.CREDIARIO, FluxoCaixa.CONTAS_A_PAGAR,
                        "Recebível", "Sangria", "Fechamento de caixa", "Pagamento")
                .doesNotContain("Transferência", "Saldo inicial");
        Painel painel = hoje.painel().carregar(admin);
        assertThat(painel.financeiro().saldoTotal()).isEqualByComparingTo(umValor("SELECT sum(saldo) FROM conta_saldo"));
        assertThat(painel.alertas().pagarVencidas().quantidade()).as("a energia que ficou em aberto").isPositive();
    }

    @Test
    void recusaBancoComVendasESoOSupervisorGera() {
        assertThatThrownBy(() -> gerador.conferir(admin)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("já tem").hasMessageContaining("varesync_demo");
        GeradorDemo.UsuarioDemo carlos = resultado.usuarios().get(2);
        Sessao gerente = t.acesso.entrar(carlos.login(), carlos.senha());
        assertThatThrownBy(() -> gerador.gerar(gerente, (f, total, m) -> { }))
                .isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void r01Er05BatemEVendaCanceladaFicaDeFora() throws Exception {
        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(), periodo());
        ResultadoRelatorio<RelRentabilidadeProduto.Linha> r05 = t.relatorios.gerar(admin,
                new RelRentabilidadeProduto(), periodo());

        assertThat(r01.linhas()).hasSize(GeradorDemo.DIAS);
        BigDecimal receita = r01.linhas().stream().map(RelVendasPeriodo.Linha::receita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lucro = r01.linhas().stream().map(RelVendasPeriodo.Linha::lucro)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(r05.linhas().stream().map(RelRentabilidadeProduto.Linha::receita)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(receita);
        assertThat(r05.linhas().stream().map(RelRentabilidadeProduto.Linha::lucro)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(lucro);
        assertThat(r05.totais().get(7)).isEqualTo(r01.totais().get(8));

        // F13.3: "Vendas" = vendas finalizadas; "Devoluções" = devoluções do período; receita líquida = a diferença.
        BigDecimal vendido = Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::vendido);
        BigDecimal devolvido = Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::devolvido);
        assertThat(vendido).isEqualByComparingTo(
                umValor("SELECT sum(valor_total) FROM venda WHERE status = 'FINALIZADA'"));
        assertThat(vendido).isLessThan(umValor("SELECT sum(valor_total) FROM venda"));
        assertThat(devolvido).isPositive().isEqualByComparingTo(umValor("SELECT sum(valor_total) FROM devolucao"));
        assertThat(receita).isEqualByComparingTo(vendido.subtract(devolvido));
        BigDecimal lucroDasTabelas = umValor("""
                SELECT sum(vi.valor_liquido - vi.custo_total) FROM venda_item vi JOIN venda v ON v.id = vi.venda_id
                 WHERE v.status = 'FINALIZADA' AND NOT vi.cancelado
                """).subtract(umValor("SELECT sum(valor - custo_total) FROM devolucao_item"));
        assertThat(lucro).isEqualByComparingTo(lucroDasTabelas);
        long vendasR01 = r01.linhas().stream().mapToLong(RelVendasPeriodo.Linha::vendas).sum();
        assertThat(vendasR01).isEqualTo(resultado.vendas() - resultado.vendasCanceladas());

        assertThat(r05.linhas()).extracting(RelRentabilidadeProduto.Linha::situacao)
                .contains(RelRentabilidadeProduto.NEGATIVA, RelRentabilidadeProduto.BAIXA,
                        RelRentabilidadeProduto.OK);
    }

    @Test
    void relatoriosDaParte2BatemComOR01EComOQueFoiGerado() {
        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(), periodo());
        BigDecimal receita = Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::receita);
        BigDecimal lucro = Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::lucro);
        long vendas = r01.linhas().stream().mapToLong(RelVendasPeriodo.Linha::vendas).sum();

        ResultadoRelatorio<RelRentabilidadeCategoria.Linha> r06 = t.relatorios.gerar(admin,
                new RelRentabilidadeCategoria(), periodo());
        assertThat(Coluna.soma(r06.linhas(), RelRentabilidadeCategoria.Linha::lucro)).isEqualByComparingTo(lucro);
        assertThat(r06.totais().get(4)).as("margem do período, igual à do R01").isEqualTo(r01.totais().get(8));

        ResultadoRelatorio<RelCurvaAbc.Linha> r07 = t.relatorios.gerar(admin, new RelCurvaAbc(), periodo());
        assertThat(Coluna.soma(r07.linhas(), RelCurvaAbc.Linha::valor)).isEqualByComparingTo(receita);
        assertThat(r07.linhas()).extracting(RelCurvaAbc.Linha::classe).contains("A", "B", "C");

        ResultadoRelatorio<RelFormasPagamento.Linha> r08 = t.relatorios.gerar(admin, new RelFormasPagamento(),
                periodo());
        // 7 formas de pagamento e, à parte, as 4 formas de reembolso; batem com as colunas Vendas e Devoluções do R01.
        assertThat(r08.linhas()).hasSize(11);
        assertThat(r08.linhas().stream().filter(RelFormasPagamento.Linha::ehReembolso)).hasSize(4);
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::valor))
                .isEqualByComparingTo(Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::vendido));
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::reembolsos))
                .isEqualByComparingTo(Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::devolvido));
        assertThat(r08.linhas().get(0).vendasNoPeriodo()).isEqualTo(vendas);

        ResultadoRelatorio<RelVendasOperador.Linha> r09 = t.relatorios.gerar(admin, new RelVendasOperador(),
                periodo());
        assertThat(Coluna.soma(r09.linhas(), RelVendasOperador.Linha::receita)).isEqualByComparingTo(receita);
        assertThat(r09.linhas().stream().mapToLong(RelVendasOperador.Linha::vendasCanceladas).sum())
                .isEqualTo(resultado.vendasCanceladas());

        ResultadoRelatorio<RelVendasHoraDia.Linha> r10 = t.relatorios.gerar(admin, new RelVendasHoraDia(),
                periodo());
        assertThat(Coluna.soma(r10.linhas(), RelVendasHoraDia.Linha::receitaTotal)).isEqualByComparingTo(receita);
        assertThat(r10.linhas().stream().mapToLong(RelVendasHoraDia.Linha::vendasTotal).sum()).isEqualTo(vendas);

        ResultadoRelatorio<RelPerdasAjustes.Linha> r14 = t.relatorios.gerar(admin, new RelPerdasAjustes(),
                periodo());
        assertThat(r14.linhas()).extracting(RelPerdasAjustes.Linha::tipo)
                .contains(TipoMovimento.PERDA, TipoMovimento.INVENTARIO);
        assertThat(r14.linhas()).extracting(RelPerdasAjustes.Linha::motivo)
                .contains("Inventário nº " + resultado.inventarioId());

        ResultadoRelatorio<RelAlteracoesPreco.Linha> r15 = t.relatorios.gerar(admin, new RelAlteracoesPreco(),
                periodo());
        assertThat(r15.linhas()).isNotEmpty()
                .allSatisfy(l -> assertThat(l.precoNovo()).isNotEqualByComparingTo(l.precoAnterior()));

        ResultadoRelatorio<RelCancelamentos.Linha> r16 = t.relatorios.gerar(admin, new RelCancelamentos(),
                periodo());
        List<RelCancelamentos.Linha> vendasCanceladas = r16.linhas().stream()
                .filter(l -> l.tipo().equals("Venda")).toList();
        // Operador cancela com a liberação do gerente; o gerente (domingo à tarde) cancela sem liberação.
        assertThat(vendasCanceladas).hasSize(resultado.vendasCanceladas())
                .allSatisfy(l -> assertThat(l.liberadoPor()).isEqualTo(
                        l.canceladoPor().equals("Carlos Pereira") ? "" : "Carlos Pereira (carlos)"));
        assertThat(vendasCanceladas).anyMatch(l -> !l.liberadoPor().isEmpty());
        assertThat(r16.linhas()).anyMatch(l -> l.tipo().equals("Item"));
    }

    /**
     * Validade e lotes (F17.3): os perecíveis embalados têm o controle ligado e um lote por entrada semanal; a sobra
     * vencida virou perda por vencimento, menos a dos últimos dias. No fim, o R29 e o painel mostram lotes vencendo em
     * até 7 dias e lotes vencidos com saldo; os lotes continuam consistentes ({@code IntegracaoBase}).
     */
    @Test
    void lotesVencendoEVencidosNoR29ENoPainel() throws Exception {
        assertThat(contar("SELECT count(*) FROM produto WHERE controla_validade"))
                .isEqualTo(GeradorDemo.VALIDADE_DIAS.size());
        assertThat(contar("""
                SELECT count(*) FROM produto p JOIN categoria c ON c.id = p.categoria_id
                 WHERE p.controla_validade AND c.nome NOT IN ('Frios e laticínios', 'Padaria')
                """)).isZero();
        assertThat(contar("SELECT count(*) FROM entrada_mercadoria_item_lote")).isPositive();
        assertThat(contar("SELECT count(*) FROM lote WHERE codigo <> ''")).isGreaterThan(20);
        assertThat(contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'PERDA' AND motivo LIKE 'Vencimento:%'"))
                .isPositive();

        Servicos hoje = Servicos.montar(BancoTeste.dataSource(), HOJE, AcessoTeste.TERMINAL,
                new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        ResultadoRelatorio<RelValidade.Linha> r29 = hoje.relatorios().gerar(admin, new RelValidade(),
                Filtros.vazios().comValidade(new FiltroValidade(7, true)));
        assertThat(r29.linhas()).anyMatch(l -> l.diasParaVencer() >= 0 && l.diasParaVencer() <= 7)
                .anyMatch(RelValidade.Linha::vencido)
                .allSatisfy(l -> assertThat(l.saldo()).isPositive())
                .allSatisfy(l -> assertThat(l.diasParaVencer())
                        .isGreaterThanOrEqualTo(-GeradorDemo.DIAS_VENCIDOS_SEM_PERDA));
        AlertasPainel.Lotes alertas = hoje.painel().carregar(admin).alertas().lotes();
        assertThat(alertas.vencendo()).isEqualTo(r29.linhas().stream().filter(l -> !l.vencido()).count());
        assertThat(alertas.vencidos()).isEqualTo(r29.linhas().stream().filter(RelValidade.Linha::vencido).count());
        LOG.info("Lotes no fim: {} vencendo em até 7 dias, {} vencidos, {} SEM LOTE negativos", alertas.vencendo(),
                alertas.vencidos(), alertas.semLoteNegativo());
    }

    @Test
    void produtosSemGiroComOsDadosDeDemonstracao() {
        Servicos hoje = Servicos.montar(BancoTeste.dataSource(), HOJE, AcessoTeste.TERMINAL,
                new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        ResultadoRelatorio<RelSemGiro.Linha> r12 = hoje.relatorios().gerar(admin, new RelSemGiro(), Filtros.vazios());
        assertThat(r12.linhas()).isNotEmpty()
                .allSatisfy(l -> assertThat(l.saldo()).isPositive())
                .anySatisfy(l -> assertThat(l.ultimaVenda()).isNull());
    }

    @Test
    void painelEmMenosDeDoisSegundosEBateComOR01() {
        Servicos hoje = Servicos.montar(BancoTeste.dataSource(), HOJE, AcessoTeste.TERMINAL,
                new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        long inicio = System.nanoTime();
        Painel painel = hoje.painel().carregar(admin);
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        LOG.info("Painel com os dados de demonstração: {} ms", ms);
        assertThat(ms).isLessThan(2000);

        LocalDate dia = LocalDate.now(HOJE);
        BigDecimal receitaR01 = Coluna.soma(hoje.relatorios().gerar(admin, new RelVendasPeriodo(),
                Filtros.vazios().comPeriodo(new Periodo(dia.minusDays(29), dia))).linhas(),
                RelVendasPeriodo.Linha::receita);
        assertThat(Coluna.soma(painel.ultimos30Dias(), DiaPainel::receita)).isEqualByComparingTo(receitaR01);
        assertThat(painel.semanaPassada().vendas()).as("os 60 dias terminam ontem; a semana anterior tem vendas")
                .isPositive();
        assertThat(painel.topMes()).hasSize(5);
        assertThat(painel.alertas().abaixoDoMinimo()).isPositive();
        assertThat(painel.alertas().precoAbaixoDoCusto()).isPositive();
        assertThat(painel.alertas().caixaAberto()).isEmpty();
        // F18.3: etiquetas e carga de balança registradas na véspera; pendente só o que mudou depois.
        assertThat(painel.alertas().etiquetasPendentes()).isNotNull()
                .isLessThan(resultado.produtos());
        assertThat(painel.alertas().cargaPendente()).isNotNull()
                .isLessThanOrEqualTo(resultado.pesaveisNaBalanca());
    }

    /**
     * F19.5 e o aceite da F19: perfis e NCM no catálogo; o R30 lista só os 8 produtos com inconsistência de propósito e
     * o painel conta os 6 com erro (as duas cervejas só têm aviso).
     */
    @Test
    void cadastroFiscalComAsInconsistenciasDePropositoNoR30ENoPainel() throws Exception {
        assertThat(resultado.perfisTributarios()).isEqualTo(FiscalDemo.perfis().size()).isEqualTo(5);
        assertThat(contar("SELECT count(*) FROM perfil_tributario")).isEqualTo(5);
        assertThat(contar("SELECT count(*) FROM produto WHERE ativo AND ncm IS NOT NULL"))
                .isEqualTo(resultado.produtos() - 1);
        assertThat(t.configuracoes.carregar(admin).loja().uf()).isEqualTo("SP");

        Servicos hoje = Servicos.montar(BancoTeste.dataSource(), HOJE, AcessoTeste.TERMINAL,
                new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        ResultadoRelatorio<RelInconsistenciasFiscais.Linha> r30 = hoje.relatorios().gerar(admin,
                new RelInconsistenciasFiscais(), Filtros.vazios());
        assertThat(r30.linhas().stream().map(RelInconsistenciasFiscais.Linha::produto).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(FiscalDemo.COM_INCONSISTENCIA);
        assertThat(r30.linhas()).extracting(RelInconsistenciasFiscais.Linha::regra)
                .containsExactlyInAnyOrder(1, 1, 4, 5, 9, 10, 11, 11, 13, 13, 16, 16);
        // Os produtos só com avisos (o energético tem o erro da regra 9 e o aviso da 16, F22.5).
        java.util.Set<String> comErro = r30.linhas().stream().filter(RelInconsistenciasFiscais.Linha::erro)
                .map(RelInconsistenciasFiscais.Linha::produto).collect(java.util.stream.Collectors.toSet());
        assertThat(r30.linhas().stream().map(RelInconsistenciasFiscais.Linha::produto).distinct()
                .filter(p -> !comErro.contains(p)).toList())
                .containsExactlyInAnyOrderElementsOf(FiscalDemo.SO_AVISO);
        assertThat(hoje.painel().carregar(admin).alertas().inconsistenciasFiscais())
                .isEqualTo(FiscalDemo.COM_INCONSISTENCIA.size() - FiscalDemo.SO_AVISO.size()).isEqualTo(6);
    }

    /**
     * F22.5: a tabela IBPT de demonstração com os NCM do catálogo, o ST retido em 4 produtos com ST, as vendas com os
     * tributos calculados, e o R31 do período batendo com a soma dos itens, sem as vendas canceladas.
     */
    @Test
    void tributosDaVendaNosDadosDeDemonstracao() throws Exception {
        assertThat(contar("SELECT count(*) FROM ibpt WHERE fonte = 'Demonstração'"))
                .isEqualTo(contar("SELECT count(DISTINCT ncm) FROM produto WHERE ncm IS NOT NULL"));
        assertThat(contar("SELECT count(*) FROM produto WHERE st_icms_retido_unitario IS NOT NULL")).isEqualTo(4);
        assertThat(contar("SELECT count(*) FROM venda_item_tributo")).isPositive();
        assertThat(contar("SELECT count(*) FROM venda_item_tributo WHERE trib_federal IS NULL"))
                .as("todo NCM do catálogo está na tabela").isZero();
        assertThat(contar("SELECT count(*) FROM venda_item_tributo WHERE icms_st_retido IS NOT NULL")).isPositive();

        Servicos hoje = Servicos.montar(BancoTeste.dataSource(), HOJE, AcessoTeste.TERMINAL,
                new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE));
        Periodo periodo = new Periodo(resultado.inicio(), resultado.fim());
        ResultadoRelatorio<br.com.varesync.report.RelImpostosVendas.Linha> r31 = hoje.relatorios().gerar(admin,
                new br.com.varesync.report.RelImpostosVendas(), Filtros.vazios().comPeriodo(periodo));
        BigDecimal aproximados = r31.linhas().stream().map(br.com.varesync.report.RelImpostosVendas.Linha::aproximados)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(aproximados).isEqualByComparingTo(umValor("""
                SELECT sum(t.trib_federal + t.trib_estadual + t.trib_municipal)
                  FROM venda_item_tributo t JOIN venda_item vi ON vi.id = t.venda_item_id AND NOT vi.cancelado
                  JOIN venda v ON v.id = vi.venda_id WHERE v.status = 'FINALIZADA'"""));
        assertThat(umValor("""
                SELECT sum(t.trib_federal + t.trib_estadual + t.trib_municipal)
                  FROM venda_item_tributo t JOIN venda_item vi ON vi.id = t.venda_item_id
                  JOIN venda v ON v.id = vi.venda_id WHERE v.status = 'CANCELADA'""")).as("as canceladas existem")
                .isPositive();
        BigDecimal liquido = r31.linhas().stream().map(br.com.varesync.report.RelImpostosVendas.Linha::valorLiquido)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(liquido).as("R31 inteiro = receita bruta dos itens vendidos").isEqualByComparingTo(umValor("""
                SELECT sum(vi.valor_liquido) FROM venda_item vi JOIN venda v ON v.id = vi.venda_id
                 WHERE v.status = 'FINALIZADA' AND NOT vi.cancelado"""));
        // O guarda-chuva (sem NCM) e a pilha (sem perfil) caem em "Itens sem tributação calculada", se vendidos.
        assertThat(r31.linhas()).filteredOn(br.com.varesync.report.RelImpostosVendas.Linha::semTributacao)
                .allSatisfy(l -> assertThat(l.situacao()).isEqualTo(
                        br.com.varesync.report.RelImpostosVendas.SEM_TRIBUTACAO));
    }

    /** F18.3: as etiquetas de todos os produtos e uma carga de balança sem arquivo, na véspera do primeiro dia. */
    @Test
    void etiquetasECargaDeBalancaNoComecoDoPeriodo() throws Exception {
        assertThat(resultado.etiquetas()).isEqualTo(resultado.produtos());
        assertThat(resultado.pesaveisNaBalanca()).isPositive();
        assertThat(contar("SELECT count(*) FROM etiqueta_impressao WHERE origem = 'PDF'"))
                .isEqualTo(resultado.produtos());
        assertThat(contar("SELECT count(*) FROM etiqueta_impressao WHERE criado_em::date = DATE '"
                + resultado.inicio().minusDays(1) + "'")).isEqualTo(resultado.produtos());
        assertThat(contar("SELECT count(*) FROM carga_balanca WHERE arquivo IS NULL AND criado_em::date = DATE '"
                + resultado.inicio().minusDays(1) + "'")).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM carga_balanca_item")).isEqualTo(resultado.pesaveisNaBalanca());
        assertThat(contar("SELECT count(*) FROM produto WHERE ativo AND unidade = 'KG' AND plu IS NOT NULL"))
                .isEqualTo(resultado.pesaveisNaBalanca());
    }

    @Test
    void relatoriosDeSessentaDiasEmMenosDeTresSegundos() {
        List<Relatorio<?>> todos = Relatorios.todos();
        Filtros filtros = periodo().comDetalhe(false);
        for (Relatorio<?> relatorio : todos) {
            long inicio = System.nanoTime();
            ResultadoRelatorio<?> r = t.relatorios.gerar(admin, relatorio, filtros);
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            LOG.info("{}: {} linhas em {} ms", relatorio.codigo(), r.linhas().size(), ms);
            assertThat(ms).as(relatorio.titulo()).isLessThan(3000);
            if (relatorio instanceof br.com.varesync.report.RelNfceEmitidas) {
                // F23.10: o gerador não emite nem cria NFC-e.
                assertThat(r.linhas()).as(relatorio.titulo()).isEmpty();
            } else {
                assertThat(r.linhas()).as(relatorio.titulo()).isNotEmpty();
            }
        }
        LOG.info("Geração dos dados de demonstração: {} s", segundosGeracao);
    }

    /** F23.10: o gerador não emite nem cria NFC-e e deixa a NFC-e desligada. */
    @Test
    void geradorNaoCriaNfceEDeixaANfceDesligada() throws Exception {
        assertThat(contar("SELECT count(*) FROM nfce")).isZero();
        assertThat(contar("SELECT count(*) FROM nfce_numeracao")).isZero();
        assertThat(contar("SELECT count(*) FROM nfce_evento")).isZero();
        assertThat(contar("SELECT count(*) FROM nfce_inutilizacao")).isZero();
        assertThat(contar("SELECT count(*) FROM venda WHERE consumidor_documento IS NOT NULL")).isZero();
        assertThat(t.configuracoes.carregar(admin).nfce().ativa()).isFalse();
    }

    /**
     * Aceite F10 (PDF grande): o R04 de 60 dias sem filtro de produto passa de 50 páginas e pede confirmação; com um
     * produto, fica pequeno e não pergunta.
     */
    @Test
    void r04DeSessentaDiasSemProdutoPedeConfirmacaoAntesDoPdf() {
        ResultadoRelatorio<RelMovimentacao.Linha> todos = t.relatorios.gerar(admin, new RelMovimentacao(), periodo());
        int paginas = RelatorioPdf.estimarPaginas(todos);
        LOG.info("R04 de 60 dias sem produto: {} linhas, cerca de {} páginas", todos.linhas().size(), paginas);
        assertThat(paginas).isGreaterThan(RelatorioPdf.PAGINAS_PARA_CONFIRMAR);
        assertThat(RelatorioPdf.avisoPdfGrande(todos)).hasValueSatisfying(aviso -> assertThat(aviso)
                .startsWith("O PDF terá cerca de " + paginas + " páginas")
                .contains("filtre por um produto, um tipo de movimento ou um período menor"));

        long produto = todos.linhas().get(todos.linhas().size() - 1).produtoId();
        ResultadoRelatorio<RelMovimentacao.Linha> umProduto = t.relatorios.gerar(admin, new RelMovimentacao(),
                periodo().comProduto(t.relatorios.produto(admin, String.valueOf(produto))));
        assertThat(RelatorioPdf.avisoPdfGrande(umProduto)).isEmpty();
        assertThat(umProduto.linhas().get(0).descricaoTipo()).isEqualTo("Saldo anterior");
    }
}
