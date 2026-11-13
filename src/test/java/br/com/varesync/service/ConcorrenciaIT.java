package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.Lote;
import br.com.varesync.model.ParteLote;
import br.com.varesync.model.PlanoCrediario;
import br.com.varesync.model.PlanoRecebimento;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.TipoFormaPagamento;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Testes de concorrência (F11.8): duas operações ao mesmo tempo, cada uma na sua conexão (o pool do banco de teste),
 * largando juntas de uma barreira. Quando a situação é de dois computadores, cada operação usa o seu terminal, com o
 * seu caixa aberto, para que a trava do caixa não as enfileire antes da hora.
 *
 * <p>Cada cenário roda {@code concorrencia.repeticoes} vezes (o pom passa a propriedade à JVM dos testes; padrão 3),
 * com o banco limpo e as views de inconsistência conferidas a cada vez. O aceite da F11 roda com 20:
 * {@code mvn verify -Dit.test=ConcorrenciaIT -Dconcorrencia.repeticoes=20}.
 */
class ConcorrenciaIT extends IntegracaoBase {

    private static final int REPETICOES = Integer.getInteger("concorrencia.repeticoes", 3);
    private static final String TERMINAL_2 = "CAIXA-TESTE-2";

    private AcessoTeste t;
    private Sessao caixa1;
    private Sessao caixa2;

    /** Banco limpo, o supervisor no terminal do teste e o mesmo usuário num segundo terminal. */
    private void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        caixa1 = t.supervisor();
        caixa2 = new Sessao(caixa1.usuario(), TERMINAL_2);
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private long produto(String nome, String inicial) {
        return t.produtos.criar(caixa1, new DadosProduto(nome, null, "UN", v("10.00"), v("6.00"), null, null,
                List.of(), inicial == null ? null : v(inicial)));
    }

