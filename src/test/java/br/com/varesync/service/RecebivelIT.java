package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.PlanoRecebimento;
import br.com.varesync.model.Recebivel;
import br.com.varesync.model.StatusRecebivel;
import br.com.varesync.model.TipoFormaPagamento;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Recebíveis de cartão e PIX (F15.2): nascem na venda e no recebimento do crediário, com taxa e prazo da forma; o
 * cancelamento e o estorno cancelam os previstos e estornam os recebidos; a conciliação lança na conta.
 */
class RecebivelIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private long banco;
    private long produto;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        banco = t.contaPorNome("Banco");
        produto = t.produtos.criar(admin, new DadosProduto("Panela", null, "UN", new BigDecimal("100.00"),
                new BigDecimal("60.00"), null, null, List.of(new CodigoBarras("7891000315507", true)),
                new BigDecimal("100")));
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private BigDecimal saldoBanco() {
        return t.contas.listar(admin).stream().filter(c -> c.id() == banco).findFirst().orElseThrow().saldo();
    }

    private long vender(FormaPagamento forma) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), BigDecimal.ONE);
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(forma, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private List<Recebivel> recebiveis() {
        return t.recebiveis.listar(admin, null, null, null, null);
    }

    @Test
    void cartaoDeCreditoComTaxaEPrazoEConciliacaoDoAceite() {
        FormaPagamento credito = forma(TipoFormaPagamento.CARTAO_CREDITO);
        t.configuracoes.alterarRecebimentoForma(admin, credito.id(), new BigDecimal("3"), 30, banco);
        vender(credito);

        Recebivel r = recebiveis().get(0);
        assertThat(r.valorBruto()).isEqualByComparingTo("100.00");
        assertThat(r.taxa()).isEqualByComparingTo("3.00");
        assertThat(r.valorLiquido()).isEqualByComparingTo("97.00");
        assertThat(r.previstoPara()).isEqualTo(hoje().plusDays(30));
        assertThat(r.status()).isEqualTo(StatusRecebivel.PREVISTO);
        assertThat(saldoBanco()).isEqualByComparingTo("0.00");

        assertThatThrownBy(() -> t.recebiveis.conciliar(admin, Map.of(r.id(), r.valorLiquido()), hoje(),
                new BigDecimal("90.00"))).isInstanceOf(NegocioException.class).hasMessageContaining("é diferente");
        assertThatThrownBy(() -> t.recebiveis.conciliar(admin, Map.of(r.id(), r.valorLiquido()), hoje().plusDays(1),
                null)).hasMessageContaining("depois de hoje");
        assertThat(t.recebiveis.conciliar(admin, Map.of(r.id(), r.valorLiquido()), hoje(), new BigDecimal("97.00")))
                .isEqualByComparingTo("97.00");
        assertThat(saldoBanco()).isEqualByComparingTo("97.00");
        assertThat(recebiveis().get(0).status()).isEqualTo(StatusRecebivel.RECEBIDO);
        assertThatThrownBy(() -> t.recebiveis.conciliar(admin, Map.of(r.id(), r.valorLiquido()), hoje(), null))
                .hasMessageContaining("não está mais previsto");
    }

    @Test
    void liquidoCorrigidoNaConciliacaoMudaATaxa() {
        FormaPagamento debito = forma(TipoFormaPagamento.CARTAO_DEBITO);
        t.configuracoes.alterarRecebimentoForma(admin, debito.id(), new BigDecimal("1"), 1, banco);
        vender(debito);
        Recebivel r = recebiveis().get(0);
        assertThatThrownBy(() -> t.recebiveis.conciliar(admin, Map.of(r.id(), new BigDecimal("100.01")), hoje(),
                null)).hasMessageContaining("passa do valor bruto");
        t.recebiveis.conciliar(admin, Map.of(r.id(), new BigDecimal("98.50")), hoje(), null);
        Recebivel recebido = recebiveis().get(0);
        assertThat(recebido.valorLiquido()).isEqualByComparingTo("98.50");
        assertThat(recebido.taxa()).isEqualByComparingTo("1.50");
        assertThat(saldoBanco()).isEqualByComparingTo("98.50");
    }

    @Test
    void pixNoMesmoDiaEntraNaHoraECancelarAVendaEstornaDoAceite() {
        long venda = vender(forma(TipoFormaPagamento.PIX));
        assertThat(recebiveis().get(0).status()).isEqualTo(StatusRecebivel.RECEBIDO);
        assertThat(saldoBanco()).isEqualByComparingTo("100.00");

        t.vendas.cancelar(admin, venda, "Cliente desistiu", null);
        assertThat(recebiveis().get(0).status()).isEqualTo(StatusRecebivel.CANCELADO);
        assertThat(saldoBanco()).isEqualByComparingTo("0.00");
        assertThat(t.contas.extrato(admin, banco, new br.com.varesync.model.Periodo(hoje(), hoje())))
                .extracting(m -> m.tipo().name()).containsExactly("RECEBIVEL", "ESTORNO_RECEBIVEL");
    }

    @Test
    void cancelarAVendaCancelaORecebivelPrevistoSemMovimento() {
        FormaPagamento credito = forma(TipoFormaPagamento.CARTAO_CREDITO);
        t.configuracoes.alterarRecebimentoForma(admin, credito.id(), new BigDecimal("3"), 30, banco);
        long venda = vender(credito);
        t.vendas.cancelar(admin, venda, "Cliente desistiu", null);
        assertThat(recebiveis().get(0).status()).isEqualTo(StatusRecebivel.CANCELADO);
        assertThat(saldoBanco()).isEqualByComparingTo("0.00");
    }

    @Test
    void dinheiroEFormaSemContaNaoGeramRecebivel() {
        vender(forma(TipoFormaPagamento.DINHEIRO));
        FormaPagamento debito = forma(TipoFormaPagamento.CARTAO_DEBITO);
        t.configuracoes.alterarRecebimentoForma(admin, debito.id(), BigDecimal.ZERO, 0, null);
        vender(debito);
        assertThat(recebiveis()).isEmpty();
    }

    @Test
    void configuracaoDaFormaValidaTaxaDiasEConta() {
        int dinheiro = forma(TipoFormaPagamento.DINHEIRO).id();
        int pix = forma(TipoFormaPagamento.PIX).id();
        assertThatThrownBy(() -> t.configuracoes.alterarRecebimentoForma(admin, pix, new BigDecimal("20.01"), 0,
                banco)).hasMessage("A taxa deve estar entre 0 e 20%.");
        assertThatThrownBy(() -> t.configuracoes.alterarRecebimentoForma(admin, pix, BigDecimal.ZERO, 61, banco))
                .hasMessageContaining("entre 0 e 60");
        assertThatThrownBy(() -> t.configuracoes.alterarRecebimentoForma(admin, dinheiro, BigDecimal.ZERO, 0, banco))
                .hasMessageContaining("não tem conta de destino");
        t.configuracoes.alterarRecebimentoForma(admin, pix, new BigDecimal("0.99"), 2, banco);
        assertThat(t.configuracoes.formasPagamento(admin)).filteredOn(f -> f.id() == pix).singleElement()
                .extracting(FormaPagamentoCadastro::diasRecebimento).isEqualTo(2);
    }

    @Test
    void recebimentoDoCrediarioNoPixGeraRecebivelEOEstornoEstorna() {
        long maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        t.clientes.alterarCrediario(admin, maria, new BigDecimal("1000.00"), false);
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), BigDecimal.ONE);
        t.vendas.identificarCliente(admin, carrinho, maria);
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(forma(TipoFormaPagamento.CREDIARIO), r.falta());
        r.definirPlanoCrediario(new PlanoCrediario(1, hoje().plusDays(30)));
        t.vendas.finalizar(admin, carrinho, r);
        assertThat(recebiveis()).isEmpty(); // crediário não movimenta dinheiro na venda

        PlanoRecebimento plano = t.recebimentos.simular(admin, maria, List.of(), new BigDecimal("40.00"), null);
        Recebimento pagamento = t.recebimentos.pagamento(plano);
        pagamento.adicionar(forma(TipoFormaPagamento.PIX), plano.total());
        t.relogio.avancar(Duration.ofMinutes(1));
        long recebimento = t.recebimentos.registrar(admin, maria, List.of(), new BigDecimal("40.00"), null, pagamento,
                null, null).id();
        assertThat(recebiveis()).singleElement().satisfies(x -> {
            assertThat(x.origem()).isEqualTo("Recebimento nº " + recebimento);
            assertThat(x.status()).isEqualTo(StatusRecebivel.RECEBIDO);
        });
        assertThat(saldoBanco()).isEqualByComparingTo("40.00");

        t.recebimentos.estornar(admin, recebimento, "Lançado errado", null);
        assertThat(recebiveis().get(0).status()).isEqualTo(StatusRecebivel.CANCELADO);
        assertThat(saldoBanco()).isEqualByComparingTo("0.00");
    }
}
