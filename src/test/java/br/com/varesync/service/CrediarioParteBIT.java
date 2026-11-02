package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.ComprovanteDevolucao;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.ExtratoCrediario;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.Painel;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.PlanoRecebimento;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.FiltroCrediario;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.RelRecebimentos;
import br.com.varesync.report.RelTitulosReceber;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.SituacaoTitulo;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F14.4: abatimentos por parcela, extrato, limite, configurações, R21, R22, painel e a migration V11. */
class CrediarioParteBIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private long produto;
    private long maria;
    private FormaPagamento pix;
    private FormaPagamento crediario;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        produto = t.produtos.criar(admin, new DadosProduto("Panela", null, "UN", new BigDecimal("100.00"),
                new BigDecimal("60.00"), null, null, List.of(new CodigoBarras("7891000315507", true)),
                new BigDecimal("100")));
        maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        t.clientes.alterarCrediario(admin, maria, new BigDecimal("2000.00"), false);
        pix = t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.PIX).findFirst()
                .orElseThrow();
        crediario = t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.CREDIARIO)
                .findFirst().orElseThrow();
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private long vender(int unidades, int parcelas) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), BigDecimal.valueOf(unidades));
        t.vendas.identificarCliente(admin, carrinho, maria);
        t.relogio.avancar(Duration.ofMinutes(1));
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(crediario, r.falta());
        r.definirPlanoCrediario(new PlanoCrediario(parcelas, hoje().plusDays(30)));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private long itemDaVenda(long venda) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM venda_item WHERE venda_id = " + venda)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private DevolucaoService.DevolucaoRegistrada devolver(long venda, int unidades) throws Exception {
        t.relogio.avancar(Duration.ofMinutes(1));
        return t.devolucoes.registrar(admin, new DadosDevolucao(venda,
                List.of(new DadosDevolucao.Item(itemDaVenda(venda), BigDecimal.valueOf(unidades), false)), "Defeito",
                FormaReembolso.OUTRO, null, null, t.contaDoReembolso(FormaReembolso.OUTRO)), null);
    }

    private void receberTudo(String valor) {
        BigDecimal quantia = valor == null ? null : new BigDecimal(valor);
        PlanoRecebimento plano = t.recebimentos.simular(admin, maria, List.of(), quantia, null);
        Recebimento pagamento = t.recebimentos.pagamento(plano);
        pagamento.adicionar(pix, plano.total());
        t.relogio.avancar(Duration.ofMinutes(1));
        t.recebimentos.registrar(admin, maria, List.of(), quantia, null, pagamento, null, null);
    }

    private List<String> linhasDeAbatimento(long devolucao) throws Exception {
        List<String> linhas = new ArrayList<>();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT t.parcela, dt.valor
                       FROM devolucao_titulo dt JOIN titulo_receber t ON t.id = dt.titulo_id
                      WHERE dt.devolucao_id = %d ORDER BY t.parcela DESC
                     """.formatted(devolucao))) {
            while (rs.next()) {
                linhas.add(rs.getInt(1) + ":" + rs.getBigDecimal(2));
            }
        }
        return linhas;
    }

    @Test
    void cadaDevolucaoGuardaOQueAbateuDeCadaParcela() throws Exception {
        long venda = vender(3, 3);

        DevolucaoService.DevolucaoRegistrada primeira = devolver(venda, 1);
        DevolucaoService.DevolucaoRegistrada segunda = devolver(venda, 1);

        assertThat(linhasDeAbatimento(primeira.id())).containsExactly("3:100.00");
        assertThat(linhasDeAbatimento(segunda.id())).containsExactly("2:100.00");
    }

    @Test
    void extratoDepoisDeDuasDevolucoesMostraQuantoCadaUmaAbateuDeCadaParcela() throws Exception {
        long venda = vender(3, 3);
        DevolucaoService.DevolucaoRegistrada primeira = devolver(venda, 1);
        DevolucaoService.DevolucaoRegistrada segunda = devolver(venda, 1);

        ExtratoCrediario extrato = t.clientes.extratoCrediario(admin, maria);

        List<ExtratoCrediario.Lancamento> abatimentos = extrato.lancamentos().stream()
                .filter(l -> l.tipo().equals("Abatimento")).toList();
        assertThat(abatimentos).hasSize(2);
        assertThat(abatimentos.get(0).documento()).contains("Devolução nº " + primeira.id(), "parcela 3/3",
                "venda nº " + venda);
        assertThat(abatimentos.get(0).valor()).isEqualByComparingTo("-100.00");
        assertThat(abatimentos.get(1).documento()).contains("Devolução nº " + segunda.id(), "parcela 2/3");
        assertThat(extrato.saldoDevedor()).isEqualByComparingTo("100.00");
    }

    @Test
    void extratoTemVendaPagamentoEstornoEOSaldoFechaComODevedor() throws Exception {
        vender(3, 3);
        receberTudo("100.00");
        t.relogio.avancar(Duration.ofMinutes(1));
        long recebimento = t.recebimentos.daSessaoAberta(admin).get(0).id();
        t.recebimentos.estornar(admin, recebimento, "Errado", null);
        receberTudo("50.00");

        ExtratoCrediario e = t.clientes.extratoCrediario(admin, maria);

        assertThat(e.lancamentos()).extracting(ExtratoCrediario.Lancamento::tipo)
                .containsExactly("Venda a prazo", "Pagamento", "Pagamento estornado", "Pagamento");
        assertThat(e.lancamentos().get(e.lancamentos().size() - 1).saldo()).isEqualByComparingTo(e.saldoDevedor());
        assertThat(e.saldoDevedor()).isEqualByComparingTo("250.00");
        assertThat(e.limiteDisponivel()).isEqualByComparingTo("1750.00");
        assertThat(e.parcelas()).hasSize(3);
    }

    @Test
    void extratoR21ER22GeramPdf() throws Exception {
        vender(1, 1);
        receberTudo("10.00");
        ResultadoRelatorio<?> extrato = t.clientes.exportarExtrato(admin, maria);
        assertThat(extrato.linhas()).isNotEmpty();
        assertThat(extrato.relatorio().descreverFiltros(extrato.filtros())).contains("Maria da Silva");

        RelatorioPdf pdf = new RelatorioPdf();
        for (ResultadoRelatorio<?> r : List.of(extrato, r21(FiltroCrediario.VAZIO), r22(null))) {
            Path destino = Files.createTempFile("crediario", ".pdf");
            try {
                pdf.salvar(r, destino);
                assertThat(Files.size(destino)).as(r.relatorio().nome()).isGreaterThan(1000);
            } finally {
                Files.deleteIfExists(destino);
            }
        }
    }

    @Test
    void comprovanteReimpressoMostraAsParcelasComoFicaramNoDia() throws Exception {
        long venda = vender(3, 3);
        DevolucaoService.DevolucaoRegistrada d = devolver(venda, 1);
        receberTudo("50.00");
        devolver(venda, 1);

        ComprovanteDevolucao c = t.devolucoes.comprovante(admin, d.id());

        assertThat(c.parcelasEmAberto()).hasSize(2);
        assertThat(c.parcelasEmAberto().get(0).valor()).as("o pagamento veio depois").isEqualByComparingTo("100.00");
        assertThat(c.parcelasEmAberto().get(1).valor()).as("a outra devolução veio depois")
                .isEqualByComparingTo("100.00");
    }

    @Test
    void limiteEBloqueioPelaPermissaoComAuditoria() throws Exception {
        t.clientes.alterarCrediario(admin, maria, new BigDecimal("500.00"), true);

        ExtratoCrediario e = t.clientes.extratoCrediario(admin, maria);
        assertThat(e.limite()).isEqualByComparingTo("500.00");
        assertThat(e.bloqueado()).isTrue();
        assertThat(t.auditoria(AcaoAuditoria.CLIENTE_CREDIARIO_ALTERADO)).hasSize(2)
                .last().asString().contains("R$ 500,00", "bloqueado").doesNotContain("Maria");

        t.usuarios.criar(admin, "beto", "Beto", false, EnumSet.of(Permissao.CLIENTES), "segredo1", "segredo1");
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE usuario SET deve_trocar_senha = FALSE WHERE login = 'beto'");
        }
        Sessao beto = t.acesso.entrar("beto", "segredo1");
        assertThat(t.clientes.extratoCrediario(beto, maria).limite()).isEqualByComparingTo("500.00");
        assertThatThrownBy(() -> t.clientes.alterarCrediario(beto, maria, BigDecimal.TEN, false))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.clientes.alterarCrediario(admin, maria, new BigDecimal("-1"), false))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void configuracoesDoCrediarioSaoGravadasEAuditadas() throws Exception {
        Configuracoes atuais = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, atuais.comCrediario(new Configuracoes.Crediario(6, 45, new BigDecimal("3.5"),
                new BigDecimal("1"), 5, 60)));

        Configuracoes.Crediario c = t.configuracoes.carregar(admin).crediario();

        assertThat(c.maxParcelas()).isEqualTo(6);
        assertThat(c.diasPrimeira()).isEqualTo(45);
        assertThat(c.jurosMesPct()).isEqualByComparingTo("3.5");
        assertThat(c.multaPct()).isEqualByComparingTo("1");
        assertThat(c.carenciaDias()).isEqualTo(5);
        assertThat(c.bloqueioAtrasoDias()).isEqualTo(60);
        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA)).last().asString()
                .contains("máximo de parcelas do crediário");
        assertThatThrownBy(() -> t.configuracoes.salvar(admin, atuais.comCrediario(new Configuracoes.Crediario(0, 30,
                BigDecimal.ONE, BigDecimal.ONE, 0, 30)))).isInstanceOf(NegocioException.class)
                .hasMessageContaining("parcelas");
        assertThatThrownBy(() -> t.configuracoes.salvar(admin, atuais.comCrediario(new Configuracoes.Crediario(3, 30,
                new BigDecimal("101"), BigDecimal.ONE, 0, 30)))).isInstanceOf(NegocioException.class)
                .hasMessageContaining("juros");
    }

    private ResultadoRelatorio<RelTitulosReceber.Linha> r21(FiltroCrediario f) {
        return t.relatorios.gerar(admin, new RelTitulosReceber(), Filtros.vazios().comCrediario(f));
    }

    @Test
    void r21MostraAsFaixasDeAtraso() {
        vender(1, 1); // vence em +30
        t.relogio.avancar(Duration.ofDays(30 + 45)); // 45 dias de atraso
        vender(1, 1); // vence em +30, a vencer
        ResultadoRelatorio<RelTitulosReceber.Linha> r = r21(FiltroCrediario.VAZIO);

        assertThat(r.linhas()).hasSize(2);
        RelTitulosReceber.Linha vencida = r.linhas().get(0);
        assertThat(vencida.diasAtraso()).isEqualTo(45);
        assertThat(vencida.situacao()).isEqualTo("Vencido");
        assertThat(r.linhas().get(1).situacao()).isEqualTo("A vencer");
        List<String> titulos = r.colunas().stream().map(c -> c.titulo()).toList();
        assertThat(r.totais().get(titulos.indexOf("31–60"))).isEqualTo("R$ 100,00");
        assertThat(r.totais().get(titulos.indexOf("A vencer"))).isEqualTo("R$ 100,00");
        assertThat(r.totais().get(titulos.indexOf("Em aberto"))).isEqualTo("R$ 200,00");
        assertThat(r.totais().get(titulos.indexOf("1–30"))).isEqualTo("R$ 0,00");

        assertThat(r21(new FiltroCrediario(SituacaoTitulo.VENCIDOS, null, null, null, null)).linhas()).hasSize(1);
        assertThat(r21(new FiltroCrediario(SituacaoTitulo.TODOS, "joao", null, null, null)).linhas()).isEmpty();
        assertThat(r21(new FiltroCrediario(SituacaoTitulo.TODOS, "MARIA", null, null, null)).linhas()).hasSize(2);
        assertThat(r21(new FiltroCrediario(SituacaoTitulo.TODOS, "529.982", null, null, null)).linhas()).hasSize(2);
        LocalDate hoje = hoje();
        assertThat(r21(new FiltroCrediario(SituacaoTitulo.TODOS, null, hoje, null, null)).linhas()).hasSize(1);
        assertThat(r21(new FiltroCrediario(SituacaoTitulo.TODOS, null, null, hoje, null)).linhas()).hasSize(1);
        assertThatThrownBy(() -> r21(new FiltroCrediario(null, null, hoje, hoje.minusDays(1), null)))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void r21Quitados() {
        vender(1, 1);
        receberTudo(null);

        assertThat(r21(FiltroCrediario.VAZIO).linhas()).isEmpty();
        assertThat(r21(new FiltroCrediario(SituacaoTitulo.QUITADOS, null, null, null, null)).linhas()).singleElement()
                .satisfies(l -> assertThat(l.pago()).isEqualByComparingTo("100.00"));
    }

    private ResultadoRelatorio<RelRecebimentos.Linha> r22(Opcao forma) {
        return t.relatorios.gerar(admin, new RelRecebimentos(), Filtros.vazios()
                .comPeriodo(new Periodo(hoje().minusDays(1), hoje().plusDays(1)))
                .comCrediario(new FiltroCrediario(null, null, null, null, forma)));
    }

    @Test
    void r22TemPrincipalMultaEJurosESemEstornados() {
        vender(1, 1);
        t.relogio.avancar(Duration.ofDays(40));
        receberTudo(null);
        vender(1, 1);
        receberTudo(null);
        t.relogio.avancar(Duration.ofMinutes(1));
        t.recebimentos.estornar(admin, t.recebimentos.daSessaoAberta(admin).get(0).id(), "Errado", null);

        ResultadoRelatorio<RelRecebimentos.Linha> r = r22(null);

        assertThat(r.linhas()).singleElement().satisfies(l -> {
            assertThat(l.principal()).isEqualByComparingTo("100.00");
            assertThat(l.multa()).isEqualByComparingTo("2.00");
            assertThat(l.juros()).isEqualByComparingTo("0.67");
            assertThat(l.total()).isEqualByComparingTo("102.67");
            assertThat(l.formas()).isEqualTo("PIX");
        });
        assertThat(r22(new Opcao(pix.id(), "PIX")).linhas()).hasSize(1);
        FormaPagamento dinheiro = t.vendas.formasAtivas(admin).get(0);
        assertThat(r22(new Opcao(dinheiro.id(), "Dinheiro")).linhas()).isEmpty();
    }

    @Test
    void painelAvisaTitulosVencidosSoParaQuemTemRecebimento() throws Exception {
        vender(1, 1);
        Painel antes = t.painel.carregar(admin);
        assertThat(antes.alertas().titulosVencidos().quantidade()).isZero();

        t.relogio.avancar(Duration.ofDays(31));

        Painel depois = t.painel.carregar(admin);
        assertThat(depois.alertas().titulosVencidos().quantidade()).isEqualTo(1);
        assertThat(depois.alertas().titulosVencidos().valor()).isEqualByComparingTo("100.00");
        assertThat(depois.alertas().algum()).isTrue();

        t.usuarios.criar(admin, "beto", "Beto", false, EnumSet.of(Permissao.PDV), "segredo1", "segredo1");
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE usuario SET deve_trocar_senha = FALSE WHERE login = 'beto'");
        }
        assertThat(t.painel.carregar(t.acesso.entrar("beto", "segredo1")).alertas().titulosVencidos()).isNull();
    }

    @Test
    void r11MostraOsRecebimentos() {
        vender(1, 1);
        FormaPagamento dinheiro = t.vendas.formasAtivas(admin).get(0);
        PlanoRecebimento plano = t.recebimentos.simular(admin, maria, List.of(), null, null);
        Recebimento pagamento = t.recebimentos.pagamento(plano);
        pagamento.adicionar(dinheiro, plano.total());
        t.recebimentos.registrar(admin, maria, List.of(), null, null, pagamento, null, null);

        var r = t.caixa.fechar(admin, new BigDecimal("200.00"));

        assertThat(r.resumo().recebimentosDinheiro()).isEqualByComparingTo("100.00");
        assertThat(r.recebimentosPorForma()).singleElement().satisfies(f -> {
            assertThat(f.forma()).isEqualTo("Dinheiro");
            assertThat(f.quantidadeVendas()).isEqualTo(1);
        });
        assertThat(r.situacao()).isEqualTo("Sem diferença");
    }

    /**
     * A migration V11 num esquema próprio: V1 a V10, duas devoluções gravadas como na parte A, sem linhas, e aí a V11. Os
     * abatimentos saem da última parcela para a primeira, na ordem das devoluções.
     */
    @Test
    void migrationPreencheAsDevolucoesAntigas() throws Exception {
        String esquema = "teste_v11";
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + esquema + " CASCADE");
        }
        try {
            Flyway.configure().dataSource(dataSource).schemas(esquema).target("10").load().migrate();
            try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
                st.execute("SET search_path TO " + esquema);
                st.execute("""
                        INSERT INTO usuario (login, nome, senha_hash, supervisor)
                        VALUES ('usuario', 'U', 'x', TRUE)
                        """);
                st.execute("""
                        INSERT INTO cliente (nome, criado_por, atualizado_por)
                        SELECT 'C', id, id FROM usuario
                        """);
                st.execute("""
                        INSERT INTO caixa_sessao (terminal, usuario_id, valor_abertura)
                        SELECT 'T', id, 0 FROM usuario
                        """);
                st.execute("""
                        INSERT INTO venda (caixa_sessao_id, usuario_id, valor_bruto, valor_total, cliente_id)
                        SELECT s.id, s.usuario_id, 300, 300, c.id FROM caixa_sessao s, cliente c
                        """);
                st.execute("""
                        INSERT INTO titulo_receber (cliente_id, venda_id, parcela, total_parcelas, emissao, vencimento,
                                                    valor, valor_abatido, status, criado_por)
                        SELECT v.cliente_id, v.id, p, 3, DATE '2026-10-01', DATE '2026-11-01' + p, 100,
                               CASE p WHEN 3 THEN 100 WHEN 2 THEN 50 ELSE 0 END,
                               CASE p WHEN 3 THEN 'QUITADO' ELSE 'ABERTO' END, v.usuario_id
                          FROM venda v, generate_series(1, 3) p
                        """);
                st.execute("""
                        INSERT INTO devolucao (venda_id, usuario_id, motivo, forma_reembolso, valor_total, valor_abatido)
                        SELECT id, usuario_id, 'x', 'ABATIMENTO', 120, 120 FROM venda
                        """);
                st.execute("""
                        INSERT INTO devolucao (venda_id, usuario_id, motivo, forma_reembolso, valor_total, valor_abatido)
                        SELECT id, usuario_id, 'y', 'ABATIMENTO', 30, 30 FROM venda
                        """);
            }
            Flyway.configure().dataSource(dataSource).schemas(esquema).load().migrate();
            try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
                st.execute("SET search_path TO " + esquema);
                List<String> linhas = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("""
                        SELECT d.motivo, t.parcela, dt.valor
                          FROM devolucao_titulo dt
                          JOIN devolucao d      ON d.id = dt.devolucao_id
                          JOIN titulo_receber t ON t.id = dt.titulo_id
                         ORDER BY d.id, t.parcela DESC
                        """)) {
                    while (rs.next()) {
                        linhas.add(rs.getString(1) + rs.getInt(2) + ":" + rs.getBigDecimal(3));
                    }
                }
                assertThat(linhas).containsExactly("x3:100.00", "x2:20.00", "y2:30.00");
                assertThat(BancoTeste.contar(con, "SELECT count(*) FROM " + esquema + ".vw_abatimento_inconsistente"))
                        .isZero();
                st.execute("SET search_path TO public");
            }
        } finally {
            try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
                st.execute("SET search_path TO public");
                st.execute("DROP SCHEMA IF EXISTS " + esquema + " CASCADE");
            }
        }
    }

    @Test
    void viewAchaAbatidoDiferenteDaSoma() throws Exception {
        long venda = vender(1, 1);
        devolver(venda, 1);
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("UPDATE titulo_receber SET valor_abatido = 50, status = 'ABERTO' WHERE venda_id = "
                        + venda);
                assertThat(BancoTeste.contar(con, "SELECT count(*) FROM vw_abatimento_inconsistente")).isEqualTo(1);
            } finally {
                con.rollback();
            }
        }
        assertThat(t.clientes.diagnosticoAbatimentos(admin)).isEmpty();
    }
}