    private BigDecimal saldo(long produto) {
        return t.estoque.saldo(caixa1, produto).saldo();
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return t.vendas.formasAtivas(caixa1).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private Carrinho carrinho(Sessao sessao, long[] produtos, int[] quantidades) {
        Carrinho carrinho = new Carrinho();
        for (int i = 0; i < produtos.length; i++) {
            t.vendas.adicionarItem(sessao, carrinho, t.vendas.produtoParaVenda(sessao, produtos[i]),
                    BigDecimal.valueOf(quantidades[i]));
        }
        return carrinho;
    }

    private Recebimento emDinheiro(Sessao sessao, Carrinho carrinho) {
        Recebimento r = t.vendas.recebimento(sessao, carrinho);
        r.adicionar(forma(TipoFormaPagamento.DINHEIRO), r.falta());
        return r;
    }

    /**
     * Roda as ações juntas (cada uma numa thread, largando da mesma barreira) e devolve, na ordem, o resultado de cada
     * uma ou a exceção que ela lançou.
     */
    private static List<Object> juntos(List<Callable<?>> acoes) throws Exception {
        CyclicBarrier largada = new CyclicBarrier(acoes.size());
        ExecutorService threads = Executors.newFixedThreadPool(acoes.size());
        try {
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<?> acao : acoes) {
                futuros.add(threads.submit(() -> {
                    largada.await(10, TimeUnit.SECONDS);
                    try {
                        return acao.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> futuro : futuros) {
                resultados.add(futuro.get(60, TimeUnit.SECONDS)); // trava sem fim = falha por tempo
            }
            return resultados;
        } finally {
            threads.shutdownNow();
        }
    }

    private static List<Object> juntos(Callable<?> a, Callable<?> b) throws Exception {
        return juntos(List.of(a, b));
    }

    private static long sucessos(List<Object> resultados) {
        return resultados.stream().filter(r -> !(r instanceof Throwable)).count();
    }

    /** A que perdeu foi recusada por regra de negócio, com mensagem para o usuário (não por deadlock nem erro do banco). */
    private static void perdedoraRecusadaComMensagem(List<Object> resultados, String trecho, int repeticao) {
        List<Object> falhas = resultados.stream().filter(r -> r instanceof Throwable).toList();
        assertThat(falhas).as("repetição %d: uma operação recusada", repeticao).hasSize(1);
        assertThat(falhas.get(0)).as("repetição %d: recusa por regra, sem erro inesperado", repeticao)
                .isExactlyInstanceOf(NegocioException.class);
        assertThat(((Throwable) falhas.get(0)).getMessage()).as("repetição %d", repeticao).contains(trecho);
    }

    private static void semFalhas(List<Object> resultados, int repeticao) {
        for (Object r : resultados) {
            if (r instanceof Throwable erro) {
                throw new AssertionError("repetição " + repeticao + ": a operação falhou: " + erro, erro);
            }
        }
    }

    // ----- Os 7 cenários -----

    /** Duas vendas com os mesmos produtos em ordens diferentes, em dois terminais: sem deadlock e saldos certos. */
    @Test
    void duasVendasComOsMesmosProdutosEmOrdensDiferentes() throws Exception {
        for (int i = 1; i <= REPETICOES; i++) {
            preparar();
            long a = produto("Arroz 5 kg", "100");
            long b = produto("Feijão 1 kg", "100");
            long c = produto("Café 500 g", "100");
            t.caixa.abrir(caixa1, v("100.00"));
            t.caixa.abrir(caixa2, v("100.00"));
            Carrinho venda1 = carrinho(caixa1, new long[] {a, b, c}, new int[] {1, 2, 3});
            Carrinho venda2 = carrinho(caixa2, new long[] {c, b, a}, new int[] {4, 5, 6});
            Recebimento r1 = emDinheiro(caixa1, venda1);
            Recebimento r2 = emDinheiro(caixa2, venda2);

            List<Object> resultados = juntos(() -> t.vendas.finalizar(caixa1, venda1, r1),
                    () -> t.vendas.finalizar(caixa2, venda2, r2));

            semFalhas(resultados, i);
            assertThat(saldo(a)).as("repetição %d: arroz", i).isEqualByComparingTo("93");
            assertThat(saldo(b)).as("repetição %d: feijão", i).isEqualByComparingTo("93");
            assertThat(saldo(c)).as("repetição %d: café", i).isEqualByComparingTo("93");
            assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'VENDA'"))
                    .as("repetição %d: um movimento por item", i).isEqualTo(6);
            estoqueContinuaConsistente();
        }
    }

    /** Duas sangrias juntas no mesmo caixa: a soma não passa do dinheiro esperado. */
    @Test
    void duasSangriasJuntasNaoPassamDoDinheiroEsperado() throws Exception {
        for (int i = 1; i <= REPETICOES; i++) {
            preparar();
            long caixa = t.caixa.abrir(caixa1, v("100.00")).id();

            List<Object> resultados = juntos(
                    () -> t.caixa.registrarMovimento(caixa1, TipoCaixaMovimento.SANGRIA, v("60.00"), "Cofre", null),
                    () -> t.caixa.registrarMovimento(caixa1, TipoCaixaMovimento.SANGRIA, v("60.00"), "Cofre", null));

            assertThat(sucessos(resultados)).as("repetição %d: só uma sangria", i).isEqualTo(1);
            perdedoraRecusadaComMensagem(resultados, "maior que o dinheiro", i);
            assertThat(t.contar("SELECT count(*) FROM caixa_movimento WHERE tipo = 'SANGRIA'"))
                    .as("repetição %d", i).isEqualTo(1);
            assertThat(t.contar("SELECT (dinheiro_esperado * 100)::bigint FROM vw_caixa_resumo WHERE caixa_sessao_id = " + caixa))
                    .as("repetição %d: esperado de R$ 40,00, nunca negativo", i).isEqualTo(4000);
            estoqueContinuaConsistente();
        }
    }

    /** Dois terminais incluindo o mesmo produto em inventários diferentes: só um consegue. */
    @Test
    void mesmoProdutoEmDoisInventariosSoUmConsegue() throws Exception {
        for (int i = 1; i <= REPETICOES; i++) {
            preparar();
            long arroz = produto("Arroz 5 kg", "10");
            long inventario1 = t.inventarios.criar(caixa1, "Mercearia, corredor 1");
            long inventario2 = t.inventarios.criar(caixa2, "Mercearia, corredor 2");

            List<Object> resultados = juntos(() -> t.inventarios.incluirProduto(caixa1, inventario1, arroz),
                    () -> t.inventarios.incluirProduto(caixa2, inventario2, arroz));

            assertThat(sucessos(resultados)).as("repetição %d: só um inclui", i).isEqualTo(1);
            perdedoraRecusadaComMensagem(resultados, "não pode estar em dois inventários abertos", i);
            assertThat(t.contar("SELECT count(*) FROM inventario_item WHERE produto_id = " + arroz))
                    .as("repetição %d: o produto num inventário só", i).isEqualTo(1);
            estoqueContinuaConsistente();
        }
    }

    /** Duas confirmações da mesma entrada, em dois terminais: só uma vale (estoque e custo entram uma vez). */
    @Test
    void duasConfirmacoesDaMesmaEntradaSoUmaVale() throws Exception {
        for (int i = 1; i <= REPETICOES; i++) {
            preparar();
            long arroz = produto("Arroz 5 kg", "10");
            long fornecedor = t.fornecedores.criar(caixa1, new DadosFornecedor("Distribuidora Paulista Ltda", null,
                    "11222333000181", null, null, null, null, null, null));
            long entrada = t.compras.criar(caixa1, new DadosEntrada(fornecedor, "1234", null, null));
            t.compras.adicionarItem(caixa1, entrada, arroz, v("10"), v("8.0000"), null);

            List<Object> resultados = juntos(() -> t.compras.confirmar(caixa1, entrada),
                    () -> t.compras.confirmar(caixa2, entrada));

            assertThat(sucessos(resultados)).as("repetição %d: só uma confirmação", i).isEqualTo(1);
            perdedoraRecusadaComMensagem(resultados, "já foi confirmada", i);
            assertThat(saldo(arroz)).as("repetição %d: entrou uma vez", i).isEqualByComparingTo("20");
            assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'ENTRADA_COMPRA'"))
                    .as("repetição %d", i).isEqualTo(1);
            assertThat(t.contar("SELECT (custo * 10000)::bigint FROM produto WHERE id = " + arroz))
                    .as("repetição %d: custo médio de 7,0000 (10 a 6 + 10 a 8)", i).isEqualTo(70000);
            estoqueContinuaConsistente();
        }
    }

    /**
     * Duas vendas do mesmo produto com controle de validade, em dois terminais: lotes A (3, vence antes) e B (3); cada
     * venda leva 4. Juntas tiram 3 de A e 3 de B (nenhuma tira de um lote o que a outra já levou) e o que falta vai
     * para o "SEM LOTE": nenhum lote com código fica negativo.
     */
    @Test
    void duasVendasDoMesmoProdutoComValidadeNaoTiramDoMesmoLote() throws Exception {
        for (int i = 1; i <= REPETICOES; i++) {
            preparar();
            long iogurte = t.produtos.criar(caixa1, new DadosProduto("Iogurte natural 170 g", null, "UN", v("10.00"),
                    v("6.00"), null, null, List.of(), null, true, null));
            LocalDate hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
            t.estoque.ajustar(caixa1, iogurte, TipoAjuste.ENTRADA, v("3"), "Compra sem nota", null,
                    ParteLote.novo("A", hoje.plusDays(10), null));
            t.estoque.ajustar(caixa1, iogurte, TipoAjuste.ENTRADA, v("3"), "Compra sem nota", null,
                    ParteLote.novo("B", hoje.plusDays(40), null));
            t.caixa.abrir(caixa1, v("100.00"));
            t.caixa.abrir(caixa2, v("100.00"));
            Carrinho venda1 = carrinho(caixa1, new long[] {iogurte}, new int[] {4});
            Carrinho venda2 = carrinho(caixa2, new long[] {iogurte}, new int[] {4});
            Recebimento r1 = emDinheiro(caixa1, venda1);
            Recebimento r2 = emDinheiro(caixa2, venda2);

            List<Object> resultados = juntos(() -> t.vendas.finalizar(caixa1, venda1, r1),
                    () -> t.vendas.finalizar(caixa2, venda2, r2));

            semFalhas(resultados, i);
            List<Lote> lotes = t.estoque.lotes(caixa1, iogurte);
            for (Lote lote : lotes) {
                if (!lote.semLote()) {
                    assertThat(lote.saldo()).as("repetição %d: lote %s", i, lote.codigo()).isEqualByComparingTo("0");
                }
            }
            assertThat(lotes.stream().filter(Lote::semLote).findFirst().orElseThrow().saldo())
                    .as("repetição %d: o que faltou ficou no SEM LOTE", i).isEqualByComparingTo("-2");
            assertThat(saldo(iogurte)).as("repetição %d", i).isEqualByComparingTo("-2");
            assertThat(t.contar("SELECT count(*) FROM (SELECT l.id FROM lote_movimento lm JOIN lote l ON l.id = lm.lote_id "
                    + "JOIN movimento_estoque m ON m.id = lm.movimento_estoque_id WHERE m.tipo = 'VENDA' "
                    + "AND l.codigo IN ('A', 'B') GROUP BY l.id HAVING sum(lm.quantidade) = -3) x"))
                    .as("repetição %d: as vendas tiraram exatamente 3 de A e 3 de B", i).isEqualTo(2);
            estoqueContinuaConsistente();
        }
    }

    /** Duas vendas usando o crédito do mesmo cliente, em dois terminais: o saldo de crédito nunca fica negativo. */
    @Test
    void duasVendasComOCreditoDoMesmoClienteNuncaDeixamSaldoNegativo() throws Exception {
        for (int i = 1; i <= REPETICOES; i++) {
            preparar();
            long arroz = produto("Arroz 5 kg", "100");
            long maria = t.clientes.criar(caixa1, DadosCliente.rapido("Maria da Silva", "52998224725"));
            t.credito.ajustar(caixa1, maria, v("50.00"), "Crédito de troca");
            t.caixa.abrir(caixa1, v("100.00"));
            t.caixa.abrir(caixa2, v("100.00"));
            FormaPagamento credito = forma(TipoFormaPagamento.CREDITO_CLIENTE);
            List<Carrinho> carrinhos = new ArrayList<>();
            List<Recebimento> pagamentos = new ArrayList<>();
            for (Sessao sessao : List.of(caixa1, caixa2)) {
                Carrinho carrinho = carrinho(sessao, new long[] {arroz}, new int[] {4});
                t.vendas.identificarCliente(sessao, carrinho, maria);
                Recebimento r = t.vendas.recebimento(sessao, carrinho);
                r.adicionar(credito, v("40.00"));
                carrinhos.add(carrinho);
                pagamentos.add(r);
            }

            List<Object> resultados = juntos(() -> t.vendas.finalizar(caixa1, carrinhos.get(0), pagamentos.get(0)),
                    () -> t.vendas.finalizar(caixa2, carrinhos.get(1), pagamentos.get(1)));

            assertThat(sucessos(resultados)).as("repetição %d: só uma venda usa o crédito", i).isEqualTo(1);
            perdedoraRecusadaComMensagem(resultados, "Saldo de crédito insuficiente", i);
            assertThat(t.credito.extrato(caixa1, maria).saldo()).as("repetição %d: sobra R$ 10,00", i)
                    .isEqualByComparingTo("10.00");
            assertThat(t.contar("SELECT count(*) FROM cliente_credito WHERE saldo < 0"))
                    .as("repetição %d: nenhum saldo negativo", i).isZero();
            estoqueContinuaConsistente();
        }
    }

    /** Dois recebimentos da mesma parcela do crediário, em dois terminais: só um vale. */
    @Test
    void doisRecebimentosDaMesmaParcelaSoUmVale() throws Exception {
        for (int i = 1; i <= REPETICOES; i++) {
            preparar();
            long panela = produto("Panela de pressão", "10");
            long maria = t.clientes.criar(caixa1, DadosCliente.rapido("Maria da Silva", "52998224725"));
            t.clientes.alterarCrediario(caixa1, maria, v("2000.00"), false);
            long caixaA = t.caixa.abrir(caixa1, v("100.00")).id();
            long caixaB = t.caixa.abrir(caixa2, v("100.00")).id();
            Carrinho carrinho = carrinho(caixa1, new long[] {panela}, new int[] {1});
            t.vendas.identificarCliente(caixa1, carrinho, maria);
            Recebimento aPrazo = t.vendas.recebimento(caixa1, carrinho);
            aPrazo.adicionar(forma(TipoFormaPagamento.CREDIARIO), aPrazo.falta());
            LocalDate hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
            aPrazo.definirPlanoCrediario(new PlanoCrediario(1, hoje.plusDays(30)));
            long venda = t.vendas.finalizar(caixa1, carrinho, aPrazo).vendaId();

            FormaPagamento dinheiro = forma(TipoFormaPagamento.DINHEIRO);
            List<Recebimento> pagamentos = new ArrayList<>();
            for (Sessao sessao : List.of(caixa1, caixa2)) {
                PlanoRecebimento plano = t.recebimentos.simular(sessao, maria, List.of(), null, null);
                Recebimento pagamento = t.recebimentos.pagamento(plano);
                pagamento.adicionar(dinheiro, plano.total());
                pagamentos.add(pagamento);
            }

            List<Object> resultados = juntos(
                    () -> t.recebimentos.registrar(caixa1, maria, List.of(), null, null, pagamentos.get(0), null, null),
                    () -> t.recebimentos.registrar(caixa2, maria, List.of(), null, null, pagamentos.get(1), null, null));

            assertThat(sucessos(resultados)).as("repetição %d: só um recebimento", i).isEqualTo(1);
            List<Object> falhas = resultados.stream().filter(r -> r instanceof Throwable).toList();
            assertThat(falhas.get(0)).as("repetição %d: recusa por regra", i).isExactlyInstanceOf(NegocioException.class);
            assertThat(t.contar("SELECT count(*) FROM recebimento")).as("repetição %d", i).isEqualTo(1);
            assertThat(t.contar("SELECT (valor_pago * 100)::bigint FROM titulo_receber WHERE venda_id = " + venda))
                    .as("repetição %d: a parcela paga uma vez", i).isEqualTo(1000);
            assertThat(t.contar("SELECT count(*) FROM titulo_receber WHERE venda_id = " + venda
                    + " AND status = 'QUITADO'")).as("repetição %d", i).isEqualTo(1);
            assertThat(t.contar("SELECT (sum(dinheiro_esperado) * 100)::bigint FROM vw_caixa_resumo WHERE caixa_sessao_id IN ("
                    + caixaA + ", " + caixaB + ")")).as("repetição %d: o dinheiro entrou uma vez", i).isEqualTo(21000);
            estoqueContinuaConsistente();
        }
    }
}
