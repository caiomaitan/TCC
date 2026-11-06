package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosVinculo;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemContagem;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.Lote;
import br.com.varesync.model.LoteEntrada;
import br.com.varesync.model.ParteLote;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoMovimento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validade e lotes (F17.1 e F17.2), com os critérios de aceite da F17 que dependem da parte A (todos, menos o alerta):
 * FEFO, "SEM LOTE" primeiro, cancelamento, devolução parcial e avariada, venda sem controle, entrada em lotes, rastro da
 * NF-e, ligar o controle, negativo, transferência, perda por vencimento e a garantia no commit. As views
 * {@code vw_lote_inconsistente} e {@code vw_estoque_inconsistente} são conferidas ao fim de cada teste
 * ({@code IntegracaoBase}).
 */
class LoteServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private FormaPagamento dinheiro;
    private LocalDate hoje;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        dinheiro = t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow();
        t.caixa.abrir(admin, new BigDecimal("100.00"));
        hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    // ----- Ajudantes -----

    private static BigDecimal q(String valor) {
        return new BigDecimal(valor);
    }

    private long produto(String nome, boolean controla, String inicial) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, "UN", q("8.00"), q("5.00"), null, null, List.of(),
                inicial == null ? null : q(inicial), controla, null));
    }

    /** Produto com o controle ligado, lote A (vence em 10 dias, 20 un) e lote B (vence em 40 dias, 20 un). */
    private long iogurteComAeB() {
        long p = produto("Iogurte natural 170 g", true, null);
        entrarNoLote(p, "A", 10, "20");
        entrarNoLote(p, "B", 40, "20");
        return p;
    }

    private void entrarNoLote(long produto, String codigo, int dias, String quantidade) {
        t.estoque.ajustar(admin, produto, TipoAjuste.ENTRADA, q(quantidade), "Compra sem nota", null,
                ParteLote.novo(codigo, hoje.plusDays(dias), null));
    }

    private Lote lote(long produto, String codigo) {
        return t.estoque.lotes(admin, produto).stream()
                .filter(l -> codigo == null ? l.semLote() : l.codigo().equals(codigo)).findFirst().orElseThrow();
    }

    private BigDecimal saldoLote(long produto, String codigo) {
        return lote(produto, codigo).saldo();
    }

    private BigDecimal saldo(long produto) {
        return t.estoque.saldo(admin, produto).saldo();
    }

    private long vender(long produto, int unidades) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), BigDecimal.valueOf(unidades));
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private void devolver(long vendaId, String quantidade, boolean avariado) {
        t.relogio.avancar(Duration.ofMinutes(1));
        long vendaItemId = t.devolucoes.venda(admin, vendaId).itens().get(0).vendaItemId();
        t.devolucoes.registrar(admin, new DadosDevolucao(vendaId, List.of(new DadosDevolucao.Item(vendaItemId,
                q(quantidade), avariado)), "Cliente desistiu", FormaReembolso.DINHEIRO, null, null, null), null);
    }

    private long fornecedor() {
        return t.fornecedores.criar(admin, new DadosFornecedor("Laticínios Vale do Pardo Ltda", null,
                "AB12CD34EFGH83", null, null, null, null, null, null));
    }

    // ----- Aceite -----

    /** FEFO: A (10 dias, 20) e B (40 dias, 20); uma venda de 25 deixa A com 0 e B com 15. */
    @Test
    void fefoDoAceite() {
        long p = iogurteComAeB();
        vender(p, 25);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("0");
        assertThat(saldoLote(p, "B")).isEqualByComparingTo("15");
        assertThat(saldo(p)).isEqualByComparingTo("15");
    }

    /** "SEM LOTE" primeiro: 5 no "SEM LOTE" e A com 20; uma venda de 8 deixa o "SEM LOTE" com 0 e A com 17. */
    @Test
    void semLotePrimeiroDoAceite() {
        long p = produto("Queijo minas frescal", false, "5");
        t.produtos.definirControleValidade(admin, p, true);
        entrarNoLote(p, "A", 10, "20");
        vender(p, 8);
        assertThat(saldoLote(p, null)).isEqualByComparingTo("0");
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("17");
    }

    /** Cancelamento: cancelar a venda de 25 devolve 20 para A e 5 para B. */
    @Test
    void cancelamentoVoltaParaOsLotesDaVendaDoAceite() {
        long p = iogurteComAeB();
        long venda = vender(p, 25);
        t.vendas.cancelar(admin, venda, "Cliente desistiu", null);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("20");
        assertThat(saldoLote(p, "B")).isEqualByComparingTo("20");
    }

    /** Devolução parcial: devolver 3 da venda de 25 volta para B; depois mais 4 voltam 2 para B e 2 para A. */
    @Test
    void devolucaoParcialNaOrdemInversaDoAceite() {
        long p = iogurteComAeB();
        long venda = vender(p, 25);
        devolver(venda, "3", false);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("0");
        assertThat(saldoLote(p, "B")).isEqualByComparingTo("18");
        devolver(venda, "4", false);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("2");
        assertThat(saldoLote(p, "B")).isEqualByComparingTo("20");
        assertThat(saldo(p)).isEqualByComparingTo("22");
    }

    /** Devolução avariada: 1 avariado da venda de 25 faz B receber 1 e perder 1; os saldos dos lotes não mudam. */
    @Test
    void devolucaoAvariadaEntraESaiDoMesmoLoteDoAceite() throws Exception {
        long p = iogurteComAeB();
        long venda = vender(p, 25);
        devolver(venda, "1", true);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("0");
        assertThat(saldoLote(p, "B")).isEqualByComparingTo("15");
        assertThat(saldo(p)).isEqualByComparingTo("15");
        long loteB = lote(p, "B").id();
        assertThat(t.contar("SELECT count(*) FROM lote_movimento lm JOIN movimento_estoque m "
                + "ON m.id = lm.movimento_estoque_id WHERE lm.lote_id = " + loteB
                + " AND ((m.tipo = 'DEVOLUCAO_VENDA' AND lm.quantidade = 1) OR (m.tipo = 'PERDA' AND lm.quantidade = -1))"))
                .isEqualTo(2);
    }

    /**
     * Venda sem controle e ligar o controle: produto com saldo 10 e sem lotes fica com 10 no "SEM LOTE"; a devolução
     * de uma venda feita antes de ligar o controle volta para o "SEM LOTE".
     */
    @Test
    void vendaAntesDoControleEDevolucaoParaOSemLoteDoAceite() throws Exception {
        long p = produto("Presunto cozido", false, "13");
        long venda = vender(p, 3);
        t.produtos.definirControleValidade(admin, p, true);
        assertThat(t.estoque.lotes(admin, p)).singleElement().satisfies(l -> {
            assertThat(l.semLote()).isTrue();
            assertThat(l.saldo()).isEqualByComparingTo("10");
        });
        assertThat(t.auditoria(AcaoAuditoria.CONTROLE_VALIDADE_ALTERADO)).singleElement()
                .satisfies(d -> assertThat(d).contains("saldo inicial de 10 UN no SEM LOTE"));
        entrarNoLote(p, "P1", 20, "5");

        devolver(venda, "2", false);
        assertThat(saldoLote(p, null)).isEqualByComparingTo("12");
        assertThat(saldoLote(p, "P1")).isEqualByComparingTo("5");
        t.vendas.cancelar(admin, vender(p, 1), "Teste", null); // venda com controle: 1 do SEM LOTE e volta para ele
        assertThat(saldoLote(p, null)).isEqualByComparingTo("12");
    }

    /** Entrada em lotes: entrada de 30 em dois lotes (10 + 20) cria os dois lotes com esses saldos. */
    @Test
    void entradaEmDoisLotesDoAceite() {
        long p = produto("Manteiga 200 g", true, null);
        long entrada = t.compras.criar(admin, new DadosEntrada(fornecedor(), "777", hoje, null));
        long item = t.compras.adicionarItem(admin, entrada, p, q("30"), q("9.5000"), null);

        assertThatThrownBy(() -> t.compras.confirmar(admin, entrada))
                .hasMessageContaining("O produto Manteiga 200 g controla validade");
        t.compras.salvarLotesDoItem(admin, entrada, item, List.of(
                new LoteEntrada(" m10 ", hoje.plusDays(30), q("10")),
                new LoteEntrada("M20", hoje.plusDays(60), q("15"))));
        assertThatThrownBy(() -> t.compras.confirmar(admin, entrada))
                .hasMessageContaining("soma 25, mas o item tem 30");
        assertThatThrownBy(() -> t.compras.salvarLotesDoItem(admin, entrada, item, List.of(
                new LoteEntrada("M10", null, q("30"))))).hasMessageContaining("Informe a validade do lote 1");
        t.compras.salvarLotesDoItem(admin, entrada, item, List.of(
                new LoteEntrada(" m10 ", hoje.plusDays(30), q("10")),
                new LoteEntrada("M20", hoje.plusDays(60), q("20"))));

        t.compras.confirmar(admin, entrada);
        assertThat(saldoLote(p, "M10")).isEqualByComparingTo("10");
        assertThat(saldoLote(p, "M20")).isEqualByComparingTo("20");
        assertThat(lote(p, "M10").validade()).isEqualTo(hoje.plusDays(30));
        assertThat(saldo(p)).isEqualByComparingTo("30");
    }

    /** Em produto sem controle, a divisão da entrada é ignorada (nenhum lote). */
    @Test
    void produtoSemControleIgnoraADivisao() throws Exception {
        long p = produto("Arroz 5 kg", false, null);
        long entrada = t.compras.criar(admin, new DadosEntrada(fornecedor(), "778", hoje, null));
        long item = t.compras.adicionarItem(admin, entrada, p, q("10"), q("20.0000"), null);
        t.compras.salvarLotesDoItem(admin, entrada, item, List.of(new LoteEntrada("X", hoje.plusDays(9), q("4"))));
        t.compras.confirmar(admin, entrada);
        assertThat(saldo(p)).isEqualByComparingTo("10");
        assertThat(t.contar("SELECT count(*) FROM lote WHERE produto_id = " + p)).isZero();
    }

    /** NF-e: 3 CX (fator 12) com rastro de dois lotes (qLote 1 e 2) viram lotes de 12 e 24. */
    @Test
    void rastroDaNfeComFatorDoAceite() {
        long fornecedor = fornecedor();
        long leite = produto("Leite integral 1 L", true, null);
        t.vinculos.salvar(admin, leite, null, new DadosVinculo(fornecedor, "LEITE-CX12", "CX", q("12"), 3, true));

        long entrada = t.nfe.gerarEntrada(admin, LeitorNfeTest.recurso("nfe-rastro-lotes.xml"), Map.of());
        ItemEntrada item = t.compras.itens(admin, entrada).get(0);
        assertThat(item.quantidade()).isEqualByComparingTo("36");
        List<LoteEntrada> divisao = t.compras.lotesDoItem(admin, entrada, item.id());
        assertThat(divisao).extracting(LoteEntrada::codigo).containsExactly("L2610A", "L2610B");
        assertThat(divisao).extracting(LoteEntrada::quantidade)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(q("12"), q("24"));
        assertThat(divisao).extracting(LoteEntrada::validade)
                .containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 10));

        t.compras.confirmar(admin, entrada);
        assertThat(saldoLote(leite, "L2610A")).isEqualByComparingTo("12");
        assertThat(saldoLote(leite, "L2610B")).isEqualByComparingTo("24");
    }

    /** Rastro que não soma a quantidade do item: a entrada é gerada, mas só confirma depois de acertar a divisão. */
    @Test
    void rastroQueNaoSomaEsperaADivisaoSerAcertada() {
        long fornecedor = fornecedor();
        long leite = produto("Leite integral 1 L", true, null);
        t.vinculos.salvar(admin, leite, null, new DadosVinculo(fornecedor, "LEITE-CX12", "CX", q("12"), 3, true));
        byte[] xml = new String(LeitorNfeTest.recurso("nfe-rastro-lotes.xml"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("<qLote>2.000</qLote>", "<qLote>1.000</qLote>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        long entrada = t.nfe.gerarEntrada(admin, xml, Map.of());
        ItemEntrada item = t.compras.itens(admin, entrada).get(0);
        assertThatThrownBy(() -> t.compras.confirmar(admin, entrada)).hasMessageContaining("soma 24, mas o item tem 36");
        t.compras.salvarLotesDoItem(admin, entrada, item.id(), List.of(
                new LoteEntrada("L2610A", LocalDate.of(2026, 10, 20), q("12")),
                new LoteEntrada("L2610B", LocalDate.of(2026, 11, 10), q("24"))));
        t.compras.confirmar(admin, entrada);
        assertThat(saldo(leite)).isEqualByComparingTo("36");
    }

    /** Negativo: com estoque negativo permitido, uma venda de 30 com só o lote A (20) deixa A com 0 e o "SEM LOTE" −10. */
    @Test
    void faltaSaiDoSemLoteNegativoDoAceite() {
        long p = produto("Requeijão 200 g", true, null);
        entrarNoLote(p, "A", 10, "20");
        vender(p, 30);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("0");
        assertThat(saldoLote(p, null)).isEqualByComparingTo("-10");
        assertThat(saldo(p)).isEqualByComparingTo("-10");
    }

    /** Transferência: passar 2 de A para o "SEM LOTE" mantém o saldo do produto; passar mais que a origem é recusado. */
    @Test
    void transferenciaEntreLotesDoAceite() throws Exception {
        long p = iogurteComAeB();
        long a = lote(p, "A").id();
        t.estoque.transferirEntreLotes(admin, p, a, ParteLote.semLote(q("2")), "Lote digitado errado");
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("18");
        assertThat(saldoLote(p, null)).isEqualByComparingTo("2");
        assertThat(saldo(p)).isEqualByComparingTo("40");
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE produto_id = " + p)).isEqualTo(2);

        assertThatThrownBy(() -> t.estoque.transferirEntreLotes(admin, p, a, ParteLote.semLote(q("19")), "Teste"))
                .hasMessageContaining("tem só 18");
        long semLote = lote(p, null).id();
        assertThatThrownBy(() -> t.estoque.transferirEntreLotes(admin, p, semLote, ParteLote.semLote(q("1")),
                "Teste")).hasMessageContaining("destino diferente");
        assertThatThrownBy(() -> t.estoque.transferirEntreLotes(admin, p, a, ParteLote.semLote(q("1")), " "))
                .hasMessageContaining("motivo");
        // O "SEM LOTE" também precisa ter saldo; o destino pode ser um lote novo.
        t.estoque.transferirEntreLotes(admin, p, semLote, ParteLote.novo("c", hoje.plusDays(5), q("2")),
                "Contagem");
        assertThat(saldoLote(p, "C")).isEqualByComparingTo("2");
        assertThat(saldoLote(p, null)).isEqualByComparingTo("0");
        assertThat(t.auditoria(AcaoAuditoria.LOTE_TRANSFERIDO)).hasSize(2);
    }

    /** Perda: a perda por vencimento no lote escolhido baixa o lote e o estoque; sem o lote, é recusada. */
    @Test
    void perdaPorVencimentoNoLoteEscolhidoDoAceite() {
        long p = iogurteComAeB();
        assertThatThrownBy(() -> t.estoque.ajustar(admin, p, TipoAjuste.PERDA, q("5"), "Vencimento", null))
                .hasMessageContaining("escolha o lote");
        long b = lote(p, "B").id();
        t.estoque.ajustar(admin, p, TipoAjuste.PERDA, q("5"), "Vencimento", null, ParteLote.existente(b, null));
        assertThat(saldoLote(p, "B")).isEqualByComparingTo("15");
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("20");
        assertThat(saldo(p)).isEqualByComparingTo("35");
        assertThatThrownBy(() -> t.estoque.ajustar(admin, p, TipoAjuste.SAIDA, q("16"), "Consumo", null,
                ParteLote.existente(b, null))).hasMessageContaining("tem só 15");
    }

    /** Inventário: a falta sai por FEFO; a sobra vai para o "SEM LOTE". */
    @Test
    void inventarioFaltaPorFefoESobraNoSemLote() {
        long p = iogurteComAeB();
        long inv = t.inventarios.criar(admin, "Conferência");
        t.inventarios.incluirProduto(admin, inv, p);
        ItemContagem item = t.inventarios.itensContagem(admin, inv).get(0);
        t.inventarios.registrarContagem(admin, inv, item.itemId(), q("35"));
        t.inventarios.aplicar(admin, inv);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("15");

        long inv2 = t.inventarios.criar(admin, "Recontagem");
        t.inventarios.incluirProduto(admin, inv2, p);
        t.inventarios.registrarContagem(admin, inv2, t.inventarios.itensContagem(admin, inv2).get(0).itemId(),
                q("38"));
        t.inventarios.aplicar(admin, inv2);
        assertThat(saldoLote(p, null)).isEqualByComparingTo("3");
        assertThat(saldo(p)).isEqualByComparingTo("38");
    }

    /** Estoque inicial do cadastro com controle: vai para o lote escolhido. */
    @Test
    void estoqueInicialNoLoteEscolhido() {
        long p = t.produtos.criar(admin, new DadosProduto("Pão de forma", null, "UN", q("9.00"), q("5.00"), null, null,
                List.of(), q("12"), true, ParteLote.novo("pf1", hoje.plusDays(7), null)));
        assertThat(t.estoque.lotes(admin, p)).singleElement().satisfies(l -> {
            assertThat(l.codigo()).isEqualTo("PF1");
            assertThat(l.saldo()).isEqualByComparingTo("12");
        });
    }

    /**
     * Desligar: os lotes ficam parados (o banco recusa linhas no razão) e a venda não os movimenta; ao ligar de novo, a
     * diferença negativa sai dos lotes como uma saída.
     */
    @Test
    void desligarParaOsLotesEReligarAcertaADiferenca() throws Exception {
        long p = iogurteComAeB();
        t.produtos.definirControleValidade(admin, p, false);
        vender(p, 5);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("20");
        long a = lote(p, "A").id();
        assertThatThrownBy(() -> new Transacao(dataSource).executar(con -> {
            try (var st = con.createStatement()) {
                st.executeUpdate("INSERT INTO lote_movimento (lote_id, tipo, quantidade, usuario_id) VALUES ("
                        + a + ", 'SALDO_INICIAL', 1, " + admin.usuarioId() + ")");
            }
            return null;
        })).hasMessageContaining("controle de validade desligado");

        t.produtos.definirControleValidade(admin, p, true);
        assertThat(saldoLote(p, "A")).isEqualByComparingTo("15");
        assertThat(saldoLote(p, "B")).isEqualByComparingTo("20");
    }

    /** Garantia: movimento de produto com controle sem distribuição em lotes é recusado no commit. */
    @Test
    void movimentoSemDistribuicaoRecusadoNoCommitDoAceite() throws Exception {
        long p = iogurteComAeB();
        assertThatThrownBy(() -> new Transacao(dataSource).executar(con -> new MovimentoEstoqueDAO().inserir(con, p,
                TipoMovimento.AJUSTE_ENTRADA, q("5"), null, null, null, null, null, "Direto no banco",
                admin.usuarioId(), OffsetDateTime.now(t.relogio)))).hasMessageContaining("não foi distribuído nos lotes");
        assertThat(saldo(p)).isEqualByComparingTo("40");
        // Saldo de lote só pelo razão; razão imutável.
        try (Connection con = dataSource.getConnection(); var st = con.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE lote_saldo SET saldo = 0"))
                    .hasMessageContaining("só pode ser alterado registrando um movimento do lote");
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM lote_movimento"))
                    .hasMessageContaining("não podem ser alterados nem excluídos");
        }
    }
}
