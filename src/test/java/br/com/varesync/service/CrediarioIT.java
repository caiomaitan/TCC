package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.StatusTitulo;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelDevolucoes;
import br.com.varesync.report.RelFormasPagamento;
import br.com.varesync.report.RelVendasPeriodo;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Crediário (F14.1 e F14.2), com os critérios de aceite da F14 que dependem da parte A: parcelamento, centavos, limite,
 * cancelamento, devolução com abatimento, caixa, cashback, LGPD e o R08 batendo com o R01.
 */
class CrediarioIT extends IntegracaoBase {

    private AcessoTeste t;
    private VendaService vendas;
    private Sessao admin;
    private long cem;
    private long maria;
    private FormaPagamento dinheiro;
    private FormaPagamento crediario;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        vendas = t.vendas;
        admin = t.supervisor();
        cem = t.produtos.criar(admin, new DadosProduto("Panela de R$ 100", null, "UN", new BigDecimal("100.00"),
                new BigDecimal("60.00"), null, null, List.of(new CodigoBarras("7891000315507", true)),
                new BigDecimal("100")));
        maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        limite(maria, "1000.00");
        dinheiro = forma(TipoFormaPagamento.DINHEIRO);
        crediario = forma(TipoFormaPagamento.CREDIARIO);
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private void sql(String comando) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate(comando);
        }
    }

    /** O limite ainda não tem tela (parte B). */
    private void limite(long cliente, String valor) throws Exception {
        sql("UPDATE cliente SET limite_credito = " + valor + " WHERE id = " + cliente);
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private Carrinho carrinho(int unidades, Long cliente) {
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(admin, carrinho, vendas.produtoParaVenda(admin, cem), BigDecimal.valueOf(unidades));
        if (cliente != null) {
            vendas.identificarCliente(admin, carrinho, cliente);
        }
        return carrinho;
    }

    /** Recebimento com {@code aVista} em dinheiro e o resto no crediário, em {@code parcelas}, 1ª em +30 dias. */
    private Recebimento recebimento(Sessao sessao, Carrinho carrinho, String aVista, int parcelas) {
        Recebimento r = vendas.recebimento(sessao, carrinho);
        if (aVista != null) {
            r.adicionar(dinheiro, new BigDecimal(aVista));
        }
        r.adicionar(crediario, r.falta());
        r.definirPlanoCrediario(new PlanoCrediario(parcelas, hoje().plusDays(30)));
        return r;
    }

    private long vender(int unidades, String aVista, int parcelas) {
        Carrinho carrinho = carrinho(unidades, maria);
        t.relogio.avancar(Duration.ofMinutes(1));
        return vendas.finalizar(admin, carrinho, recebimento(admin, carrinho, aVista, parcelas)).vendaId();
    }

    private record Titulo(int parcela, int total, LocalDate vencimento, BigDecimal valor, BigDecimal abatido,
                          StatusTitulo status) {
    }

    private List<Titulo> titulos(long venda) throws Exception {
        List<Titulo> titulos = new ArrayList<>();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT parcela, total_parcelas, vencimento, valor, valor_abatido, status "
                     + "FROM titulo_receber WHERE venda_id = " + venda + " ORDER BY parcela")) {
            while (rs.next()) {
                titulos.add(new Titulo(rs.getInt(1), rs.getInt(2), rs.getObject(3, LocalDate.class),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), StatusTitulo.valueOf(rs.getString(6))));
            }
        }
        return titulos;
    }

    private DevolucaoService.DevolucaoRegistrada devolver(long venda, String quantidade, FormaReembolso forma) {
        t.relogio.avancar(Duration.ofMinutes(1));
        long item = t.devolucoes.venda(admin, venda).itens().get(0).vendaItemId();
        return t.devolucoes.registrar(admin, new DadosDevolucao(venda, List.of(new DadosDevolucao.Item(item,
                new BigDecimal(quantidade), false)), "Cliente desistiu", forma, null, null), null);
    }

    private BigDecimal esperado() throws Exception {
        return BigDecimal.valueOf(t.contar("SELECT dinheiro_esperado * 100 FROM vw_caixa_resumo "
                + "WHERE status = 'ABERTO'")).movePointLeft(2);
    }

    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    private Filtros hojeSo() {
        return Filtros.vazios().comPeriodo(new Periodo(hoje(), hoje()));
    }

    // ----- Parcelas -----

    @Test
    void parcelamentoGeraTitulosComVencimentosDe30Em30Dias() throws Exception {
        long venda = vender(3, null, 3);

        List<Titulo> titulos = titulos(venda);
        assertThat(titulos).hasSize(3);
        assertThat(titulos).allSatisfy(x -> {
            assertThat(x.valor()).isEqualByComparingTo("100.00");
            assertThat(x.status()).isEqualTo(StatusTitulo.ABERTO);
            assertThat(x.total()).isEqualTo(3);
        });
        assertThat(titulos).extracting(Titulo::vencimento).containsExactly(hoje().plusDays(30), hoje().plusDays(60),
                hoje().plusDays(90));
        Comprovante comprovante = vendas.comprovante(admin, venda);
        assertThat(comprovante.texto()).contains("Parcelas do crediário:", "Crediário")
                .containsPattern("1/3 vence .* +R\\$ 100,00");
    }

    @Test
    void centavosFicamNaPrimeiraParcela() throws Exception {
        long venda = vender(1, null, 3);
        assertThat(titulos(venda)).extracting(x -> x.valor().toPlainString()).containsExactly("33.34", "33.33",
                "33.33");
    }

    @Test
    void parcelasFicamEntreUmEOMaximoEVencimentoNaoPodeSerNoPassado() {
        Carrinho carrinho = carrinho(1, maria);
        Recebimento quatro = recebimento(admin, carrinho, null, 4);
        assertThatThrownBy(() -> vendas.finalizar(admin, carrinho, quatro)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("de 1 a 3 parcelas");

        Recebimento passado = recebimento(admin, carrinho, null, 1);
        passado.definirPlanoCrediario(new PlanoCrediario(1, hoje().minusDays(1)));
        assertThatThrownBy(() -> vendas.finalizar(admin, carrinho, passado)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("anterior a hoje");

        Recebimento semPlano = recebimento(admin, carrinho, null, 1);
        semPlano.definirPlanoCrediario(null);
        assertThatThrownBy(() -> vendas.finalizar(admin, carrinho, semPlano)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("Informe as parcelas");
    }

    @Test
    void vencimentoEditavelVale() throws Exception {
        Carrinho carrinho = carrinho(1, maria);
        Recebimento r = recebimento(admin, carrinho, null, 2);
        r.definirPlanoCrediario(new PlanoCrediario(2, hoje().plusDays(10)));
        long venda = vendas.finalizar(admin, carrinho, r).vendaId();
        assertThat(titulos(venda)).extracting(Titulo::vencimento).containsExactly(hoje().plusDays(10),
                hoje().plusDays(40));
    }

    // ----- Cliente -----

    @Test
    void crediarioExigeClienteIdentificadoAtivoENaoBloqueado() throws Exception {
        Carrinho semCliente = carrinho(1, null);
        Recebimento r = vendas.recebimento(admin, semCliente);
        assertThatThrownBy(() -> r.adicionar(crediario, new BigDecimal("100.00")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("identifique o cliente");

        Carrinho bloqueado = carrinho(1, maria);
        sql("UPDATE cliente SET crediario_bloqueado = TRUE WHERE id = " + maria);
        Recebimento rb = recebimento(admin, bloqueado, null, 1);
        assertThatThrownBy(() -> vendas.finalizar(admin, bloqueado, rb)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("bloqueado");
        sql("UPDATE cliente SET crediario_bloqueado = FALSE WHERE id = " + maria);

        Carrinho inativo = carrinho(1, maria);
        Recebimento ri = recebimento(admin, inativo, null, 1);
        t.clientes.alterarSituacao(admin, maria, false);
        assertThatThrownBy(() -> vendas.finalizar(admin, inativo, ri)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("inativo");
        assertThat(t.contar("SELECT count(*) FROM titulo_receber")).isZero();
    }

    // ----- Limite -----

    @Test
    void vendaAcimaDoLimiteERecusadaSemLiberacaoEPassaComLiberacaoAuditada() throws Exception {
        limite(maria, "150.00");
        vender(1, null, 1); // restam R$ 50,00 de limite
        Sessao caixa = operador("caixa", Permissao.PDV);
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(caixa, carrinho, vendas.produtoParaVenda(caixa, cem), BigDecimal.ONE);
        vendas.identificarCliente(caixa, carrinho, maria);
        Recebimento r = recebimento(caixa, carrinho, null, 1);

        assertThatThrownBy(() -> vendas.finalizar(caixa, carrinho, r)).isInstanceOf(CrediarioLiberacaoException.class)
                .hasMessageContaining("limite disponível (R$ 50,00)");
        assertThat(t.contar("SELECT count(*) FROM titulo_receber")).isEqualTo(1);

        t.relogio.avancar(Duration.ofMinutes(1));
        r.liberarCrediario(t.acesso.liberar("admin", "segredo1", Permissao.LIMITE_CREDITO));
        vendas.finalizar(caixa, carrinho, r);

        assertThat(t.contar("SELECT count(*) FROM titulo_receber")).isEqualTo(2);
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = '" + AcaoAuditoria.CREDIARIO_LIBERADO
                + "' AND descricao LIKE '%liberado por%'")).isEqualTo(1);
    }

    @Test
    void dentroDoLimiteNaoPedeLiberacaoEQuemTemAPermissaoVendeComAuditoria() throws Exception {
        limite(maria, "100.00");
        Sessao caixa = operador("caixa", Permissao.PDV);
        Carrinho dentro = new Carrinho();
        vendas.adicionarItem(caixa, dentro, vendas.produtoParaVenda(caixa, cem), BigDecimal.ONE);
        vendas.identificarCliente(caixa, dentro, maria);
        vendas.finalizar(caixa, dentro, recebimento(caixa, dentro, null, 1));
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = 'CREDIARIO_LIBERADO'")).isZero();

        Sessao gerente = operador("gerente", Permissao.PDV, Permissao.LIMITE_CREDITO);
        Carrinho acima = new Carrinho();
        vendas.adicionarItem(gerente, acima, vendas.produtoParaVenda(gerente, cem), BigDecimal.ONE);
        vendas.identificarCliente(gerente, acima, maria);
        t.relogio.avancar(Duration.ofMinutes(1));
        vendas.finalizar(gerente, acima, recebimento(gerente, acima, null, 1));
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = 'CREDIARIO_LIBERADO'")).isEqualTo(1);
    }

    @Test
    void tituloVencidoHaMaisDosDiasDeBloqueioPedeLiberacao() throws Exception {
        long venda = vender(1, null, 1);
        Sessao caixa = operador("caixa", Permissao.PDV);
        // Vencido há exatos 30 dias ainda não bloqueia; há 31, sim.
        sql("UPDATE titulo_receber SET vencimento = DATE '" + hoje().minusDays(30) + "' WHERE venda_id = " + venda);
        Carrinho noLimite = new Carrinho();
        vendas.adicionarItem(caixa, noLimite, vendas.produtoParaVenda(caixa, cem), BigDecimal.ONE);
        vendas.identificarCliente(caixa, noLimite, maria);
        t.relogio.avancar(Duration.ofMinutes(1));
        vendas.finalizar(caixa, noLimite, recebimento(caixa, noLimite, null, 1));

        sql("UPDATE titulo_receber SET vencimento = DATE '" + hoje().minusDays(31) + "' WHERE venda_id = " + venda);
        Carrinho atrasado = new Carrinho();
        vendas.adicionarItem(caixa, atrasado, vendas.produtoParaVenda(caixa, cem), BigDecimal.ONE);
        vendas.identificarCliente(caixa, atrasado, maria);
        Recebimento r = recebimento(caixa, atrasado, null, 1);
        assertThatThrownBy(() -> vendas.finalizar(caixa, atrasado, r)).isInstanceOf(CrediarioLiberacaoException.class)
                .hasMessageContaining("vencido há mais de 30 dias");
    }

    // ----- Combinação e cashback -----

    @Test
    void parteNoCrediarioNaoGeraCashback() throws Exception {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comFidelidade(new Configuracoes.Fidelidade(true, BigDecimal.ONE,
                BigDecimal.ZERO)));
        vender(1, "40.00", 2);
        assertThat(t.credito.extrato(admin, maria).saldo()).as("1% de R$ 40,00").isEqualByComparingTo("0.40");
        assertThat(esperado()).as("só os R$ 40,00 em dinheiro entram na gaveta").isEqualByComparingTo("140.00");
    }

    // ----- Cancelamento -----

    @Test
    void cancelarVendaCancelaOsTitulos() throws Exception {
        long venda = vender(3, null, 3);
        vendas.cancelar(admin, venda, "Cliente desistiu", null);
        assertThat(titulos(venda)).extracting(Titulo::status).containsOnly(StatusTitulo.CANCELADO);
        assertThat(vendas.comprovante(admin, venda).crediario()).isEmpty();
        // Cancelados não ocupam mais o limite.
        limite(maria, "100.00");
        vender(1, null, 1);
    }

    @Test
    void cancelarVendaComParcelaPagaERecusado() throws Exception {
        long venda = vender(3, null, 3);
        sql("UPDATE titulo_receber SET valor_pago = 10 WHERE venda_id = " + venda + " AND parcela = 1");
        assertThatThrownBy(() -> vendas.cancelar(admin, venda, "Cliente desistiu", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("parcela do crediário com pagamento");
        assertThat(titulos(venda)).extracting(Titulo::status).containsOnly(StatusTitulo.ABERTO);
    }

    // ----- Devolução -----

    @Test
    void devolucaoSoComAbatimentoDaUltimaParcelaParaAPrimeira() throws Exception {
        // R$ 300,00 em 3×; 6 unidades de R$ 50,00 para devolver R$ 120,00? O produto é de R$ 100: desconto dá R$ 120.
        long produto = t.produtos.criar(admin, new DadosProduto("Copo de R$ 20", null, "UN", new BigDecimal("20.00"),
                new BigDecimal("10.00"), null, null, List.of(new CodigoBarras("7896006716112", true)),
                new BigDecimal("100")));
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(admin, carrinho, vendas.produtoParaVenda(admin, produto), new BigDecimal("15"));
        vendas.identificarCliente(admin, carrinho, maria);
        long venda = vendas.finalizar(admin, carrinho, recebimento(admin, carrinho, null, 3)).vendaId();

        BigDecimal antes = esperado();
        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "6", FormaReembolso.DINHEIRO);

        assertThat(d.valor()).isEqualByComparingTo("120.00");
        List<Titulo> titulos = titulos(venda);
        assertThat(titulos.get(2).abatido()).isEqualByComparingTo("100.00");
        assertThat(titulos.get(2).status()).isEqualTo(StatusTitulo.QUITADO);
        assertThat(titulos.get(1).abatido()).isEqualByComparingTo("20.00");
        assertThat(titulos.get(1).status()).isEqualTo(StatusTitulo.ABERTO);
        assertThat(titulos.get(0).abatido()).isEqualByComparingTo("0.00");
        assertThat(t.contar("SELECT count(*) FROM devolucao WHERE forma_reembolso = 'ABATIMENTO' "
                + "AND valor_abatido = 120 AND caixa_sessao_id IS NULL")).isEqualTo(1);
        assertThat(esperado()).as("nada sai da gaveta").isEqualByComparingTo(antes);
        assertThat(d.comprovante().texto()).containsPattern("Abatido nas parcelas +R\\$ 120,00")
                .containsPattern("Reembolso +Abatimento").containsPattern("1/3 vence .* +R\\$ 100,00")
                .containsPattern("2/3 vence .* +R\\$ 80,00").doesNotContain("3/3");
    }

    @Test
    void devolucaoSoComAbatimentoDispensaCaixaAberto() throws Exception {
        long venda = vender(3, null, 3);
        t.caixa.fechar(admin, new BigDecimal("100.00"));
        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "1", FormaReembolso.DINHEIRO);
        assertThat(d.valor()).isEqualByComparingTo("100.00");
        assertThat(d.comprovante().forma()).isEqualTo(FormaReembolso.ABATIMENTO);
    }

    @Test
    void devolucaoComAbatimentoERestanteEmDinheiro() throws Exception {
        long venda = vender(1, "40.00", 1);
        assertThat(esperado()).isEqualByComparingTo("140.00");

        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "1", FormaReembolso.DINHEIRO);

        assertThat(titulos(venda).get(0).abatido()).isEqualByComparingTo("60.00");
        assertThat(titulos(venda).get(0).status()).isEqualTo(StatusTitulo.QUITADO);
        assertThat(t.contar("SELECT count(*) FROM devolucao WHERE forma_reembolso = 'DINHEIRO' "
                + "AND valor_total = 100 AND valor_abatido = 60")).isEqualTo(1);
        assertThat(esperado()).as("cai só R$ 40,00").isEqualByComparingTo("100.00");
        assertThat(t.contar("SELECT devolucoes_dinheiro * 100 FROM vw_caixa_resumo WHERE status = 'ABERTO'"))
                .isEqualTo(4000);
        assertThat(d.comprovante().texto()).containsPattern("Abatido nas parcelas +R\\$ 60,00")
                .containsPattern("Reembolso: Dinheiro +R\\$ 40,00").contains("Nenhuma parcela em aberto.");
    }

    @Test
    void restanteEmDinheiroPassandoDaGavetaOuSemCaixaERecusado() throws Exception {
        long venda = vender(1, "40.00", 1);
        t.caixa.registrarMovimento(admin, br.com.varesync.model.TipoCaixaMovimento.SANGRIA,
                new BigDecimal("120.00"), "Depósito", null);
        assertThatThrownBy(() -> devolver(venda, "1", FormaReembolso.DINHEIRO)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("R$ 40,00");
        assertThat(titulos(venda).get(0).abatido()).as("nada foi gravado").isEqualByComparingTo("0.00");

        t.caixa.fechar(admin, new BigDecimal("20.00"));
        assertThatThrownBy(() -> devolver(venda, "1", FormaReembolso.DINHEIRO)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("abra o caixa");
        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "1", FormaReembolso.OUTRO);
        assertThat(d.comprovante().forma()).isEqualTo(FormaReembolso.OUTRO);
    }

    @Test
    void restanteEmValeTrocaViraCredito() throws Exception {
        long venda = vender(1, "40.00", 1);
        devolver(venda, "1", FormaReembolso.CREDITO_CLIENTE);
        assertThat(t.credito.extrato(admin, maria).saldo()).isEqualByComparingTo("40.00");
    }

    @Test
    void parcelaJaPagaNaoEAbatidaEORestanteVaiNaForma() throws Exception {
        long venda = vender(1, null, 2); // 50 + 50
        sql("UPDATE titulo_receber SET valor_pago = 50, status = 'QUITADO' WHERE venda_id = " + venda
                + " AND parcela = 2");
        DevolucaoService.DevolucaoRegistrada d = devolver(venda, "1", FormaReembolso.OUTRO);
        List<Titulo> titulos = titulos(venda);
        assertThat(titulos.get(0).abatido()).isEqualByComparingTo("50.00");
        assertThat(titulos.get(1).abatido()).isEqualByComparingTo("0.00");
        assertThat(d.comprovante().valorAbatido()).isEqualByComparingTo("50.00");
        assertThat(d.comprovante().forma()).isEqualTo(FormaReembolso.OUTRO);
    }

    @Test
    void abatimentoEscolhidoComRestanteERecusadoEVendaSemTituloNaoMuda() throws Exception {
        long comRestante = vender(1, "40.00", 1);
        assertThatThrownBy(() -> devolver(comRestante, "1", FormaReembolso.ABATIMENTO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("R$ 40,00");

        Carrinho carrinho = carrinho(1, null);
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        long aVista = vendas.finalizar(admin, carrinho, r).vendaId();
        DevolucaoService.DevolucaoRegistrada d = devolver(aVista, "1", FormaReembolso.DINHEIRO);
        assertThat(d.comprovante().valorAbatido()).isEqualByComparingTo("0");
        assertThat(d.comprovante().texto()).doesNotContain("Abatido").containsPattern("Reembolso +Dinheiro");
    }

    @Test
    void bancoRecusaAbatimentoQueNaoConfere() throws Exception {
        long venda = vender(1, null, 1);
        assertThatThrownBy(() -> sql("UPDATE titulo_receber SET valor_abatido = 101 WHERE venda_id = " + venda))
                .hasMessageContaining("ck_tr_baixas");
        assertThatThrownBy(() -> sql("UPDATE titulo_receber SET status = 'QUITADO' WHERE venda_id = " + venda))
                .hasMessageContaining("ck_tr_situacao");
        assertThatThrownBy(() -> sql("INSERT INTO devolucao (venda_id, usuario_id, motivo, forma_reembolso, "
                + "valor_total, valor_abatido) SELECT " + venda + ", id, 'x', 'ABATIMENTO', 10, 5 FROM usuario "
                + "LIMIT 1")).hasMessageContaining("ck_dev_abatido");
    }

    // ----- LGPD -----

    @Test
    void clienteComTituloEmAbertoNaoPodeSerAnonimizado() throws Exception {
        long venda = vender(1, null, 1);
        assertThatThrownBy(() -> t.clientes.anonimizar(admin, maria, "Maria da Silva"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("títulos do crediário em aberto");

        devolver(venda, "1", FormaReembolso.DINHEIRO); // abatido por inteiro: nada em aberto
        t.clientes.anonimizar(admin, maria, "Maria da Silva");
    }

    // ----- Formas e consistência -----

    @Test
    void formaCrediarioEUnicaENaoContaComoFormaAtiva() {
        assertThatThrownBy(() -> t.configuracoes.criarForma(admin, "Fiado", TipoFormaPagamento.CREDIARIO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Só pode existir uma");
        assertThatThrownBy(() -> sql("INSERT INTO forma_pagamento (descricao, tipo) VALUES ('Fiado', 'CREDIARIO')"))
                .hasMessageContaining("ux_forma_pagamento_crediario");
        t.configuracoes.alterarSituacaoForma(admin, crediario.id(), false);
        t.configuracoes.alterarSituacaoForma(admin, crediario.id(), true);
        List<FormaPagamento> ativas = vendas.formasAtivas(admin);
        for (FormaPagamento f : ativas) {
            if (f.tipo() != TipoFormaPagamento.DINHEIRO && f.tipo() != TipoFormaPagamento.CREDIARIO
                    && f.tipo() != TipoFormaPagamento.CREDITO_CLIENTE) {
                t.configuracoes.alterarSituacaoForma(admin, f.id(), false);
            }
        }
        assertThatThrownBy(() -> t.configuracoes.alterarSituacaoForma(admin, dinheiro.id(), false))
                .isInstanceOf(NegocioException.class).hasMessageContaining("única forma de pagamento ativa");
    }

    @Test
    void r08BateComR01ComCrediarioEAbatimento() throws Exception {
        long venda = vender(1, "40.00", 1);
        long soCrediario = vender(1, null, 1);
        devolver(venda, "1", FormaReembolso.DINHEIRO); // 60 abatidos + 40 em dinheiro
        devolver(soCrediario, "1", FormaReembolso.DINHEIRO); // 100 abatidos

        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(), hojeSo());
        ResultadoRelatorio<RelFormasPagamento.Linha> r08 = t.relatorios.gerar(admin, new RelFormasPagamento(),
                hojeSo());

        assertThat(r08.linhas()).extracting(RelFormasPagamento.Linha::forma).containsExactly("Crediário", "Dinheiro",
                "Reembolso: Abatimento", "Reembolso: Dinheiro");
        assertThat(r08.linhas().get(0).valor()).isEqualByComparingTo("160.00");
        assertThat(r08.linhas().get(2).reembolsos()).isEqualByComparingTo("160.00");
        assertThat(r08.linhas().get(2).devolucoes()).isEqualTo(2);
        assertThat(r08.linhas().get(3).reembolsos()).isEqualByComparingTo("40.00");
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::valor))
                .isEqualByComparingTo(Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::vendido));
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::reembolsos))
                .isEqualByComparingTo(Coluna.soma(r01.linhas(), RelVendasPeriodo.Linha::devolvido));
        assertThat(r08.totais().get(4)).as("a devolução mista conta uma vez só no total").isEqualTo("2");
    }

    @Test
    void r20MostraOAbatidoEAsParcelasQueFicaram() throws Exception {
        long venda = vender(3, null, 3);
        devolver(venda, "1", FormaReembolso.DINHEIRO);
        ResultadoRelatorio<RelDevolucoes.Linha> r20 = t.relatorios.gerar(admin, new RelDevolucoes(), hojeSo());
        assertThat(r20.linhas()).singleElement().satisfies(l -> {
            assertThat(l.forma()).isEqualTo(FormaReembolso.ABATIMENTO);
            assertThat(l.abatido()).isEqualByComparingTo("100.00");
            assertThat(l.parcelas()).isEqualTo("1/3: R$ 100,00; 2/3: R$ 100,00");
        });
    }

    @Test
    void enumDoTituloIgualAoCheckDoBanco() throws Exception {
        Set<String> doCheck = new HashSet<>();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                     + "WHERE conname = 'ck_tr_status'")) {
            rs.next();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(rs.getString(1));
            while (m.find()) {
                doCheck.add(m.group(1));
            }
        }
        assertThat(Arrays.stream(StatusTitulo.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(doCheck);
    }
}
