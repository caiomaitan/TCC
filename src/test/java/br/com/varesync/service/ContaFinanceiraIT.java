package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.MovimentoConta;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.TipoConta;
import br.com.varesync.model.TipoContaMovimento;
import br.com.varesync.model.TipoFormaPagamento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contas financeiras (F15.1): razão e saldo protegidos, integração com o caixa, lançamentos manuais e o reembolso OUTRO
 * da devolução só com a parte não abatida. {@code IntegracaoBase} confere {@code vw_conta_inconsistente} ao fim.
 */
class ContaFinanceiraIT extends IntegracaoBase {

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
        produto = t.produtos.criar(admin, new DadosProduto("Panela", null, "UN", new BigDecimal("100.00"),
                new BigDecimal("60.00"), null, null, List.of(new CodigoBarras("7891000315507", true)),
                new BigDecimal("100")));
    }

    private void integrar(boolean ligado) {
        t.configuracoes.salvar(admin, t.configuracoes.carregar(admin).comIntegrarCaixa(ligado));
    }

    private BigDecimal saldo(long conta) {
        return t.contas.listar(admin).stream().filter(c -> c.id() == conta).findFirst().orElseThrow().saldo();
    }

    private List<MovimentoConta> extrato(long conta) {
        LocalDate hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
        return t.contas.extrato(admin, conta, new br.com.varesync.model.Periodo(hoje.minusYears(1), hoje));
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    @Test
    void caixaECofreDoAceite() {
        integrar(true);
        assertThat(t.caixa.contasParaAbrir(admin)).extracting(ContaFinanceira::nome).contains("Cofre", "Banco");
        t.caixa.abrir(admin, v("100.00"));
        t.relogio.avancar(Duration.ofMinutes(1));
        t.caixa.registrarMovimento(admin, TipoCaixaMovimento.SANGRIA, v("50.00"), "Recolhimento ao cofre", null);
        t.relogio.avancar(Duration.ofMinutes(1));
        var fechamento = t.caixa.fechar(admin, v("49.00"));

        assertThat(fechamento.sessao().diferenca()).isEqualByComparingTo("-1.00");
        assertThat(extrato(cofre)).extracting(MovimentoConta::tipo, m -> m.valor().toPlainString()).containsExactly(
                org.assertj.core.groups.Tuple.tuple(TipoContaMovimento.ABERTURA_CAIXA, "-100.00"),
                org.assertj.core.groups.Tuple.tuple(TipoContaMovimento.SANGRIA, "50.00"),
                org.assertj.core.groups.Tuple.tuple(TipoContaMovimento.FECHAMENTO_CAIXA, "49.00"));
        assertThat(saldo(cofre)).isEqualByComparingTo("-1.00"); // negativo é permitido: nunca bloqueia o caixa
        assertThat(extrato(cofre).get(2).saldoPosterior()).isEqualByComparingTo("-1.00");
    }

    @Test
    void contaEscolhidaNoSuprimentoENoFechamento() {
        integrar(true);
        t.caixa.abrir(admin, v("100.00"), cofre);
        assertThat(t.caixa.contasDoCaixaAberto(admin)).isNotEmpty();
        t.caixa.registrarMovimento(admin, TipoCaixaMovimento.SUPRIMENTO, v("20.00"), "Reforço de troco", null, banco);
        t.caixa.fechar(admin, v("120.00"), banco);

        assertThat(saldo(cofre)).isEqualByComparingTo("-100.00");
        assertThat(saldo(banco)).isEqualByComparingTo("100.00"); // −20 do suprimento + 120 do fechamento
    }

    @Test
    void integracaoDesligadaESessaoAbertaAntesDeLigarNaoMovimentam() {
        t.caixa.abrir(admin, v("100.00"));
        assertThat(t.caixa.contasDoCaixaAberto(admin)).isEmpty();
        integrar(true); // a sessão continua como foi aberta, até o fechamento
        t.caixa.registrarMovimento(admin, TipoCaixaMovimento.SANGRIA, v("50.00"), "Recolhimento", null);
        t.caixa.fechar(admin, v("50.00"));
        assertThat(extrato(cofre)).isEmpty();

        integrar(false);
        t.caixa.abrir(admin, v("100.00"));
        assertThat(t.caixa.contasParaAbrir(admin)).isEmpty();
        assertThat(extrato(cofre)).isEmpty();
    }

    @Test
    void saldoInicialUmaVezAjusteComMotivoETransferencia() {
        t.contas.lancarSaldoInicial(admin, cofre, v("500.00"));
        assertThatThrownBy(() -> t.contas.lancarSaldoInicial(admin, cofre, v("10.00")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("já tem saldo inicial");
        assertThatThrownBy(() -> t.contas.ajustar(admin, cofre, v("-5.00"), " "))
                .isInstanceOf(NegocioException.class).hasMessage("Informe o motivo do ajuste.");
        t.contas.ajustar(admin, cofre, v("-5.00"), "Diferença na contagem");
        t.contas.transferir(admin, cofre, banco, v("300.00"), "Depósito");
        assertThatThrownBy(() -> t.contas.transferir(admin, cofre, cofre, v("1.00"), ""))
                .isInstanceOf(NegocioException.class);

        assertThat(saldo(cofre)).isEqualByComparingTo("195.00");
        assertThat(saldo(banco)).isEqualByComparingTo("300.00");
        assertThat(extrato(banco)).singleElement().satisfies(m -> {
            assertThat(m.tipo()).isEqualTo(TipoContaMovimento.TRANSFERENCIA);
            assertThat(m.descricao()).isEqualTo("Transferência de Cofre: Depósito");
        });
    }

    @Test
    void cadastroDaContaEInativacao() {
        long caixinha = t.contas.criar(admin, "Caixinha", TipoConta.OUTRA, true);
        assertThat(t.contas.listar(admin)).filteredOn(ContaFinanceira::padraoCaixa).extracting(ContaFinanceira::nome)
                .containsExactly("Caixinha");
        assertThatThrownBy(() -> t.contas.criar(admin, "cofre", TipoConta.COFRE, false))
                .hasMessage("Já existe uma conta com este nome");
        assertThatThrownBy(() -> t.contas.alterar(admin, caixinha, "Caixinha", TipoConta.OUTRA, false, false))
                .hasMessageContaining("padrão do caixa");
        assertThatThrownBy(() -> t.contas.alterar(admin, banco, "Banco", TipoConta.BANCO, false, false))
                .hasMessageContaining("recebe as formas de pagamento");
        t.contas.alterar(admin, cofre, "Cofre", TipoConta.COFRE, true, true);
        t.contas.ajustar(admin, caixinha, v("1.00"), "Teste");
        assertThatThrownBy(() -> t.contas.alterar(admin, caixinha, "Caixinha", TipoConta.OUTRA, false, false))
                .hasMessageContaining("tem saldo");
        t.contas.ajustar(admin, caixinha, v("-1.00"), "Teste");
        t.contas.alterar(admin, caixinha, "Caixinha", TipoConta.OUTRA, false, false);
        assertThatThrownBy(() -> t.contas.ajustar(admin, caixinha, v("1.00"), "Teste"))
                .hasMessageContaining("inativa");
    }

    @Test
    void lancamentosExigemAPermissaoFinanceiro() {
        t.usuarios.criar(admin, "caixa", "Caixa", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao caixa = t.acesso.entrar("caixa", "inicial1");
        assertThatThrownBy(() -> t.contas.listar(caixa)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.contas.transferir(caixa, cofre, banco, v("1.00"), ""))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.contas.lancarSaldoInicial(caixa, cofre, v("1.00")))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.contas.ajustar(caixa, cofre, v("1.00"), "x"))
                .isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void razaoImutavelESaldoProtegido() throws Exception {
        t.contas.ajustar(admin, cofre, v("10.00"), "Teste");
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE conta_saldo SET saldo = 0"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("registrando um movimento");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE conta_movimento SET valor = 1"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("não podem ser alterados");
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM conta_movimento"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("não podem ser alterados");
        }
    }

    /** F15.1: o reembolso OUTRO sai de uma conta só com a parte que não abateu parcelas do crediário. */
    @Test
    void reembolsoOutroSoComAParteNaoAbatida() throws Exception {
        long maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        t.clientes.alterarCrediario(admin, maria, v("1000.00"), false);
        t.caixa.abrir(admin, v("100.00"));
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), BigDecimal.ONE);
        t.vendas.identificarCliente(admin, carrinho, maria);
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(forma(TipoFormaPagamento.DINHEIRO), v("40.00"));
        r.adicionar(forma(TipoFormaPagamento.CREDIARIO), r.falta());
        r.definirPlanoCrediario(new PlanoCrediario(1, LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault())
                .plusDays(30)));
        long venda = t.vendas.finalizar(admin, carrinho, r).vendaId();
        long item = t.devolucoes.venda(admin, venda).itens().get(0).vendaItemId();
        List<DadosDevolucao.Item> itens = List.of(new DadosDevolucao.Item(item, BigDecimal.ONE, false));
        t.relogio.avancar(Duration.ofMinutes(1));

        assertThatThrownBy(() -> t.devolucoes.registrar(admin, new DadosDevolucao(venda, itens, "Defeito",
                FormaReembolso.OUTRO, null, null, null), null))
                .isInstanceOf(NegocioException.class).hasMessage("Escolha a conta de onde saiu o reembolso.");
        var devolucao = t.devolucoes.registrar(admin, new DadosDevolucao(venda, itens, "Defeito",
                FormaReembolso.OUTRO, null, null, banco), null);

        assertThat(devolucao.comprovante().valorAbatido()).isEqualByComparingTo("60.00");
        assertThat(extrato(banco)).singleElement().satisfies(m -> {
            assertThat(m.tipo()).isEqualTo(TipoContaMovimento.REEMBOLSO);
            assertThat(m.valor()).isEqualByComparingTo("-40.00");
        });
    }

    @Test
    void devolucaoAbatidaPorInteiroNaoMovimentaConta() {
        long maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        t.clientes.alterarCrediario(admin, maria, v("1000.00"), false);
        t.caixa.abrir(admin, v("100.00"));
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), BigDecimal.ONE);
        t.vendas.identificarCliente(admin, carrinho, maria);
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(forma(TipoFormaPagamento.CREDIARIO), r.falta());
        r.definirPlanoCrediario(new PlanoCrediario(1, LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault())
                .plusDays(30)));
        long venda = t.vendas.finalizar(admin, carrinho, r).vendaId();
        long item = t.devolucoes.venda(admin, venda).itens().get(0).vendaItemId();
        t.relogio.avancar(Duration.ofMinutes(1));
        t.devolucoes.registrar(admin, new DadosDevolucao(venda, List.of(new DadosDevolucao.Item(item, BigDecimal.ONE,
                false)), "Defeito", FormaReembolso.OUTRO, null, null, null), null);
        assertThat(extrato(banco)).isEmpty();
    }
}
