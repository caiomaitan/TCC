package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import br.com.varesync.config.Servicos;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DiaPainel;
import br.com.varesync.model.Painel;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelAlteracoesPreco;
import br.com.varesync.report.RelCancelamentos;
import br.com.varesync.report.RelClientes;
import br.com.varesync.report.RelComprasFornecedor;
import br.com.varesync.report.RelCurvaAbc;
import br.com.varesync.report.RelFormasPagamento;
import br.com.varesync.report.RelMovimentacao;
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
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gerador de dados de demonstração (6.14), rodado uma vez para a classe toda (60 dias, pelos próprios services):
 * o que a especificação pede foi criado, o banco recusa uma segunda geração, só o supervisor gera, R01 e R05 batem
 * no período e o relatório de 60 dias fica abaixo de 3 s (9.4). O estoque continua consistente
 * ({@link IntegracaoBase}).
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
                .as("as 5 formas da V2 e o crédito do cliente").containsExactly(6L);
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
        assertThat(r05.totais().get(7)).isEqualTo(r01.totais().get(6));

        assertThat(receita).isEqualByComparingTo(
                umValor("SELECT sum(valor_total) FROM venda WHERE status = 'FINALIZADA'"));
        assertThat(receita).isLessThan(umValor("SELECT sum(valor_total) FROM venda"));
        BigDecimal lucroDasTabelas = umValor("""
                SELECT sum(vi.valor_liquido - vi.custo_total) FROM venda_item vi JOIN venda v ON v.id = vi.venda_id
                 WHERE v.status = 'FINALIZADA' AND NOT vi.cancelado
                """);
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
        assertThat(r06.totais().get(4)).as("margem do período, igual à do R01").isEqualTo(r01.totais().get(6));

        ResultadoRelatorio<RelCurvaAbc.Linha> r07 = t.relatorios.gerar(admin, new RelCurvaAbc(), periodo());
        assertThat(Coluna.soma(r07.linhas(), RelCurvaAbc.Linha::valor)).isEqualByComparingTo(receita);
        assertThat(r07.linhas()).extracting(RelCurvaAbc.Linha::classe).contains("A", "B", "C");

        ResultadoRelatorio<RelFormasPagamento.Linha> r08 = t.relatorios.gerar(admin, new RelFormasPagamento(),
                periodo());
        assertThat(r08.linhas()).hasSize(6);
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::valor)).isEqualByComparingTo(receita);
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
            assertThat(r.linhas()).as(relatorio.titulo()).isNotEmpty();
        }
        LOG.info("Geração dos dados de demonstração: {} s", segundosGeracao);
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
