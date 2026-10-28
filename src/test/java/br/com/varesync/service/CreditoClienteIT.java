package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.ExtratoCredito;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.MovimentoCredito;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoCreditoCliente;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelDadosCliente;
import br.com.varesync.report.RelSaldosCredito;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Crédito do cliente e cashback (F12.3 a F12.5) e a trava da anonimização (F12.6), com os critérios de aceite da F12:
 * cashback de 1% e estorno, uso do crédito com cashback sobre o resto, limite do pagamento e anonimização recusada com
 * saldo. A {@code vw_cliente_credito_inconsistente} é conferida ao fim de cada teste ({@code IntegracaoBase}).
 */
class CreditoClienteIT extends IntegracaoBase {

    private AcessoTeste t;
    private VendaService vendas;
    private Sessao admin;
    private long arroz;
    private long maria;
    private FormaPagamento dinheiro;
    private FormaPagamento credito;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        vendas = t.vendas;
        admin = t.supervisor();
        arroz = t.produtos.criar(admin, new DadosProduto("Arroz 5 kg", null, "UN", new BigDecimal("10.00"),
                new BigDecimal("6.00"), null, null, List.of(new CodigoBarras("7896006716112", true)),
                new BigDecimal("1000")));
        maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        dinheiro = forma(TipoFormaPagamento.DINHEIRO);
        credito = forma(TipoFormaPagamento.CREDITO_CLIENTE);
        fidelidade(true, "1", "0");
        t.caixa.abrir(admin, BigDecimal.ZERO);
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private void fidelidade(boolean ativo, String pct, String minima) {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comFidelidade(new Configuracoes.Fidelidade(ativo, new BigDecimal(pct),
                new BigDecimal(minima))));
    }

    /** Carrinho de {@code unidades} × R$ 10,00, com o cliente identificado (ou sem cliente). */
    private Carrinho carrinho(Long cliente, int unidades) {
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(admin, carrinho, vendas.produtoParaVenda(admin, arroz), BigDecimal.valueOf(unidades));
        if (cliente != null) {
            vendas.identificarCliente(admin, carrinho, cliente);
        }
        return carrinho;
    }

    /** Paga {@code comCredito} com o crédito do cliente e o resto em dinheiro. */
    private VendaService.VendaFinalizada vender(Long cliente, int unidades, String comCredito) {
        Carrinho carrinho = carrinho(cliente, unidades);
        Recebimento r = vendas.recebimento(admin, carrinho);
        if (comCredito != null) {
            r.adicionar(credito, new BigDecimal(comCredito));
        }
        if (!r.quitado()) {
            r.adicionar(dinheiro, r.falta());
        }
        t.relogio.avancar(Duration.ofMinutes(1));
        return vendas.finalizar(admin, carrinho, r);
    }

    private BigDecimal saldo(long cliente) {
        return t.credito.extrato(admin, cliente).saldo();
    }

    private List<TipoCreditoCliente> tipos(long cliente) {
        return t.credito.extrato(admin, cliente).movimentos().stream().map(MovimentoCredito::tipo).toList();
    }

    private void executar(String sql) throws SQLException {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // ----- Aceite F12 -----

    @Test
    void cashbackDeUmPorCentoEmVendaDeCemReaisEEstornoNoCancelamento() throws Exception {
        VendaService.VendaFinalizada venda = vender(maria, 10, null);
        assertThat(venda.total()).isEqualByComparingTo("100.00");
        assertThat(venda.cashback()).isEqualByComparingTo("1.00");
        assertThat(saldo(maria)).isEqualByComparingTo("1.00");
        assertThat(venda.comprovante().texto()).containsPattern("Cashback gerado +R\\$ 1,00")
                .containsPattern("Saldo de crédito +R\\$ 1,00");

        vendas.cancelar(admin, venda.vendaId(), "Cliente desistiu", null);
        assertThat(saldo(maria)).isEqualByComparingTo("0.00");
        assertThat(tipos(maria)).containsExactly(TipoCreditoCliente.CASHBACK, TipoCreditoCliente.ESTORNO_CASHBACK);
        assertThat(vendas.comprovante(admin, venda.vendaId()).texto()).contains("Cashback (estornado)");
        assertThat(t.auditoria(AcaoAuditoria.VENDA_CANCELADA).get(0))
                .endsWith("Crédito do cliente: estorno de cashback de R$ 1,00");
    }

    @Test
    void usoDoCreditoComCashbackSobreORestoENaoEntraNoDinheiroDoCaixa() throws Exception {
        vender(maria, 10, null); // saldo R$ 1,00
        VendaService.VendaFinalizada venda = vender(maria, 1, "1.00");
        assertThat(venda.cashback()).as("1% de R$ 9,00").isEqualByComparingTo("0.09");
        assertThat(saldo(maria)).isEqualByComparingTo("0.09");
        assertThat(venda.troco()).isEqualByComparingTo("0.00");
        assertThat(venda.comprovante().texto()).containsPattern("Crédito do cliente +R\\$ 1,00")
                .containsPattern("Saldo de crédito +R\\$ 0,09");
        assertThat(t.contar("SELECT valor * 100 FROM venda_pagamento vp JOIN forma_pagamento fp "
                + "ON fp.id = vp.forma_pagamento_id WHERE fp.tipo = 'CREDITO_CLIENTE' AND vp.venda_id = "
                + venda.vendaId())).isEqualTo(100);
        assertThat(t.contar("SELECT dinheiro_esperado * 100 FROM vw_caixa_resumo WHERE status = 'ABERTO'"))
                .as("R$ 100,00 + R$ 9,00 em dinheiro; o crédito não entra na gaveta").isEqualTo(10900);

        vendas.cancelar(admin, venda.vendaId(), "Erro na forma de pagamento", null);
        assertThat(saldo(maria)).as("devolve o crédito usado e retira o cashback").isEqualByComparingTo("1.00");
        assertThat(tipos(maria)).containsExactly(TipoCreditoCliente.CASHBACK, TipoCreditoCliente.USO_EM_VENDA,
                TipoCreditoCliente.CASHBACK, TipoCreditoCliente.ESTORNO_USO, TipoCreditoCliente.ESTORNO_CASHBACK);
    }

    @Test
    void pagarComCreditoAcimaDoSaldoOuSemClienteERecusado() throws Exception {
        vender(maria, 10, null); // saldo R$ 1,00
        Recebimento r = vendas.recebimento(admin, carrinho(maria, 1));
        assertThat(r.creditoDisponivel()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.00"));
        assertThatThrownBy(() -> r.adicionar(credito, new BigDecimal("1.01")))
                .isInstanceOf(NegocioException.class).hasMessage("Saldo de crédito insuficiente: o cliente tem "
                        + "R$ 1,00 disponível.");
        r.adicionar(credito, new BigDecimal("0.60"));
        assertThatThrownBy(() -> r.adicionar(credito, new BigDecimal("0.50"))).as("somando as parcelas")
                .hasMessageContaining("R$ 0,40 disponível");

        Recebimento semCliente = vendas.recebimento(admin, carrinho(null, 1));
        assertThatThrownBy(() -> semCliente.adicionar(credito, new BigDecimal("1.00")))
                .hasMessageContaining("identifique o cliente (F11)");

        // O saldo foi usado em outro caixa depois do pagamento montado: a finalização confere de novo e recusa.
        Carrinho primeiro = carrinho(maria, 1);
        Recebimento pagamento = vendas.recebimento(admin, primeiro);
        pagamento.adicionar(credito, new BigDecimal("1.00"));
        pagamento.adicionar(dinheiro, pagamento.falta());
        vender(maria, 1, "1.00");
        long vendasAntes = t.contar("SELECT count(*) FROM venda");
        assertThatThrownBy(() -> vendas.finalizar(admin, primeiro, pagamento))
                .hasMessageContaining("Saldo de crédito insuficiente: o cliente tem R$ 0,09");
        assertThat(t.contar("SELECT count(*) FROM venda")).as("nada gravado").isEqualTo(vendasAntes);
    }

    @Test
    void estornoPodeDeixarOSaldoNegativoQueEAbatidoPelosProximosCreditos() {
        long primeira = vender(maria, 10, null).vendaId(); // +1,00
        vender(maria, 1, "1.00"); // −1,00 e +0,09
        vendas.cancelar(admin, primeira, "Cliente desistiu", null); // −1,00
        assertThat(saldo(maria)).isEqualByComparingTo("-0.91");
        Recebimento r = vendas.recebimento(admin, carrinho(maria, 1));
        assertThat(r.creditoDisponivel()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.00"));
        assertThatThrownBy(() -> r.adicionar(credito, new BigDecimal("0.01")))
                .hasMessageContaining("Saldo de crédito insuficiente");

        vender(maria, 100, null); // +10,00
        assertThat(saldo(maria)).isEqualByComparingTo("9.09");
        ResultadoRelatorio<RelSaldosCredito.Linha> r19 = t.relatorios.gerar(admin, new RelSaldosCredito(),
                Filtros.vazios());
        assertThat(r19.linhas()).extracting(RelSaldosCredito.Linha::cliente).containsExactly("Maria da Silva");
        assertThat(r19.totais()).contains("R$ 9,09");
    }

    @Test
    void anonimizarERecusadoComSaldoDiferenteDeZeroEAceitoDepoisDoAjuste() throws Exception {
        vender(maria, 10, null);
        assertThatThrownBy(() -> t.clientes.anonimizar(admin, maria, "Maria da Silva"))
                .hasMessageContaining("tem saldo de crédito de R$ 1,00 e não pode ser anonimizado");

        assertThat(t.credito.ajustar(admin, maria, new BigDecimal("-1.00"), "  Zerado a pedido da titular "))
                .isEqualByComparingTo("0.00");
        assertThat(t.auditoria(AcaoAuditoria.CREDITO_AJUSTADO)).containsExactly("Crédito do cliente nº " + maria
                + " ajustado em -R$ 1,00 (saldo de R$ 1,00 para R$ 0,00). Motivo: Zerado a pedido da titular");
        t.clientes.anonimizar(admin, maria, "Maria da Silva");

        ExtratoCredito extrato = t.credito.extrato(admin, maria);
        assertThat(extrato.movimentos()).as("o extrato continua").extracting(MovimentoCredito::tipo)
                .containsExactly(TipoCreditoCliente.CASHBACK, TipoCreditoCliente.AJUSTE);
        assertThatThrownBy(() -> t.credito.ajustar(admin, maria, BigDecimal.ONE, "Teste"))
                .hasMessageContaining("anonimizado");
    }

    // ----- Regras -----

    @Test
    void cashbackSoComFidelidadeLigadaClienteECompraMinima() {
        assertThat(vender(null, 10, null).cashback()).as("sem cliente").isEqualByComparingTo("0.00");
        fidelidade(true, "2.5", "50");
        assertThat(vender(maria, 4, null).cashback()).as("abaixo da compra mínima").isEqualByComparingTo("0.00");
        assertThat(vender(maria, 5, null).cashback()).as("2,5% de R$ 50,00").isEqualByComparingTo("1.25");
        VendaService.VendaFinalizada arredonda = vender(maria, 7, null);
        assertThat(arredonda.cashback()).as("2,5% de R$ 70,00 = 1,75").isEqualByComparingTo("1.75");
        fidelidade(false, "2.5", "50");
        VendaService.VendaFinalizada desligada = vender(maria, 10, null);
        assertThat(desligada.cashback()).isEqualByComparingTo("0.00");
        assertThat(desligada.comprovante().texto()).as("sem movimento e sem fidelidade, sem o bloco")
                .doesNotContain("Saldo de crédito");
        assertThat(saldo(maria)).isEqualByComparingTo("3.00");
        assertThat(Configuracoes.Fidelidade.PADRAO.cashback(new BigDecimal("100"))).isEqualByComparingTo("0");
        assertThat(new Configuracoes.Fidelidade(true, BigDecimal.ONE, BigDecimal.ZERO)
                .cashback(new BigDecimal("12.50"))).as("round(0,125) = 0,13").isEqualByComparingTo("0.13");
    }

    @Test
    void ajusteSoDoSupervisorComMotivoEExtratoComAPermissaoClientes() throws Exception {
        t.usuarios.criar(admin, "gerente", "Gerente", false, EnumSet.of(Permissao.CLIENTES, Permissao.PDV),
                "inicial1", "inicial1");
        Sessao gerente = t.acesso.entrar("gerente", "inicial1");
        assertThatThrownBy(() -> t.credito.ajustar(gerente, maria, BigDecimal.TEN, "Brinde"))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.credito.ajustar(admin, maria, BigDecimal.TEN, " "))
                .hasMessage("Informe o motivo do ajuste.");
        assertThatThrownBy(() -> t.credito.ajustar(admin, maria, BigDecimal.ZERO, "Brinde"))
                .hasMessageContaining("positivo credita");
        assertThatThrownBy(() -> t.credito.ajustar(admin, maria, new BigDecimal("1.001"), "Brinde"))
                .hasMessageContaining("2 casas");
        t.credito.ajustar(admin, maria, new BigDecimal("5"), "Brinde de aniversário");
        ExtratoCredito extrato = t.credito.extrato(gerente, maria);
        assertThat(extrato.saldo()).isEqualByComparingTo("5.00");
        assertThat(extrato.movimentos().get(0).origem()).isEqualTo("Brinde de aniversário");
        assertThat(extrato.movimentos().get(0).saldoAnterior()).isEqualByComparingTo("0.00");
        assertThat(extrato.movimentos().get(0).saldoPosterior()).isEqualByComparingTo("5.00");
        assertThat(t.clientes.pesquisarParaVenda(gerente, "52998224725").get(0).saldoCredito())
                .as("o F11 mostra o saldo").isEqualByComparingTo("5.00");

        // Cliente com movimento de crédito não é excluído (o extrato fica), mesmo sem venda.
        assertThatThrownBy(() -> t.clientes.excluir(admin, maria)).isInstanceOf(RegistroEmUsoException.class)
                .hasMessageContaining("movimentos de crédito");
        long semMovimento = t.clientes.criar(admin, DadosCliente.rapido("José Antônio", null));
        t.clientes.excluir(admin, semMovimento);
    }

    @Test
    void exportacaoLgpdTrazOExtratoDeCredito() {
        vender(maria, 10, null);
        ResultadoRelatorio<RelDadosCliente.Linha> dados = t.clientes.exportar(admin, maria);
        List<RelDadosCliente.Linha> extrato = dados.linhas().stream()
                .filter(l -> l.secao().equals(RelDadosCliente.CREDITO)).toList();
        assertThat(extrato).hasSize(2);
        assertThat(extrato.get(0)).isEqualTo(new RelDadosCliente.Linha(RelDadosCliente.CREDITO, "Saldo atual", "",
                new BigDecimal("1.00")));
        assertThat(extrato.get(1).item()).isEqualTo("Cashback");
        assertThat(extrato.get(1).detalhe()).contains("Venda nº").endsWith("saldo R$ 1,00");
    }

    @Test
    void razaoImutavelESaldoSoPeloTrigger() throws Exception {
        vender(maria, 10, null);
        assertThatThrownBy(() -> executar("UPDATE cliente_credito SET saldo = 50 WHERE cliente_id = " + maria))
                .isInstanceOf(SQLException.class).hasMessageContaining("registrando um movimento de crédito");
        assertThatThrownBy(() -> executar("UPDATE cliente_credito_movimento SET valor = 50"))
                .hasMessageContaining("não podem ser alterados");
        assertThatThrownBy(() -> executar("DELETE FROM cliente_credito_movimento"))
                .hasMessageContaining("não podem ser alterados");
        long venda = t.contar("SELECT max(id) FROM venda");
        assertThatThrownBy(() -> executar("INSERT INTO cliente_credito_movimento (cliente_id, tipo, valor, venda_id, "
                + "usuario_id) VALUES (" + maria + ", 'USO_EM_VENDA', -5, " + venda + ", " + admin.usuarioId() + ")"))
                .as("o uso não deixa o saldo negativo").hasMessageContaining("negativo");
        assertThatThrownBy(() -> executar("INSERT INTO cliente_credito_movimento (cliente_id, tipo, valor, venda_id, "
                + "usuario_id) VALUES (" + maria + ", 'CASHBACK', 1, " + venda + ", " + admin.usuarioId() + ")"))
                .as("um cashback por venda").hasMessageContaining("ux_ccm_venda_tipo");
        assertThatThrownBy(() -> executar("INSERT INTO cliente_credito_movimento (cliente_id, tipo, valor, "
                + "usuario_id) VALUES (" + maria + ", 'AJUSTE', 1, " + admin.usuarioId() + ")"))
                .as("ajuste sem motivo").hasMessageContaining("ck_ccm_origem");
        assertThat(t.credito.diagnostico(admin)).isEmpty();
        assertThat(t.contar("SELECT count(*) FROM cliente_credito WHERE cliente_id = " + maria))
                .as("o saldo nasce com o cliente").isEqualTo(1);
    }

    @Test
    void soUmaFormaDeCreditoDoClienteEElaFicaForaDoDinheiroEsperado() throws Exception {
        assertThatThrownBy(() -> t.configuracoes.criarForma(admin, "Crédito 2", TipoFormaPagamento.CREDITO_CLIENTE))
                .hasMessageContaining("Só pode existir uma forma do tipo Crédito do cliente");
        assertThatThrownBy(() -> executar("INSERT INTO forma_pagamento (descricao, tipo) VALUES ('Outro crédito', "
                + "'CREDITO_CLIENTE')")).hasMessageContaining("ux_forma_pagamento_credito_cliente");
        assertThat(credito.descricao()).isEqualTo("Crédito do cliente");
        assertThat(credito.dinheiro()).as("não gera troco").isFalse();
    }
}
