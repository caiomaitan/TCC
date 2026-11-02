package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CategoriaDespesa;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosTituloPagar;
import br.com.varesync.model.GrupoDespesa;
import br.com.varesync.model.MovimentoConta;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusTituloPagar;
import br.com.varesync.model.TituloPagar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contas a pagar (F15.3): competência, pagamento parcial com juros e desconto, estorno, repetição e parcelas da entrada. */
class ContaPagarIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private long banco;
    private long aluguel;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        banco = t.contaPorNome("Banco");
        aluguel = categoria("Aluguel");
    }

    private long categoria(String nome) {
        return t.contasPagar.categorias(admin).stream().filter(c -> c.nome().equals(nome)).findFirst().orElseThrow()
                .id();
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private TituloPagar titulo(long id) {
        return t.contasPagar.listar(admin, null, null, null, null).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow();
    }

    private long despesa(String valor, LocalDate competencia, LocalDate vencimento) {
        return t.contasPagar.criar(admin, new DadosTituloPagar(aluguel, "Aluguel da loja", competencia, vencimento,
                null, null, null, v(valor)), 0).get(0);
    }

    private BigDecimal saldoBanco() {
        return t.contas.listar(admin).stream().filter(c -> c.id() == banco).findFirst().orElseThrow().saldo();
    }

    @Test
    void competenciaEmMarcoPagoEmAbrilDoAceite() {
        long id = despesa("2000.00", LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 10));
        assertThat(titulo(id).competencia()).isEqualTo(LocalDate.of(2026, 3, 1));
        t.contasPagar.pagar(admin, id, banco, LocalDate.of(2026, 4, 10), v("2000.00"), null, null);

        assertThat(titulo(id).status()).isEqualTo(StatusTituloPagar.PAGO);
        List<MovimentoConta> extrato = t.contas.extrato(admin, banco, new Periodo(LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30)));
        assertThat(extrato).singleElement().satisfies(m -> {
            assertThat(m.valor()).isEqualByComparingTo("-2000.00");
            assertThat(m.dataHora().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 4, 10));
        });
        assertThat(saldoBanco()).isEqualByComparingTo("-2000.00");
    }

    @Test
    void competenciaVaziaValeOMesDoVencimentoEDataFuturaERecusada() {
        long id = despesa("50.00", null, LocalDate.of(2026, 11, 20));
        assertThat(titulo(id).competencia()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThatThrownBy(() -> t.contasPagar.pagar(admin, id, banco, hoje().plusDays(1), v("50.00"), null, null))
                .hasMessageContaining("depois de hoje");
    }

    @Test
    void pagamentoParcialComJurosEDescontoEstornoECancelamento() {
        long id = despesa("100.00", null, hoje());
        assertThatThrownBy(() -> t.contasPagar.pagar(admin, id, banco, hoje(), v("100.01"), null, null))
                .hasMessageContaining("passa do que está em aberto");
        long primeiro = t.contasPagar.pagar(admin, id, banco, hoje(), v("60.00"), v("2.00"), null);
        assertThat(titulo(id).emAberto()).isEqualByComparingTo("40.00");
        assertThat(titulo(id).status()).isEqualTo(StatusTituloPagar.ABERTO);
        t.contasPagar.pagar(admin, id, banco, hoje(), v("40.00"), null, v("1.00"));
        assertThat(titulo(id).status()).isEqualTo(StatusTituloPagar.PAGO);
        assertThat(saldoBanco()).isEqualByComparingTo("-101.00"); // 62 + 39

        assertThatThrownBy(() -> t.contasPagar.cancelar(admin, id, "Lançado errado"))
                .hasMessageContaining("Estorne o pagamento");
        assertThatThrownBy(() -> t.contasPagar.estornar(admin, primeiro, " "))
                .hasMessage("Informe o motivo do estorno.");
        t.contasPagar.estornar(admin, primeiro, "Pago na conta errada");
        assertThatThrownBy(() -> t.contasPagar.estornar(admin, primeiro, "De novo"))
                .hasMessageContaining("já foi estornado");
        assertThat(titulo(id).status()).isEqualTo(StatusTituloPagar.ABERTO);
        assertThat(titulo(id).valorPago()).isEqualByComparingTo("40.00");
        assertThat(saldoBanco()).isEqualByComparingTo("-39.00");

        long outro = despesa("10.00", null, hoje());
        t.contasPagar.cancelar(admin, outro, "Lançado em dobro");
        assertThat(titulo(outro).status()).isEqualTo(StatusTituloPagar.CANCELADO);
        assertThatThrownBy(() -> t.contasPagar.pagar(admin, outro, banco, hoje(), v("10.00"), null, null))
                .hasMessageContaining("cancelado");
    }

    @Test
    void repeticaoMensalGeraOsProximosMeses() {
        List<Long> ids = t.contasPagar.criar(admin, new DadosTituloPagar(categoria("Energia"), "Energia", null,
                LocalDate.of(2026, 1, 31), null, null, null, v("300.00")), 3);
        assertThat(ids).hasSize(4);
        assertThat(ids.stream().map(this::titulo).toList()).extracting(TituloPagar::vencimento, TituloPagar::competencia)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 1)),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 2, 1)),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1)),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 1)));
        assertThatThrownBy(() -> t.contasPagar.criar(admin, new DadosTituloPagar(aluguel, "X", null, hoje(), null,
                null, null, v("1.00")), 13)).hasMessageContaining("de 0 a 12");
    }

    @Test
    void categoriaNovaECategoriaInativaRecusada() {
        long id = t.contasPagar.criarCategoria(admin, "Contabilidade", GrupoDespesa.OPERACIONAL);
        assertThatThrownBy(() -> t.contasPagar.criarCategoria(admin, "aluguel", GrupoDespesa.OPERACIONAL))
                .hasMessage("Já existe uma categoria de despesa com este nome");
        t.contasPagar.alterarSituacaoCategoria(admin, id, false);
        assertThatThrownBy(() -> t.contasPagar.criar(admin, new DadosTituloPagar(id, "Honorários", null, hoje(), null,
                null, null, v("1.00")), 0)).hasMessageContaining("inativa");
    }

    @Test
    void entradaComDuasParcelasGeraDoisTitulosDeMercadoriaDoAceite() {
        long fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Teste Ltda", "Teste",
                "12ABC34501DE35", null, null, null, null, null, null));
        long produto = t.produtos.criar(admin, new DadosProduto("Café", null, "UN", v("10.00"), v("5.00"), null, null,
                List.of(new CodigoBarras("7891000315507", true)), null));
        long entrada = t.compras.criar(admin, new DadosEntrada(fornecedor, "777", hoje(), v("120.00")));
        t.compras.adicionarItem(admin, entrada, produto, v("20"), v("6.0000"), null);
        t.compras.salvarParcelas(admin, entrada, List.of(new ParcelaEntrada(0, hoje().plusDays(30), v("60.00")),
                new ParcelaEntrada(0, hoje().plusDays(60), v("50.00"))));
        assertThat(EntradaService.avisoParcelas(t.compras.buscar(admin, entrada), t.compras.parcelas(admin, entrada)))
                .hasValueSatisfying(a -> assertThat(a).contains("R$ 110,00").contains("R$ 120,00"));
        t.compras.salvarParcelas(admin, entrada, List.of(new ParcelaEntrada(0, hoje().plusDays(30), v("60.00")),
                new ParcelaEntrada(0, hoje().plusDays(60), v("60.00"))));
        assertThat(t.compras.parcelas(admin, entrada)).extracting(ParcelaEntrada::numero).containsExactly(1, 2);
        assertThat(EntradaService.avisoParcelas(t.compras.buscar(admin, entrada), t.compras.parcelas(admin, entrada)))
                .isEmpty();

        t.compras.confirmar(admin, entrada);
        List<TituloPagar> titulos = t.contasPagar.listar(admin, null, null, null, null);
        assertThat(titulos).hasSize(2).allSatisfy(x -> {
            assertThat(x.entradaId()).isEqualTo(entrada);
            assertThat(x.fornecedorId()).isEqualTo(fornecedor);
            assertThat(x.categoria()).isEqualTo("Compra de mercadorias");
            assertThat(x.valor()).isEqualByComparingTo("60.00");
        });
        assertThat(titulos).extracting(TituloPagar::documento).containsExactly("777/1", "777/2");
        assertThat(t.contasPagar.categorias(admin)).filteredOn(c -> c.nome().equals("Compra de mercadorias"))
                .extracting(CategoriaDespesa::grupo).containsExactly(GrupoDespesa.MERCADORIA);
        assertThatThrownBy(() -> t.compras.salvarParcelas(admin, entrada, List.of()))
                .hasMessageContaining("já foi confirmada");
    }

    @Test
    void tudoExigeAPermissaoFinanceiro() {
        t.usuarios.criar(admin, "compras", "Compras", false, EnumSet.of(Permissao.COMPRAS), "inicial1", "inicial1");
        Sessao comprador = t.acesso.entrar("compras", "inicial1");
        assertThatThrownBy(() -> t.contasPagar.listar(comprador, null, null, null, null))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.contasPagar.criar(comprador, new DadosTituloPagar(aluguel, "X", null, hoje(), null,
                null, null, v("1.00")), 0)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.recebiveis.listar(comprador, null, null, null, null))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
