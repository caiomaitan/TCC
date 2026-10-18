package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import br.com.varesync.config.Servicos;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Periodo;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Relatorio;
import br.com.varesync.report.RelEstoqueAtual;
import br.com.varesync.report.RelMaisVendidos;
import br.com.varesync.report.RelMovimentacao;
import br.com.varesync.report.RelRentabilidadeProduto;
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
        assertThat(passos).isEqualTo(resultado.produtos() + resultado.vendas());
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
                .containsExactly(5L);
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
    void relatoriosDeSessentaDiasEmMenosDeTresSegundos() {
        List<Relatorio<?>> todos = List.of(new RelVendasPeriodo(), new RelMaisVendidos(), new RelEstoqueAtual(),
                new RelMovimentacao(), new RelRentabilidadeProduto());
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
}
