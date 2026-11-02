package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.Kardex;
import br.com.varesync.model.MovimentoKardex;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoCreditoCliente;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.model.VendaDevolucao;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelDevolucoes;
import br.com.varesync.report.RelPerdasAjustes;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Devoluções e trocas (F13.2), com os critérios de aceite da F13 que dependem da parte A: devolução em dinheiro com o
 * dinheiro esperado do caixa, desconto rateado com o restante na última devolução, limites (quantidade, prazo e
 * liberação), avariado, vale-troca, cashback, venda com devolução que não se cancela e o cliente anonimizado. As views
 * de consistência do estoque e do crédito são conferidas ao fim de cada teste ({@code IntegracaoBase}).
 */
class DevolucaoServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private VendaService vendas;
    private DevolucaoService devolucoes;
    private Sessao admin;
    private long refri;
    private long arroz;
    private long maria;
    private FormaPagamento dinheiro;
    private FormaPagamento credito;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        vendas = t.vendas;
        devolucoes = t.devolucoes;
        admin = t.supervisor();
        refri = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 2 L", null, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.20"), null, null, List.of(new CodigoBarras("7891000315507", true)),
                new BigDecimal("100")));
        arroz = t.produtos.criar(admin, new DadosProduto("Arroz 5 kg", null, "UN", new BigDecimal("10.00"),
                new BigDecimal("6.00"), null, null, List.of(new CodigoBarras("7896006716112", true)),
                new BigDecimal("100")));
        maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        dinheiro = forma(TipoFormaPagamento.DINHEIRO);
        credito = forma(TipoFormaPagamento.CREDITO_CLIENTE);
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private void fidelidade(boolean ativo) {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comFidelidade(new Configuracoes.Fidelidade(ativo, BigDecimal.ONE,
                BigDecimal.ZERO)));
    }

    /** Vende {@code unidades} do produto, com o cliente (ou sem) e o desconto na venda em R$ (ou sem), em dinheiro. */
    private long vender(long produto, int unidades, Long cliente, String descontoVenda) {
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(admin, carrinho, vendas.produtoParaVenda(admin, produto), BigDecimal.valueOf(unidades));
        if (cliente != null) {
            vendas.identificarCliente(admin, carrinho, cliente);
        }
        if (descontoVenda != null) {
            vendas.aplicarDescontoVenda(admin, carrinho, TipoDesconto.VALOR, new BigDecimal(descontoVenda), null);
        }
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private ItemDevolucao item(long vendaId) {
        return devolucoes.venda(admin, vendaId).itens().get(0);
    }

    private DevolucaoService.DevolucaoRegistrada devolver(Sessao sessao, long vendaId, String quantidade,
                                                         boolean avariado, FormaReembolso forma, Long cliente,
                                                         Liberacao liberacao) {
        t.relogio.avancar(Duration.ofMinutes(1));
        return devolucoes.registrar(sessao, new DadosDevolucao(vendaId, List.of(new DadosDevolucao.Item(
                item(vendaId).vendaItemId(), new BigDecimal(quantidade), avariado)), "Cliente desistiu", forma,
                cliente, null, t.contaDoReembolso(forma)), liberacao);
    }

    private DevolucaoService.DevolucaoRegistrada devolver(long vendaId, String quantidade, FormaReembolso forma) {
        return devolver(admin, vendaId, quantidade, false, forma, null, null);
    }

    private BigDecimal saldoEstoque(long produto) {
        return t.estoque.saldo(admin, produto).saldo();
    }

    private BigDecimal esperado() throws Exception {
        return new BigDecimal(String.valueOf(t.contar("SELECT dinheiro_esperado * 100 FROM vw_caixa_resumo "
                + "WHERE status = 'ABERTO'"))).movePointLeft(2);
    }

    private Kardex kardex(long produto) {
        LocalDate hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
        return t.estoque.kardex(admin, produto, new Periodo(hoje, hoje));
    }

    private BigDecimal saldoCredito(long cliente) {
        return t.credito.extrato(admin, cliente).saldo();
    }

    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    // ----- Aceite F13 -----

    @Test
    void devolucaoEmDinheiroReembolsaVoltaAoEstoqueESaiDoDinheiroEsperado() throws Exception {
        long venda = vender(refri, 3, null, null);
        assertThat(esperado()).isEqualByComparingTo("116.47");
        assertThat(saldoEstoque(refri)).isEqualByComparingTo("97");

        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "1", FormaReembolso.DINHEIRO);

        assertThat(d.valor()).isEqualByComparingTo("5.49");
        assertThat(saldoEstoque(refri)).isEqualByComparingTo("98");
        assertThat(esperado()).as("116,47 − 5,49").isEqualByComparingTo("110.98");
        assertThat(t.contar("SELECT devolucoes_dinheiro * 100 FROM vw_caixa_resumo WHERE status = 'ABERTO'"))
                .isEqualTo(549);
        assertThat(t.contar("SELECT custo_total * 100 FROM devolucao_item")).as("3 × 3,20 = 9,60 ÷ 3").isEqualTo(320);
        Kardex kardex = kardex(refri);
        MovimentoKardex ultimo = kardex.movimentos().get(kardex.movimentos().size() - 1);
        assertThat(ultimo.tipo()).isEqualTo(TipoMovimento.DEVOLUCAO_VENDA);
        assertThat(ultimo.origem()).isEqualTo("Devolução nº " + d.id());
        assertThat(ultimo.custoUnitario()).isEqualByComparingTo("3.20");
        assertThat(d.comprovante().texto()).contains("DEVOLUÇÃO DE MERCADORIA", "Devolução nº " + d.id(),
                "Venda nº " + venda, "NÃO É DOCUMENTO FISCAL").containsPattern("TOTAL DEVOLVIDO +R\\$ 5,49")
                .containsPattern("Reembolso +Dinheiro");
        assertThat(t.auditoria(AcaoAuditoria.DEVOLUCAO_REGISTRADA).get(0)).startsWith("Devolução nº " + d.id()
                + " da venda nº " + venda + ": 1 item(ns), R$ 5,49 em dinheiro (caixa nº ");

        // R11: a devolução aparece no fechamento e o esperado já a descontou.
        var fechamento = t.caixa.fechar(admin, new BigDecimal("110.98"));
        assertThat(fechamento.resumo().devolucoesDinheiro()).isEqualByComparingTo("5.49");
        assertThat(fechamento.situacao()).isEqualTo("Sem diferença");
    }

    @Test
    void descontoRateadoDevolveOProporcionalEORestanteNaUltima() {
        long venda = vender(refri, 3, null, "0.82");
        assertThat(item(venda).valorLiquido()).isEqualByComparingTo("15.65");

        assertThat(devolver(venda, "1", FormaReembolso.OUTRO).valor()).isEqualByComparingTo("5.22");
        assertThat(item(venda).disponivel()).isEqualByComparingTo("2");
        assertThat(devolver(venda, "2", FormaReembolso.OUTRO).valor()).isEqualByComparingTo("10.43");

        ItemDevolucao depois = item(venda);
        assertThat(depois.valorDevolvido()).as("5,22 + 10,43").isEqualByComparingTo("15.65");
        assertThat(depois.custoDevolvido()).isEqualByComparingTo(depois.custoTotal());
        assertThat(depois.disponivel()).isZero();
        assertThat(devolucoes.venda(admin, venda).temDisponivel()).isFalse();
    }

    @Test
    void quantidadeAcimaDaVendidaOuFracionadaEmUnidadeERecusada() throws Exception {
        long venda = vender(refri, 3, null, null);
        assertThatThrownBy(() -> devolver(venda, "4", FormaReembolso.OUTRO))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A quantidade devolvida de \"Refrigerante cola 2 L\" (4 UN) passa do que pode ser "
                        + "devolvido: vendida 3 UN.");
        devolver(venda, "2", FormaReembolso.OUTRO);
        assertThatThrownBy(() -> devolver(venda, "2", FormaReembolso.OUTRO))
                .hasMessageContaining("vendida 3 UN, já devolvida 2 UN.");
        assertThatThrownBy(() -> devolver(venda, "0.5", FormaReembolso.OUTRO))
                .hasMessageContaining("inteir");
        assertThat(t.contar("SELECT count(*) FROM devolucao")).isEqualTo(1);
    }

    @Test
    void foraDoPrazoSoComLiberacaoDeSupervisor() throws Exception {
        long venda = vender(refri, 3, null, null);
        Sessao caixa1 = operador("caixa1", Permissao.PDV, Permissao.DEVOLUCAO);
        t.relogio.avancar(Duration.ofDays(31));
        assertThat(devolucoes.venda(caixa1, venda).foraDoPrazo()).isTrue();

        assertThatThrownBy(() -> devolver(caixa1, venda, "1", false, FormaReembolso.OUTRO, null, null))
                .isInstanceOf(PrazoDevolucaoException.class)
                .hasMessageContaining("o prazo de devolução (30 dias) terminou em")
                .hasMessageEndingWith("A devolução precisa da liberação de um supervisor.");
        // Liberação de quem tem DEVOLUCAO, mas não é supervisor, não vale para o prazo.
        operador("gerente", Permissao.DEVOLUCAO);
        assertThatThrownBy(() -> t.acesso.liberarSupervisor("gerente", "inicial1", Permissao.DEVOLUCAO))
                .hasMessage("Esta liberação precisa de um supervisor.");
        Liberacao doGerente = t.acesso.liberar("gerente", "inicial1", Permissao.DEVOLUCAO);
        assertThatThrownBy(() -> devolver(caixa1, venda, "1", false, FormaReembolso.OUTRO, null, doGerente))
                .isInstanceOf(PrazoDevolucaoException.class);

        Liberacao supervisor = t.acesso.liberarSupervisor("admin", "segredo1", Permissao.DEVOLUCAO);
        DevolucaoService.DevolucaoRegistrada d = devolver(caixa1, venda, "1", false, FormaReembolso.OUTRO, null,
                supervisor);
        assertThat(t.contar("SELECT liberado_por FROM devolucao WHERE id = " + d.id())).isEqualTo(admin.usuarioId());
        assertThat(d.comprovante().texto()).contains("Liberada por: Ana Admin");
        assertThat(t.auditoria(AcaoAuditoria.DEVOLUCAO_REGISTRADA).get(0))
                .contains("(liberado por Ana Admin (admin), fora do prazo)");
        // O supervisor devolve fora do prazo sem liberação.
        assertThat(devolver(venda, "1", FormaReembolso.OUTRO).valor()).isEqualByComparingTo("5.49");
    }

    @Test
    void semAPermissaoDevolucaoSoComLiberacao() throws Exception {
        long venda = vender(refri, 3, null, null);
        Sessao caixa1 = operador("caixa1", Permissao.PDV);
        assertThat(devolucoes.venda(caixa1, venda).itens()).hasSize(1);
        assertThatThrownBy(() -> devolver(caixa1, venda, "1", false, FormaReembolso.OUTRO, null, null))
                .isInstanceOf(PermissaoNegadaException.class);
        Liberacao liberacao = t.acesso.liberar("admin", "segredo1", Permissao.DEVOLUCAO);
        devolver(caixa1, venda, "1", false, FormaReembolso.OUTRO, null, liberacao);
        assertThat(t.contar("SELECT liberado_por FROM devolucao")).isEqualTo(admin.usuarioId());
        assertThat(t.contar("SELECT usuario_id FROM devolucao")).isEqualTo(caixa1.usuarioId());
    }

    @Test
    void avariadoMostraDevolucaoEPerdaSemMudarOSaldo() throws Exception {
        long venda = vender(refri, 3, null, null);
        BigDecimal antes = saldoEstoque(refri);
        DevolucaoService.DevolucaoRegistrada d = devolver(admin, venda, "1", true, FormaReembolso.DINHEIRO, null,
                null);

        assertThat(saldoEstoque(refri)).isEqualByComparingTo(antes);
        List<MovimentoKardex> movimentos = kardex(refri).movimentos();
        List<MovimentoKardex> ultimos = movimentos.subList(movimentos.size() - 2, movimentos.size());
        assertThat(ultimos).extracting(MovimentoKardex::tipo)
                .containsExactly(TipoMovimento.DEVOLUCAO_VENDA, TipoMovimento.PERDA);
        assertThat(ultimos.get(1).origem()).isEqualTo("Devolução nº " + d.id() + " — avariado: Cliente desistiu");
        assertThat(d.comprovante().texto()).contains("1 UN (avariado)");

        LocalDate hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
        ResultadoRelatorio<RelPerdasAjustes.Linha> r14 = t.relatorios.gerar(admin, new RelPerdasAjustes(),
                Filtros.vazios().comPeriodo(new Periodo(hoje, hoje)));
        assertThat(r14.linhas()).anySatisfy(l -> {
            assertThat(l.tipo()).isEqualTo(TipoMovimento.PERDA);
            assertThat(l.motivo()).isEqualTo("Devolução nº " + d.id() + " — avariado");
            assertThat(l.valor()).as("saída a custo da venda").isEqualByComparingTo("-3.20");
        });
    }

    @Test
    void valeTrocaAumentaOSaldoEPagaOutraVenda() {
        long venda = vender(arroz, 2, null, null);
        assertThatThrownBy(() -> devolver(venda, "1", FormaReembolso.CREDITO_CLIENTE))
                .hasMessage("O vale-troca fica no crédito do cliente: identifique ou cadastre o cliente.");
        long joao = t.clientes.cadastrarRapido(admin, "João Pereira", null).id();

        DevolucaoService.DevolucaoRegistrada d = devolver(admin, venda, "1", false, FormaReembolso.CREDITO_CLIENTE,
                joao, null);
        assertThat(saldoCredito(joao)).isEqualByComparingTo("10.00");
        assertThat(t.credito.extrato(admin, joao).movimentos().get(0).tipo())
                .isEqualTo(TipoCreditoCliente.CREDITO_DEVOLUCAO);
        assertThat(d.comprovante().texto()).contains("Cliente: João Pereira").containsPattern("Reembolso +Vale-troca")
                .containsPattern("Saldo do vale-troca +R\\$ 10,00");

        // O vale-troca paga outra venda.
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(admin, carrinho, vendas.produtoParaVenda(admin, refri), BigDecimal.ONE);
        vendas.identificarCliente(admin, carrinho, joao);
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(credito, new BigDecimal("5.49"));
        vendas.finalizar(admin, carrinho, r);
        assertThat(saldoCredito(joao)).isEqualByComparingTo("4.51");
    }

    @Test
    void devolucaoEstornaOCashbackNaProporcaoEORestanteNaUltima() {
        fidelidade(true);
        long venda = vender(arroz, 10, maria, null); // R$ 100,00, cashback R$ 1,00
        assertThat(saldoCredito(maria)).isEqualByComparingTo("1.00");

        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "4", FormaReembolso.DINHEIRO); // R$ 40,00
        assertThat(d.cashbackEstornado()).isEqualByComparingTo("0.40");
        assertThat(saldoCredito(maria)).isEqualByComparingTo("0.60");
        assertThat(d.comprovante().texto()).containsPattern("Cashback estornado +-R\\$ 0,40")
                .containsPattern("Saldo de crédito +R\\$ 0,60");

        assertThat(devolver(venda, "6", FormaReembolso.DINHEIRO).cashbackEstornado()).isEqualByComparingTo("0.60");
        assertThat(saldoCredito(maria)).isEqualByComparingTo("0.00");
    }

    @Test
    void vendaComDevolucaoNaoPodeSerCancelada() {
        long venda = vender(refri, 3, null, null);
        devolver(venda, "1", FormaReembolso.DINHEIRO);
        assertThatThrownBy(() -> vendas.cancelar(admin, venda, "Erro", null))
                .isInstanceOf(NegocioException.class)
                .hasMessageStartingWith("A venda nº " + venda + " tem devolução e não pode mais ser cancelada.");
    }

    @Test
    void vendaCanceladaNaoTemDevolucao() {
        long venda = vender(refri, 3, null, null);
        vendas.cancelar(admin, venda, "Erro", null);
        assertThatThrownBy(() -> devolver(venda, "1", FormaReembolso.OUTRO))
                .hasMessageContaining("está cancelada: só vendas finalizadas têm devolução");
    }

    @Test
    void clienteAnonimizadoNaoRecebeValeTrocaEOSupervisorZeraOSaldo() {
        fidelidade(true);
        long venda = vender(arroz, 10, maria, null); // cashback R$ 1,00
        t.credito.ajustar(admin, maria, new BigDecimal("-1.00"), "Zerar antes de anonimizar");
        t.clientes.anonimizar(admin, maria, "Maria da Silva");

        VendaDevolucao v = devolucoes.venda(admin, venda);
        assertThat(v.clienteAnonimizado()).isTrue();
        assertThat(v.aceitaValeTroca()).isFalse();
        assertThatThrownBy(() -> devolver(venda, "4", FormaReembolso.CREDITO_CLIENTE))
                .hasMessageContaining("foi anonimizado (LGPD): não há a quem dar o vale-troca");

        devolver(venda, "4", FormaReembolso.DINHEIRO); // estorna R$ 0,40 do cashback
        assertThat(saldoCredito(maria)).isEqualByComparingTo("-0.40");

        assertThatThrownBy(() -> t.credito.ajustar(admin, maria, new BigDecimal("0.10"), "Parcial"))
                .hasMessage("O cliente nº " + maria + " foi anonimizado (LGPD): o ajuste só pode zerar o saldo de "
                        + "-R$ 0,40 (valor +R$ 0,40).");
        assertThat(t.credito.ajustar(admin, maria, new BigDecimal("0.40"), "Saldo do estorno")).isZero();
        assertThat(saldoCredito(maria)).isEqualByComparingTo("0.00");
    }

    @Test
    void reembolsoEmDinheiroExigeCaixaAbertoEDinheiroNaGaveta() throws Exception {
        long venda = vender(arroz, 10, null, null); // gaveta: 100 + 100
        assertThat(devolver(venda, "10", FormaReembolso.DINHEIRO).valor()).as("cabe nos R$ 200,00 da gaveta")
                .isEqualByComparingTo("100.00");
        long outra = vender(arroz, 10, null, null);
        t.caixa.registrarMovimento(admin, br.com.varesync.model.TipoCaixaMovimento.SANGRIA, new BigDecimal("150.00"),
                "Depósito", null);
        assertThatThrownBy(() -> devolver(outra, "10", FormaReembolso.DINHEIRO))
                .hasMessage("O valor da devolução (R$ 100,00) é maior que o dinheiro que deveria estar na gaveta. "
                        + "Escolha outra forma de reembolso.");
        t.caixa.fechar(admin, esperado());
        assertThatThrownBy(() -> devolver(outra, "1", FormaReembolso.DINHEIRO))
                .hasMessageStartingWith("O reembolso em dinheiro sai da gaveta: abra o caixa deste terminal");
        assertThat(devolver(outra, "1", FormaReembolso.OUTRO).valor()).as("sem caixa, por outra forma")
                .isEqualByComparingTo("10.00");
    }

    @Test
    void buscaDaTelaEReimpressaoDoComprovante() {
        long venda = vender(refri, 3, maria, null);
        long semCliente = vender(arroz, 1, null, null);
        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "1", FormaReembolso.OUTRO);

        LocalDate hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
        Periodo periodo = new Periodo(hoje, hoje);
        assertThat(devolucoes.pesquisarVendas(admin, null, periodo, null)).extracting(v -> v.id())
                .containsExactly(semCliente, venda);
        assertThat(devolucoes.pesquisarVendas(admin, null, periodo, "maria")).singleElement()
                .satisfies(v -> assertThat(v.devolvido()).isEqualByComparingTo("5.49"));
        assertThat(devolucoes.pesquisarVendas(admin, null, periodo, "529.982")).extracting(v -> v.id())
                .containsExactly(venda);
        assertThat(devolucoes.pesquisarVendas(admin, semCliente, null, null)).hasSize(1);
        assertThat(devolucoes.venda(admin, venda).devolucoes()).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(d.id()));
        assertThat(devolucoes.comprovante(admin, d.id()).texto()).isEqualTo(d.comprovante().texto())
                .contains("Cliente: Maria da Silva", "CPF: ***.982.247-**");
    }

    @Test
    void r20ListaAsDevolucoesComItensEFormaDeReembolso() {
        long venda = vender(refri, 3, maria, null);
        Liberacao liberacao = t.acesso.liberar("admin", "segredo1", Permissao.DEVOLUCAO);
        Sessao caixa1 = operador("caixa1", Permissao.PDV);
        devolver(caixa1, venda, "2", true, FormaReembolso.DINHEIRO, null, liberacao);
        LocalDate hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());

        ResultadoRelatorio<RelDevolucoes.Linha> r20 = t.relatorios.gerar(admin, new RelDevolucoes(),
                Filtros.vazios().comPeriodo(new Periodo(hoje, hoje)));
        assertThat(r20.linhas()).singleElement().satisfies(l -> {
            assertThat(l.vendaId()).isEqualTo(venda);
            assertThat(l.usuario()).isEqualTo("Operador caixa1");
            assertThat(l.cliente()).isEqualTo("Maria da Silva (***.982.247-**)");
            assertThat(l.itens()).isEqualTo("2 UN x Refrigerante cola 2 L (avariado)");
            assertThat(l.forma()).isEqualTo(FormaReembolso.DINHEIRO);
            assertThat(l.valor()).isEqualByComparingTo("10.98");
        });
    }

    @Test
    void enumsDaDevolucaoIguaisAosChecksDoBanco() throws Exception {
        assertThat(Arrays.stream(FormaReembolso.values()).map(Enum::name).collect(Collectors.toSet()))
                .isEqualTo(valoresDoCheck("ck_dev_forma"));
    }

    @Test
    void bancoRecusaDevolverMaisQueOVendidoMesmoForaDoSistema() throws Exception {
        long venda = vender(refri, 1, null, null);
        long item = item(venda).vendaItemId();
        try (Connection con = dataSource.getConnection(); var st = con.createStatement()) {
            st.executeUpdate("INSERT INTO devolucao (venda_id, usuario_id, motivo, forma_reembolso, valor_total) "
                    + "VALUES (" + venda + ", " + admin.usuarioId() + ", 'Teste', 'OUTRO', 0)");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO devolucao_item (devolucao_id, venda_item_id, "
                    + "quantidade, valor, custo_total, volta_estoque) SELECT max(id), " + item
                    + ", 2, 0, 0, TRUE FROM devolucao"))
                    .hasMessageContaining("passaria da quantidade vendida");
        }
    }

    private static Set<String> valoresDoCheck(String constraint) throws Exception {
        String definicao;
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, constraint);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(constraint).isTrue();
                definicao = rs.getString(1);
            }
        }
        Set<String> valores = new HashSet<>();
        Matcher m = Pattern.compile("'(\\w+)'").matcher(definicao);
        while (m.find()) {
            valores.add(m.group(1));
        }
        return valores;
    }
}
