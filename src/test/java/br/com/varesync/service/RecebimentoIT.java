package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.ComprovanteRecebimento;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.ExtratoCrediario;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.PlanoRecebimento;
import br.com.varesync.model.StatusTitulo;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TituloEmAberto;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Recebimento do crediário (F14.3), com os critérios de aceite da F14 que dependem da parte B. */
class RecebimentoIT extends IntegracaoBase {

    private AcessoTeste t;
    private RecebimentoService recebimentos;
    private Sessao admin;
    private long produto;
    private long maria;
    private FormaPagamento dinheiro;
    private FormaPagamento pix;
    private FormaPagamento crediario;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        recebimentos = t.recebimentos;
        admin = t.supervisor();
        produto = t.produtos.criar(admin, new DadosProduto("Panela", null, "UN", new BigDecimal("100.00"),
                new BigDecimal("60.00"), null, null, List.of(new CodigoBarras("7891000315507", true)),
                new BigDecimal("100")));
        maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        t.clientes.alterarCrediario(admin, maria, new BigDecimal("2000.00"), false);
        dinheiro = forma(TipoFormaPagamento.DINHEIRO);
        pix = forma(TipoFormaPagamento.PIX);
        crediario = forma(TipoFormaPagamento.CREDIARIO);
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    /** Venda a prazo: 1ª parcela em +30 dias. */
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

    private void avancarDias(long dias) {
        t.relogio.avancar(Duration.ofDays(dias));
    }

    /** O título vence há {@code dias} dias: avança o relógio além do vencimento de +30. */
    private void vencidoHa(long dias) {
        avancarDias(30 + dias);
    }

    private RecebimentoService.RecebimentoRegistrado receber(String valor, FormaPagamento forma, String entregue) {
        BigDecimal quantia = valor == null ? null : new BigDecimal(valor);
        PlanoRecebimento plano = recebimentos.simular(admin, maria, List.of(), quantia, null);
        Recebimento pagamento = recebimentos.pagamento(plano);
        pagamento.adicionar(forma, entregue == null ? plano.total() : new BigDecimal(entregue));
        t.relogio.avancar(Duration.ofMinutes(1));
        return recebimentos.registrar(admin, maria, List.of(), quantia, null, pagamento, null, null);
    }

    private BigDecimal consulta(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private BigDecimal esperado() throws Exception {
        return consulta("SELECT dinheiro_esperado FROM vw_caixa_resumo WHERE status = 'ABERTO'");
    }

    private String status(long venda, int parcela) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM titulo_receber WHERE venda_id = " + venda
                     + " AND parcela = " + parcela)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private BigDecimal pago(long venda, int parcela) throws Exception {
        return consulta("SELECT valor_pago FROM titulo_receber WHERE venda_id = " + venda + " AND parcela = " + parcela);
    }

    @Test
    void encargosDoTituloVencidoHaDezDias() {
        vender(1, 1);
        vencidoHa(10);

        TituloEmAberto titulo = recebimentos.titulos(admin, maria).get(0);

        assertThat(titulo.diasAtraso()).isEqualTo(10);
        assertThat(titulo.encargos().multa()).isEqualByComparingTo("2.00");
        assertThat(titulo.encargos().juros()).isEqualByComparingTo("0.67");
        assertThat(titulo.encargos().paraQuitar()).isEqualByComparingTo("102.67");
    }

