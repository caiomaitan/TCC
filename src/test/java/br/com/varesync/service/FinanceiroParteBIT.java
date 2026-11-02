package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosTituloPagar;
import br.com.varesync.model.FinanceiroPainel;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.MotivoPerda;
import br.com.varesync.model.Painel;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Agrupamento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.FiltroCrediario;
import br.com.varesync.report.FiltroFinanceiro;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.FluxoCaixa;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.RelContasPagar;
import br.com.varesync.report.RelDre;
import br.com.varesync.report.RelFluxoCaixa;
import br.com.varesync.report.RelRentabilidadeProduto;
import br.com.varesync.report.RelVendasPeriodo;
import br.com.varesync.report.Relatorio;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.SituacaoTitulo;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Financeiro, parte B (F15.4 a F15.7): DRE com as linhas do aceite (taxa do cartão, diferença de caixa, competência,
 * compra de mercadoria fora), fluxo de caixa (saldo inicial e transferências fora do realizado, ajustes à parte, "Em
 * atraso" no primeiro dia da projeção), R23 com os filtros, painel financeiro e a permissão FINANCEIRO.
 */
class FinanceiroParteBIT extends IntegracaoBase {

    private static final YearMonth OUTUBRO = YearMonth.of(2026, 10);

    private AcessoTeste t;
    private Sessao admin;
    private long cofre;
    private long banco;
    private long produto;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        cofre = t.contaPorNome("Cofre");
        banco = t.contaPorNome("Banco");
        // Estoque inicial de 100 (AJUSTE_ENTRADA "Estoque inicial", que o DRE deixa de fora da linha 7).
        produto = t.produtos.criar(admin, new DadosProduto("Panela", null, "UN", v("100.00"), v("60.00"), null, null,
                List.of(new CodigoBarras("7891000315507", true)), new BigDecimal("100")));
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private void integrar() {
        t.configuracoes.salvar(admin, t.configuracoes.carregar(admin).comIntegrarCaixa(true));
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private long vender(FormaPagamento forma) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), BigDecimal.ONE);
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(forma, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private long categoria(String nome) {
        return t.contasPagar.categorias(admin).stream().filter(c -> c.nome().equals(nome)).findFirst().orElseThrow()
                .id();
    }

    private long despesa(String categoria, String valor, LocalDate competencia, LocalDate vencimento) {
        return t.contasPagar.criar(admin, new DadosTituloPagar(categoria(categoria), categoria + " de teste",
                competencia, vencimento, null, null, null, v(valor)), 0).get(0);
    }

    private ResultadoRelatorio<RelDre.Linha> dre(YearMonth mes) {
        return t.relatorios.gerar(admin, new RelDre(), Filtros.vazios()
                .comFinanceiro(new FiltroFinanceiro(null, null, mes)));
    }

    private static RelDre.Linha linha(ResultadoRelatorio<RelDre.Linha> dre, String chave) {
        return dre.linhas().stream().filter(l -> l.chave().equals(chave)).findFirst().orElseThrow();
    }

    /**
     * Aceite F15 (recebível de cartão e caixa e Cofre) no DRE: R$ 3,00 de taxa em despesas financeiras e −R$ 1,00 de
     * diferença de caixa; mais perda, despesa operacional, juros pagos e desconto obtido. A receita líquida e o lucro
     * bruto são os do R01 e do R05.
     */
    @Test
    void dreDoMesComAsLinhasDoAceite() {
        integrar();
        FormaPagamento credito = forma(TipoFormaPagamento.CARTAO_CREDITO);
        t.configuracoes.alterarRecebimentoForma(admin, credito.id(), v("3"), 30, banco);
        t.caixa.abrir(admin, v("100.00"));
        vender(credito);
        t.relogio.avancar(Duration.ofMinutes(1));
        t.caixa.registrarMovimento(admin, TipoCaixaMovimento.SANGRIA, v("50.00"), "Recolhimento ao cofre", null);
        t.relogio.avancar(Duration.ofMinutes(1));
        t.caixa.fechar(admin, v("49.00"));
        t.estoque.ajustar(admin, produto, TipoAjuste.PERDA, BigDecimal.ONE, MotivoPerda.AVARIA.rotulo(), "caiu");
        long energia = despesa("Energia", "100.00", hoje(), hoje());
        t.contasPagar.pagar(admin, energia, banco, hoje(), v("100.00"), v("2.00"), v("1.00"));

        ResultadoRelatorio<RelDre.Linha> dre = dre(OUTUBRO);
        assertThat(linha(dre, "1").valor()).isEqualByComparingTo("100.00");
        assertThat(linha(dre, "4").valor()).isEqualByComparingTo("100.00");
        assertThat(linha(dre, "5").valor()).isEqualByComparingTo("-60.00");
        assertThat(linha(dre, "6").valor()).isEqualByComparingTo("40.00");
        assertThat(linha(dre, "6").percentual()).as("margem bruta").isEqualByComparingTo("40.00");
        assertThat(linha(dre, "7").valor()).as("perda de 1 a R$ 60, sem o estoque inicial")
                .isEqualByComparingTo("-60.00");
        assertThat(linha(dre, "9").valor()).isEqualByComparingTo("-100.00");
        assertThat(linha(dre, "9:Energia").valor()).isEqualByComparingTo("-100.00");
        assertThat(linha(dre, "11:Taxas").valor()).as("taxa do cartão").isEqualByComparingTo("-3.00");
        assertThat(linha(dre, "11:Juros pagos").valor()).isEqualByComparingTo("-2.00");
        assertThat(linha(dre, "11").valor()).isEqualByComparingTo("-5.00");
        assertThat(linha(dre, "12").valor()).as("desconto obtido").isEqualByComparingTo("1.00");
        assertThat(linha(dre, "13").valor()).as("diferença de caixa").isEqualByComparingTo("-1.00");
        assertThat(linha(dre, "14").valor()).isEqualByComparingTo("-125.00"); // 40 − 60 − 100 − 5 + 1 − 1
        assertThat(linha(dre, "14").percentual()).as("margem líquida").isEqualByComparingTo("-125.00");
        assertThat(linha(dre, "4").valorAnterior()).as("setembro sem vendas").isEqualByComparingTo("0.00");
        assertThat(linha(dre, "4").percentualAnterior()).isNull();
        assertThat(dre.colunas()).extracting(Coluna::titulo).containsExactly("Linha", "10/2026", "% 10/2026",
                "09/2026", "% 09/2026");

        Filtros mes = Filtros.vazios().comPeriodo(new Periodo(OUTUBRO.atDay(1), OUTUBRO.atEndOfMonth()));
        BigDecimal receitaR01 = Coluna.soma(t.relatorios.gerar(admin, new RelVendasPeriodo(), mes).linhas(),
                RelVendasPeriodo.Linha::receita);
        BigDecimal lucroR05 = Coluna.soma(t.relatorios.gerar(admin, new RelRentabilidadeProduto(), mes).linhas(),
                RelRentabilidadeProduto.Linha::lucro);
        assertThat(linha(dre, "4").valor()).isEqualByComparingTo(receitaR01);
        assertThat(linha(dre, "6").valor()).isEqualByComparingTo(lucroR05);
    }

    /**
     * Aceite F15 (competência e compra de mercadoria): aluguel de março pago em abril entra no DRE de março e sai no
     * fluxo de caixa de abril; as duplicatas de uma entrada não aparecem como despesa.
     */
    @Test
    void competenciaNoDreSaidaNoFluxoEMercadoriaForaDoDre() {
        long aluguel = despesa("Aluguel", "2000.00", LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 10));
        t.contasPagar.pagar(admin, aluguel, banco, LocalDate.of(2026, 4, 10), v("2000.00"), null, null);
        long fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Teste Ltda", "Teste",
                "12ABC34501DE35", null, null, null, null, null, null));
        long entrada = t.compras.criar(admin, new DadosEntrada(fornecedor, "777", LocalDate.of(2026, 3, 20),
                v("120.00")));
        t.compras.adicionarItem(admin, entrada, produto, v("2"), v("60.0000"), null);
        t.compras.salvarParcelas(admin, entrada, List.of(new ParcelaEntrada(0, hoje().plusDays(30), v("60.00")),
                new ParcelaEntrada(0, hoje().plusDays(60), v("60.00"))));
        t.compras.confirmar(admin, entrada);

        ResultadoRelatorio<RelDre.Linha> marco = dre(YearMonth.of(2026, 3));
        assertThat(linha(marco, "9").valor()).isEqualByComparingTo("-2000.00");
        assertThat(linha(marco, "9:Aluguel").valor()).isEqualByComparingTo("-2000.00");
        assertThat(marco.linhas()).as("compras de mercadoria ficam fora")
                .noneMatch(l -> l.descricao().equals("Compra de mercadorias"));
        assertThat(linha(marco, "14").valor()).isEqualByComparingTo("-2000.00");
        ResultadoRelatorio<RelDre.Linha> abril = dre(YearMonth.of(2026, 4));
        assertThat(linha(abril, "9").valor()).isEqualByComparingTo("0.00");
        assertThat(linha(abril, "9:Aluguel").valor()).as("só no mês anterior").isNull();
        assertThat(linha(abril, "9:Aluguel").valorAnterior()).isEqualByComparingTo("-2000.00");

        FluxoCaixa fluxo = t.fluxoCaixa.calcular(admin, new Periodo(LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30)), Agrupamento.MES);
        assertThat(fluxo.saldoInicial()).isEqualByComparingTo("0.00");
        assertThat(fluxo.lancamentos()).singleElement().satisfies(l -> {
            assertThat(l.parte()).isEqualTo(FluxoCaixa.Parte.REALIZADO);
            assertThat(l.descricao()).isEqualTo("Pagamento");
            assertThat(l.saidas()).isEqualByComparingTo("2000.00");
        });
        assertThat(fluxo.saldoFinal()).isEqualByComparingTo("-2000.00");
        assertThat(t.fluxoCaixa.calcular(admin, new Periodo(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
                Agrupamento.MES).lancamentos()).as("a competência não mexe no fluxo").isEmpty();
    }

    /**
     * F15.4: o saldo inicial e as transferências ficam fora do realizado (o saldo inicial entra no saldo de partida),
     * os ajustes têm linha própria, o que venceu e está em aberto vai para a linha "Em atraso" de hoje, e o saldo
     * projetado parte do saldo atual.
     */
    @Test
    void fluxoDeCaixaRealizadoPrevistoEEmAtraso() {
        t.contas.lancarSaldoInicial(admin, cofre, v("1000.00"));
        t.caixa.abrir(admin, v("100.00")); // integração desligada: o caixa não movimenta contas
        FormaPagamento debito = forma(TipoFormaPagamento.CARTAO_DEBITO);
        FormaPagamento credito = forma(TipoFormaPagamento.CARTAO_CREDITO);
        t.configuracoes.alterarRecebimentoForma(admin, debito.id(), v("1"), 1, banco);
        t.configuracoes.alterarRecebimentoForma(admin, credito.id(), v("3"), 30, banco);
        vender(debito); // previsto para amanhã: R$ 99,00
        vender(credito); // previsto para daqui a 30 dias, depois do fim do período
        despesa("Energia", "300.00", hoje(), hoje().plusDays(1));
        despesa("Internet e telefone", "100.00", hoje(), hoje().plusDays(6));
        t.relogio.avancar(Duration.ofDays(3)); // hoje = 09/10: o débito e a energia ficaram para trás
        t.contas.transferir(admin, cofre, banco, v("200.00"), "Depósito");
        t.contas.ajustar(admin, banco, v("10.00"), "Tarifa devolvida");
        t.contas.ajustar(admin, cofre, v("-5.00"), "Diferença na contagem");
        t.contas.lancarSaldoInicial(admin, banco, v("500.00"));

        Periodo outubro = new Periodo(OUTUBRO.atDay(1), OUTUBRO.atEndOfMonth());
        FluxoCaixa fluxo = t.fluxoCaixa.calcular(admin, outubro, Agrupamento.DIA);
        assertThat(fluxo.saldoAtual()).isEqualByComparingTo("1505.00");
        assertThat(fluxo.saldoInicial()).as("os dois saldos iniciais").isEqualByComparingTo("1500.00");
        LocalDate hoje = hoje();
        assertThat(fluxo.lancamentos()).extracting(FluxoCaixa.Lancamento::grupo, FluxoCaixa.Lancamento::parte,
                FluxoCaixa.Lancamento::descricao).containsExactly(
                org.assertj.core.groups.Tuple.tuple(hoje, FluxoCaixa.Parte.REALIZADO, FluxoCaixa.AJUSTES),
                org.assertj.core.groups.Tuple.tuple(hoje, FluxoCaixa.Parte.PREVISTO, FluxoCaixa.EM_ATRASO),
                org.assertj.core.groups.Tuple.tuple(hoje.plusDays(3), FluxoCaixa.Parte.PREVISTO,
                        FluxoCaixa.CONTAS_A_PAGAR));
        FluxoCaixa.Lancamento ajustes = fluxo.lancamentos().get(0);
        assertThat(ajustes.entradas()).isEqualByComparingTo("10.00");
        assertThat(ajustes.saidas()).isEqualByComparingTo("5.00");
        FluxoCaixa.Lancamento atraso = fluxo.lancamentos().get(1);
        assertThat(atraso.entradas()).as("débito não conciliado").isEqualByComparingTo("99.00");
        assertThat(atraso.saidas()).as("energia vencida").isEqualByComparingTo("300.00");
        assertThat(fluxo.saldoFinal()).isEqualByComparingTo("1204.00"); // 1505 + 99 − 300 − 100
        assertThat(fluxo.grupos()).hasSize(31);
        FluxoCaixa.Grupo diaDeHoje = fluxo.grupos().get(hoje.getDayOfMonth() - 1);
        assertThat(diaDeHoje.saldoFinal()).isEqualByComparingTo("1304.00");

        FluxoCaixa semanal = t.fluxoCaixa.calcular(admin, outubro, Agrupamento.SEMANA);
        assertThat(semanal.grupos()).extracting(FluxoCaixa.Grupo::rotulo).startsWith("Semana de 01/10",
                "Semana de 05/10", "Semana de 12/10");
        assertThat(semanal.lancamentos()).filteredOn(l -> l.descricao().equals(FluxoCaixa.EM_ATRASO))
                .singleElement().extracting(FluxoCaixa.Lancamento::grupo).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(semanal.saldoFinal()).isEqualByComparingTo(fluxo.saldoFinal());

        ResultadoRelatorio<RelFluxoCaixa.Linha> r24 = t.relatorios.gerar(admin, new RelFluxoCaixa(),
                Filtros.vazios().comPeriodo(outubro));
        assertThat(r24.linhas().get(0).saldo()).isEqualByComparingTo("1500.00");
        assertThat(r24.linhas()).hasSize(4);
        List<String> totais = r24.totais();
        assertThat(totais.get(0)).isEqualTo("Total (4 linhas)");
        assertThat(totais.get(3)).endsWith("109,00");
        assertThat(totais.get(4)).endsWith("405,00");
        assertThat(totais.get(5)).as("saldo projetado no fim").endsWith("1.204,00");

        assertThatThrownBy(() -> t.fluxoCaixa.calcular(admin, new Periodo(hoje.plusDays(1), hoje.plusDays(5)),
                Agrupamento.DIA)).isInstanceOf(NegocioException.class).hasMessageContaining("começa hoje ou antes");
    }

    @Test
    void r23PorSituacaoCategoriaFornecedorEVencimento() {
        long fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Energia Paulista SA", "Energia", null,
                null, null, null, null, null, null));
        long vencida = t.contasPagar.criar(admin, new DadosTituloPagar(categoria("Energia"), "Luz", null,
                hoje().minusDays(3), fornecedor, "123", null, v("300.00")), 0).get(0);
        long paga = despesa("Aluguel", "2000.00", null, hoje().minusDays(1));
        t.contasPagar.pagar(admin, paga, banco, hoje(), v("2000.00"), null, null);
        long cancelada = despesa("Aluguel", "50.00", null, hoje());
        t.contasPagar.cancelar(admin, cancelada, "Lançada em dobro");
        long aVencer = despesa("Marketing", "80.00", null, hoje().plusDays(10));

        assertThat(r23(SituacaoTitulo.EM_ABERTO, null, null, null)).extracting(RelContasPagar.Linha::id)
                .containsExactly(vencida, aVencer);
        List<RelContasPagar.Linha> vencidas = r23(SituacaoTitulo.VENCIDOS, null, null, null);
        assertThat(vencidas).singleElement().satisfies(l -> {
            assertThat(l.situacao()).isEqualTo("Vencida");
            assertThat(l.diasAtraso()).isEqualTo(3L);
            assertThat(l.fornecedor()).isEqualTo("Energia Paulista SA");
        });
        assertThat(r23(SituacaoTitulo.QUITADOS, null, null, null)).extracting(RelContasPagar.Linha::situacao)
                .containsExactly("Paga");
        assertThat(r23(SituacaoTitulo.CANCELADOS, null, null, null)).extracting(RelContasPagar.Linha::id)
                .containsExactly(cancelada);
        assertThat(r23(SituacaoTitulo.TODOS, new Opcao(categoria("Aluguel"), "Aluguel"), null, null))
                .extracting(RelContasPagar.Linha::id).containsExactly(paga, cancelada);
        assertThat(r23(SituacaoTitulo.TODOS, null, new Opcao(fornecedor, "Energia"), null))
                .extracting(RelContasPagar.Linha::id).containsExactly(vencida);
        assertThat(r23(SituacaoTitulo.TODOS, null, null, hoje())).extracting(RelContasPagar.Linha::id)
                .containsExactly(cancelada, aVencer);
    }

    private List<RelContasPagar.Linha> r23(SituacaoTitulo situacao, Opcao categoria, Opcao fornecedor,
                                           LocalDate vencimentoDe) {
        return t.relatorios.gerar(admin, new RelContasPagar(), Filtros.vazios().comFornecedor(fornecedor)
                .comCrediario(new FiltroCrediario(situacao, null, vencimentoDe, null, null))
                .comFinanceiro(new FiltroFinanceiro(categoria, null, null))).linhas();
    }

    /** F15.6: saldo das contas, resultado do mês e os alertas de contas a pagar e de conta negativa. */
    @Test
    void painelComSaldosResultadoEAlertas() {
        t.contas.lancarSaldoInicial(admin, cofre, v("1000.00"));
        t.contas.ajustar(admin, banco, v("-50.00"), "Tarifa");
        despesa("Energia", "300.00", hoje(), hoje().minusDays(1));
        despesa("Aluguel", "100.00", hoje(), hoje());
        despesa("Marketing", "70.00", hoje(), hoje().plusDays(1));
        t.caixa.abrir(admin, v("100.00"));
        vender(forma(TipoFormaPagamento.DINHEIRO));

        Painel painel = t.painel.carregar(admin);
        FinanceiroPainel f = painel.financeiro();
        assertThat(f.saldoTotal()).isEqualByComparingTo("950.00");
        assertThat(f.contas()).extracting(c -> c.nome()).containsExactly("Cofre", "Banco");
        assertThat(f.resultadoMes()).as("lucro de 40 − 300 − 100 − 70 por competência")
                .isEqualByComparingTo("-430.00");
        assertThat(f.inicioMes()).isEqualTo(OUTUBRO.atDay(1));
        assertThat(painel.alertas().pagarVencidas().quantidade()).isEqualTo(1);
        assertThat(painel.alertas().pagarVencidas().valor()).isEqualByComparingTo("300.00");
        assertThat(painel.alertas().pagarHoje().quantidade()).isEqualTo(1);
        assertThat(painel.alertas().pagarHoje().valor()).isEqualByComparingTo("100.00");
        assertThat(painel.alertas().contasNegativas()).extracting(c -> c.nome()).containsExactly("Banco");
        assertThat(painel.alertas().algum()).isTrue();

        t.usuarios.criar(admin, "gerente", "Gerente", false, EnumSet.of(Permissao.RELATORIOS, Permissao.PDV),
                "inicial1", "inicial1");
        Painel semFinanceiro = t.painel.carregar(t.acesso.entrar("gerente", "inicial1"));
        assertThat(semFinanceiro.financeiro()).isNull();
        assertThat(semFinanceiro.alertas().pagarVencidas()).isNull();
        assertThat(semFinanceiro.alertas().contasNegativas()).isEmpty();
    }

    /** R23, R24 e R25 pedem RELATORIOS e FINANCEIRO; a aba do fluxo, FINANCEIRO. */
    @Test
    void relatoriosFinanceirosEFluxoExigemFinanceiro() {
        t.usuarios.criar(admin, "gerente", "Gerente", false, EnumSet.of(Permissao.RELATORIOS), "inicial1",
                "inicial1");
        Sessao gerente = t.acesso.entrar("gerente", "inicial1");
        assertThat(t.relatorios.disponiveis(gerente)).extracting(Relatorio::codigo).doesNotContain("R23", "R24", "R25")
                .contains("R01", "R22");
        assertThat(t.relatorios.opcoes(gerente).categoriasDespesa()).isEmpty();
        assertThatThrownBy(() -> t.relatorios.gerar(gerente, new RelDre(), Filtros.vazios()))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.fluxoCaixa.calcular(gerente, new Periodo(hoje(), hoje()), Agrupamento.DIA))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThat(t.relatorios.disponiveis(admin)).extracting(Relatorio::codigo).contains("R23", "R24", "R25");
        assertThat(t.relatorios.opcoes(admin).categoriasDespesa()).hasSize(12);
        assertThat(t.relatorios.gerar(admin, new RelDre(), Filtros.vazios()).filtros().financeiro().mes())
                .as("sem mês, o de hoje").isEqualTo(OUTUBRO);
        assertThat(t.relatorios.gerar(admin, new RelDre(), Filtros.vazios()).linhas())
                .as("14 linhas e os 4 detalhes fixos das linhas 11 e 12").hasSize(18);
    }
}