    @Test
    void quitarTituloVencidoGravaOsEncargos() throws Exception {
        long venda = vender(1, 1);
        vencidoHa(10);

        RecebimentoService.RecebimentoRegistrado r = receber(null, pix, null);

        assertThat(r.total()).isEqualByComparingTo("102.67");
        assertThat(status(venda, 1)).isEqualTo("QUITADO");
        assertThat(pago(venda, 1)).as("valor_pago guarda só o principal").isEqualByComparingTo("100.00");
        assertThat(consulta("SELECT multa FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("2.00");
        assertThat(consulta("SELECT juros FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("0.67");
        assertThat(consulta("SELECT valor_total FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("102.67");
        assertThat(t.auditoria(AcaoAuditoria.RECEBIMENTO_REGISTRADO)).hasSize(1);
    }

    @Test
    void parcialComAtraso() throws Exception {
        long venda = vender(1, 1);
        vencidoHa(10);

        RecebimentoService.RecebimentoRegistrado r = receber("51.34", pix, null);

        assertThat(consulta("SELECT valor_principal FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("50.00");
        assertThat(consulta("SELECT multa FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("1.00");
        assertThat(consulta("SELECT juros FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("0.34");
        assertThat(status(venda, 1)).isEqualTo("ABERTO");
        assertThat(recebimentos.titulos(admin, maria).get(0).encargos().emAberto()).isEqualByComparingTo("50.00");
        assertThat(recebimentos.titulos(admin, maria).get(0).encargos().paraQuitar())
                .as("o que fica em aberto continua sujeito aos encargos").isEqualByComparingTo("51.33");
    }

    @Test
    void parcialSemAtraso() throws Exception {
        long venda = vender(1, 1);

        RecebimentoService.RecebimentoRegistrado r = receber("50.00", pix, null);

        assertThat(consulta("SELECT multa + juros FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("0");
        assertThat(pago(venda, 1)).isEqualByComparingTo("50.00");
        assertThat(status(venda, 1)).isEqualTo("ABERTO");
        assertThat(recebimentos.titulos(admin, maria).get(0).encargos().emAberto()).isEqualByComparingTo("50.00");
    }

    @Test
    void valorVaiDoVencimentoMaisAntigoParaOMaisNovo() throws Exception {
        long venda = vender(3, 3);

        receber("150.00", pix, null);

        assertThat(status(venda, 1)).isEqualTo("QUITADO");
        assertThat(pago(venda, 2)).isEqualByComparingTo("50.00");
        assertThat(pago(venda, 3)).isEqualByComparingTo("0.00");
    }

    @Test
    void selecionarTituloQuitaSoEle() throws Exception {
        long venda = vender(3, 3);
        TituloEmAberto segunda = recebimentos.titulos(admin, maria).get(1);
        PlanoRecebimento plano = recebimentos.simular(admin, maria, List.of(segunda.tituloId()), null, null);
        Recebimento pagamento = recebimentos.pagamento(plano);
        pagamento.adicionar(pix, plano.total());

        recebimentos.registrar(admin, maria, List.of(segunda.tituloId()), null, null, pagamento, null, null);

        assertThat(status(venda, 1)).isEqualTo("ABERTO");
        assertThat(status(venda, 2)).isEqualTo("QUITADO");
    }

    @Test
    void valorAcimaDoQueFaltaERecusado() {
        vender(1, 1);

        assertThatThrownBy(() -> recebimentos.simular(admin, maria, List.of(), new BigDecimal("100.01"), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("passa do que falta");
    }

    @Test
    void carenciaDispensaEncargos() throws Exception {
        t.configuracoes.salvar(admin, t.configuracoes.carregar(admin).comCrediario(
                new Configuracoes.Crediario(3, 30, new BigDecimal("2"), new BigDecimal("2"), 10, 30)));
        vender(1, 1);
        vencidoHa(10);

        assertThat(recebimentos.titulos(admin, maria).get(0).encargos().paraQuitar()).isEqualByComparingTo("100.00");
    }

    @Test
    void recebimentoEmDinheiroEntraNoEsperadoEEstornadoDeixaDeContar() throws Exception {
        long venda = vender(1, 1);
        BigDecimal antes = esperado();

        RecebimentoService.RecebimentoRegistrado r = receber(null, dinheiro, "120.00");

        assertThat(r.troco()).isEqualByComparingTo("20.00");
        assertThat(esperado()).as("já sem o troco").isEqualByComparingTo(antes.add(new BigDecimal("100.00")));

        recebimentos.estornar(admin, r.id(), "Lançado errado", null);

        assertThat(esperado()).isEqualByComparingTo(antes);
        assertThat(status(venda, 1)).isEqualTo("ABERTO");
        assertThat(pago(venda, 1)).isEqualByComparingTo("0.00");
        assertThat(t.auditoria(AcaoAuditoria.RECEBIMENTO_ESTORNADO)).hasSize(1);
        assertThatThrownBy(() -> recebimentos.estornar(admin, r.id(), "De novo", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("já foi estornado");
    }

    @Test
    void estornoSoNaMesmaSessaoDeCaixa() {
        vender(1, 1);
        RecebimentoService.RecebimentoRegistrado r = receber(null, pix, null);
        t.caixa.fechar(admin, new BigDecimal("100.00"));
        t.caixa.abrir(admin, new BigDecimal("100.00"));

        assertThatThrownBy(() -> recebimentos.estornar(admin, r.id(), "Motivo", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("outra sessão");
    }

    @Test
    void estornoEmDinheiroNaoPassaDaGaveta() {
        vender(1, 1);
        RecebimentoService.RecebimentoRegistrado r = receber(null, dinheiro, null);
        t.caixa.registrarMovimento(admin, br.com.varesync.model.TipoCaixaMovimento.SANGRIA, new BigDecimal("150.00"),
                "Banco", null);

        assertThatThrownBy(() -> recebimentos.estornar(admin, r.id(), "Motivo", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("gaveta");
    }

    @Test
    void cancelarVendaComParcelaPagaDeVerdadeERecusadoEDepoisDoEstornoPassa() throws Exception {
        long venda = vender(3, 3);
        RecebimentoService.RecebimentoRegistrado r = receber("10.00", pix, null);

        assertThatThrownBy(() -> t.vendas.cancelar(admin, venda, "Desistiu", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("parcela");

        recebimentos.estornar(admin, r.id(), "Lançado errado", null);
        t.vendas.cancelar(admin, venda, "Desistiu", null);

        assertThat(status(venda, 1)).isEqualTo(StatusTitulo.CANCELADO.name());
    }

    @Test
    void semPermissaoPedeLiberacaoERegistraQuemLiberou() throws Exception {
        t.usuarios.criar(admin, "beto", "Beto Caixa", false, EnumSet.of(Permissao.PDV), "segredo1", "segredo1");
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE usuario SET deve_trocar_senha = FALSE WHERE login = 'beto'");
        }
        Sessao beto = t.acesso.entrar("beto", "segredo1");
        vender(1, 1);
        PlanoRecebimento plano = recebimentos.simular(beto, maria, List.of(), null, null);
        Recebimento pagamento = recebimentos.pagamento(plano);
        pagamento.adicionar(pix, plano.total());

        assertThatThrownBy(() -> recebimentos.registrar(beto, maria, List.of(), null, null, pagamento, null, null))
                .isInstanceOf(PermissaoNegadaException.class);

        Liberacao liberacao = t.acesso.liberar("admin", "segredo1", Permissao.RECEBIMENTO);
        RecebimentoService.RecebimentoRegistrado r = recebimentos.registrar(beto, maria, List.of(), null, null,
                pagamento, liberacao, null);

        assertThat(r.comprovante().liberadoPor()).isEqualTo("Ana Admin");
        assertThat(t.auditoria(AcaoAuditoria.RECEBIMENTO_REGISTRADO).get(0)).contains("liberado por");
    }

    @Test
    void descontoNosEncargosExigeLimiteCreditoOuLiberacao() throws Exception {
        t.usuarios.criar(admin, "beto", "Beto Caixa", false, EnumSet.of(Permissao.PDV, Permissao.RECEBIMENTO),
                "segredo1", "segredo1");
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE usuario SET deve_trocar_senha = FALSE WHERE login = 'beto'");
        }
        Sessao beto = t.acesso.entrar("beto", "segredo1");
        vender(1, 1);
        vencidoHa(10);
        BigDecimal desconto = new BigDecimal("2.67");
        PlanoRecebimento plano = recebimentos.simular(beto, maria, List.of(), null, desconto);
        assertThat(plano.total()).isEqualByComparingTo("100.00");
        Recebimento pagamento = recebimentos.pagamento(plano);
        pagamento.adicionar(pix, plano.total());

        assertThatThrownBy(() -> recebimentos.registrar(beto, maria, List.of(), null, desconto, pagamento, null, null))
                .isInstanceOf(PermissaoNegadaException.class);

        Liberacao liberacao = t.acesso.liberar("admin", "segredo1", Permissao.LIMITE_CREDITO);
        RecebimentoService.RecebimentoRegistrado r = recebimentos.registrar(beto, maria, List.of(), null, desconto,
                pagamento, null, liberacao);

        assertThat(consulta("SELECT desconto FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("2.67");
        assertThat(consulta("SELECT valor_total FROM recebimento WHERE id = " + r.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void descontoAcimaDosEncargosOuComValorDigitadoERecusado() {
        vender(1, 1);
        vencidoHa(10);

        assertThatThrownBy(() -> recebimentos.simular(admin, maria, List.of(), null, new BigDecimal("2.68")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("passa dos encargos");
        assertThatThrownBy(() -> recebimentos.simular(admin, maria, List.of(), new BigDecimal("50.00"),
                new BigDecimal("1.00"))).isInstanceOf(NegocioException.class).hasMessageContaining("só para quitar");
    }

    @Test
    void semCaixaAbertoNaoRecebe() {
        vender(1, 1);
        PlanoRecebimento plano = recebimentos.simular(admin, maria, List.of(), null, null);
        Recebimento pagamento = recebimentos.pagamento(plano);
        pagamento.adicionar(pix, plano.total());
        t.caixa.fechar(admin, new BigDecimal("100.00"));

        assertThatThrownBy(() -> recebimentos.registrar(admin, maria, List.of(), null, null, pagamento, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("caixa");
    }

    @Test
    void naoAceitaCrediarioNemCreditoComoPagamento() {
        assertThat(recebimentos.formas(admin)).noneMatch(f -> f.crediario() || f.creditoCliente());
        vender(1, 1);
        PlanoRecebimento plano = recebimentos.simular(admin, maria, List.of(), null, null);
        Recebimento pagamento = new Recebimento(plano.total(), BigDecimal.ZERO);
        pagamento.adicionar(crediario, plano.total());

        assertThatThrownBy(() -> recebimentos.registrar(admin, maria, List.of(), null, null, pagamento, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("não pode ser pago");
    }

    @Test
    void clienteInativoContinuaRecebendoEAparecendoNaBusca() {
        vender(1, 1);
        t.clientes.alterarSituacao(admin, maria, false);

        assertThat(recebimentos.pesquisarClientes(admin, "maria")).hasSize(1);
        assertThat(recebimentos.pesquisarClientes(admin, "52998224725")).hasSize(1);

        receber(null, pix, null);

        assertThat(recebimentos.titulos(admin, maria)).isEmpty();
    }

    @Test
    void comprovanteMostraEncargosFormasETroco() {
        vender(1, 1);
        vencidoHa(10);

        ComprovanteRecebimento c = receber("51.34", dinheiro, "60.00").comprovante();

        assertThat(c.titulos()).singleElement().satisfies(x -> {
            assertThat(x.principal()).isEqualByComparingTo("50.00");
            assertThat(x.emAbertoDepois()).isEqualByComparingTo("50.00");
        });
        String texto = c.texto();
        assertThat(texto).contains("RECEBIMENTO DE CREDIÁRIO", "NÃO É DOCUMENTO FISCAL", "Maria da Silva", "R$ 51,34",
                "R$ 60,00", "R$ 8,66");
        assertThat(texto).doesNotContain("52998224725");
    }

    @Test
    void comprovanteReimpressoDepoisDoEstornoAvisa() {
        vender(1, 1);
        RecebimentoService.RecebimentoRegistrado r = receber(null, pix, null);
        t.relogio.avancar(Duration.ofMinutes(1));
        recebimentos.estornar(admin, r.id(), "Errado", null);

        ComprovanteRecebimento c = recebimentos.comprovante(admin, r.id());

        assertThat(c.texto()).contains("RECEBIMENTO ESTORNADO");
        assertThat(c.titulos().get(0).emAbertoDepois()).as("como ficou no dia").isEqualByComparingTo("0.00");
    }

    @Test
    void anonimizarDepoisDeReceberTudoPassaEBloqueiaOEstorno() {
        vender(1, 1);
        RecebimentoService.RecebimentoRegistrado r = receber(null, pix, null);

        t.clientes.anonimizar(admin, maria, "Maria da Silva");

        assertThatThrownBy(() -> recebimentos.estornar(admin, r.id(), "Errado", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("anonimizado");
    }

    @Test
    void devolucaoAbateOQueSobrouDepoisDoPagamentoParcial() throws Exception {
        long venda = vender(1, 1);
        receber("60.00", pix, null);

        long item;
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM venda_item WHERE venda_id = " + venda)) {
            rs.next();
            item = rs.getLong(1);
        }
        t.relogio.avancar(Duration.ofMinutes(1));
        var devolucao = t.devolucoes.registrar(admin, new DadosDevolucao(venda,
                List.of(new DadosDevolucao.Item(item, BigDecimal.ONE, false)), "Defeito", FormaReembolso.OUTRO, null,
                null, t.contaDoReembolso(FormaReembolso.OUTRO)), null);

        assertThat(devolucao.comprovante().valorAbatido()).isEqualByComparingTo("40.00");
        assertThat(status(venda, 1)).isEqualTo("QUITADO");
        ExtratoCrediario extrato = t.clientes.extratoCrediario(admin, maria);
        assertThat(extrato.saldoDevedor()).isEqualByComparingTo("0.00");
    }
}
