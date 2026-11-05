package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.BancoTeste;
import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.ConferenciaPedido;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosPedido;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosVinculo;
import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.ItemPedido;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusPedido;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.RelPedidoCompra;
import br.com.varesync.report.RelPedidosPendentes;
import br.com.varesync.report.RelatorioPdf;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.SugestaoCompra;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
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
 * Pedido de compra com o banco (F16.3 e F16.4): rascunho, envio, entrada vinculada (PARCIAL e RECEBIDO), pendente na
 * sugestão, cancelamento, fornecedor inativo, "Gerar pedido" da sugestão, conferência pedido × nota, PDF e R27. Com
 * {@code compras.dias_media} de 3 dias, 30 unidades vendidas hoje dão a média de 10 por dia do aceite.
 */
class PedidoCompraIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private FormaPagamento dinheiro;
    private long fornecedor;
    private long outroFornecedor;
    private long leite;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        t.configuracoes.salvar(admin, t.configuracoes.carregar(admin).comCompras(new Configuracoes.Compras(3, 15)));
        fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Laticínios Vale do Pardo Ltda", null,
                "AB12CD34EFGH83", null, null, null, null, null, null));
        outroFornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Distribuidora Ribeirão Ltda", null,
                "11222333000181", null, null, null, null, null, null));
        dinheiro = t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow();
        t.caixa.abrir(admin, new BigDecimal("100.00"));
        leite = produto("Leite integral 1 L", "7891000315507", "70");
        t.vinculos.salvar(admin, leite, null, new DadosVinculo(fornecedor, "LT-01", "CX", new BigDecimal("12"), 3,
                true));
    }

    private long produto(String nome, String codigo, String inicial) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.20"), null, null, List.of(new CodigoBarras(codigo, true)), new BigDecimal(inicial)));
    }

    private void vender(long produto, String quantidade) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto), new BigDecimal(quantidade));
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        t.vendas.finalizar(admin, carrinho, r);
    }

    private SugestaoCompra.Linha linha(long produto) {
        return t.sugestao.consultar(admin, null, null).linhas().stream().filter(l -> l.produtoId() == produto)
                .findFirst().orElseThrow();
    }

    private long pedidoEnviado(long fornecedorId, long produto, String quantidade, String custo) {
        long id = t.pedidos.criar(admin, new DadosPedido(fornecedorId, LocalDate.now(t.relogio).plusDays(3), null));
        t.pedidos.adicionarItem(admin, id, produto, new BigDecimal(quantidade), custo == null ? null
                : new BigDecimal(custo));
        t.pedidos.enviar(admin, id);
        return id;
    }

    /** Entrada da nota {@code numero} vinculada ao pedido, com um item; não confirmada. */
    private long entrada(long pedidoId, String numero, long produto, String quantidade, String custo) {
        long id = t.compras.criar(admin, new DadosEntrada(fornecedor, numero, null, null));
        t.compras.adicionarItem(admin, id, produto, new BigDecimal(quantidade), new BigDecimal(custo), null);
        t.compras.vincularPedido(admin, id, pedidoId);
        t.relogio.avancar(Duration.ofMinutes(1));
        return id;
    }

    private ItemPedido item(long pedidoId, long produto) {
        return t.pedidos.itens(admin, pedidoId).stream().filter(i -> i.produtoId() == produto).findFirst()
                .orElseThrow();
    }

    /**
     * Aceite F16 (pedido parcial e pendente): pedido de 144 reduz a sugestão; a entrada de 120 deixa o pedido PARCIAL
     * com 24 pendentes; uma nova entrada de 24 deixa o pedido RECEBIDO.
     */
    @Test
    void pedidoParcialEPendenteDoAceite() {
        vender(leite, "30");
        assertThat(linha(leite).sugestao()).as("sugestão básica do aceite").isEqualByComparingTo("144");

        long rascunho = t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null));
        t.pedidos.adicionarItem(admin, rascunho, leite, new BigDecimal("144"), null);
        assertThat(linha(leite).pendente()).as("rascunho não conta como pendente").isEqualByComparingTo("0");

        t.pedidos.enviar(admin, rascunho);
        long pedido = rascunho;
        SugestaoCompra.Linha comPedido = linha(leite);
        assertThat(comPedido.pendente()).isEqualByComparingTo("144");
        // 180 − 40 − 144 = −4 → nada a comprar: o pendente reduz a sugestão.
        assertThat(comPedido.sugestao()).isEqualByComparingTo("0");

        long primeira = entrada(pedido, "501", leite, "120", "3.50");
        assertThat(linha(leite).pendente()).as("entrada aberta ainda não recebe").isEqualByComparingTo("144");
        t.compras.confirmar(admin, primeira);
        PedidoCompra parcial = t.pedidos.buscar(admin, pedido);
        assertThat(parcial.status()).isEqualTo(StatusPedido.PARCIAL);
        assertThat(parcial.quantidadePendente()).isEqualByComparingTo("24");
        assertThat(item(pedido, leite).quantidadeRecebida()).isEqualByComparingTo("120");
        assertThat(linha(leite).pendente()).isEqualByComparingTo("24");

        long segunda = entrada(pedido, "502", leite, "24", "3.50");
        t.compras.confirmar(admin, segunda);
        assertThat(t.pedidos.buscar(admin, pedido).status()).isEqualTo(StatusPedido.RECEBIDO);
        assertThat(t.pedidos.buscar(admin, pedido).quantidadePendente()).isEqualByComparingTo("0");
        assertThat(linha(leite).pendente()).isEqualByComparingTo("0");
        assertThat(t.pedidos.entradas(admin, pedido)).containsExactly(primeira, segunda);
    }

    /** Recebida acima da pedida e produto fora do pedido são aceitos; o pendente nunca fica negativo. */
    @Test
    void recebidaAcimaDaPedidaEProdutoForaDoPedidoComConferencia() {
        long pao = produto("Pão de forma", "7896004000015", "5");
        long queijo = produto("Queijo prato", "7891234567895", "5");
        long pedido = t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null));
        t.pedidos.adicionarItem(admin, pedido, leite, new BigDecimal("144"), new BigDecimal("3.3333"));
        t.pedidos.adicionarItem(admin, pedido, pao, new BigDecimal("10"), new BigDecimal("4.00"));
        t.pedidos.enviar(admin, pedido);

        long id = t.compras.criar(admin, new DadosEntrada(fornecedor, "900", null, null));
        t.compras.adicionarItem(admin, id, leite, new BigDecimal("150"), new BigDecimal("3.5000"), null);
        t.compras.adicionarItem(admin, id, queijo, new BigDecimal("4"), new BigDecimal("12.00"), null);
        t.compras.vincularPedido(admin, id, pedido);

        ConferenciaPedido c = t.compras.conferencia(admin, id);
        assertThat(c.pedido().id()).isEqualTo(pedido);
        assertThat(c.linhas()).extracting(ConferenciaPedido.Linha::produtoId).containsExactly(leite, pao, queijo);
        ConferenciaPedido.Linha l = c.linhas().get(0);
        assertThat(l.situacao()).isEqualTo(ConferenciaPedido.Linha.ACIMA);
        assertThat(l.quantidadeDivergente()).isTrue();
        assertThat(l.custoDivergente()).isTrue();
        assertThat(l.pendenteDepois()).isEqualByComparingTo("0");
        assertThat(c.linhas().get(1).situacao()).isEqualTo(ConferenciaPedido.Linha.NAO_VEIO);
        assertThat(c.linhas().get(1).custoDivergente()).isFalse();
        assertThat(c.linhas().get(2).situacao()).isEqualTo(ConferenciaPedido.Linha.FORA_DO_PEDIDO);
        assertThat(c.temDivergencia()).isTrue();

        t.compras.confirmar(admin, id);
        assertThat(item(pedido, leite).quantidadeRecebida()).isEqualByComparingTo("150");
        assertThat(item(pedido, leite).pendente()).isEqualByComparingTo("0");
        assertThat(t.pedidos.itens(admin, pedido)).extracting(ItemPedido::produtoId).containsExactly(leite, pao);
        PedidoCompra p = t.pedidos.buscar(admin, pedido);
        assertThat(p.status()).as("o pão não veio").isEqualTo(StatusPedido.PARCIAL);
        assertThat(p.quantidadePendente()).as("150 de 144 não abate o pão").isEqualByComparingTo("10");
        assertThat(linha(leite).pendente()).isEqualByComparingTo("0");
        assertThat(linha(pao).pendente()).isEqualByComparingTo("10");

        // Na entrada confirmada, a "recebida antes" desconta o que esta nota trouxe.
        ConferenciaPedido depois = t.compras.conferencia(admin, id);
        assertThat(depois.linhas().get(0).recebidaAntes()).isEqualByComparingTo("0");
        assertThat(depois.linhas().get(0).situacao()).isEqualTo(ConferenciaPedido.Linha.ACIMA);
    }

    /** Uma nota que traz exatamente o que faltava, com o custo previsto, confere. */
    @Test
    void conferenciaSemDivergencia() {
        long pedido = pedidoEnviado(fornecedor, leite, "24", "3.5000");
        long id = entrada(pedido, "10", leite, "24", "3.50");
        ConferenciaPedido c = t.compras.conferencia(admin, id);
        assertThat(c.linhas()).singleElement().satisfies(l -> {
            assertThat(l.situacao()).isEqualTo(ConferenciaPedido.Linha.CONFERE);
            assertThat(l.custoDivergente()).isFalse();
        });
        assertThat(c.temDivergencia()).isFalse();
    }

    /** Cancelar só RASCUNHO, ENVIADO ou PARCIAL; no PARCIAL, o que faltava deixa de ser pendente. */
    @Test
    void cancelarParcialTiraOPendenteEOsDemaisNaoCancelam() {
        long pedido = pedidoEnviado(fornecedor, leite, "144", null);
        t.compras.confirmar(admin, entrada(pedido, "1", leite, "120", "3.50"));
        assertThat(linha(leite).pendente()).isEqualByComparingTo("24");

        long aberta = entrada(pedido, "2", leite, "24", "3.50");
        assertThatThrownBy(() -> t.pedidos.cancelar(admin, pedido)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("entrada nº " + aberta);
        t.compras.vincularPedido(admin, aberta, null);
        assertThat(t.compras.buscar(admin, aberta).pedidoId()).isNull();

        t.pedidos.cancelar(admin, pedido);
        PedidoCompra cancelado = t.pedidos.buscar(admin, pedido);
        assertThat(cancelado.status()).isEqualTo(StatusPedido.CANCELADO);
        assertThat(cancelado.canceladoPorNome()).isNotNull();
        assertThat(linha(leite).pendente()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> t.pedidos.cancelar(admin, pedido)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("cancelado");
        assertThatThrownBy(() -> t.compras.vincularPedido(admin, aberta, pedido))
                .isInstanceOf(NegocioException.class).hasMessageContaining("enviado ou parcial");

        long recebido = pedidoEnviado(fornecedor, leite, "10", null);
        t.compras.confirmar(admin, entrada(recebido, "3", leite, "10", "3.50"));
        assertThat(t.pedidos.buscar(admin, recebido).status()).isEqualTo(StatusPedido.RECEBIDO);
        assertThatThrownBy(() -> t.pedidos.cancelar(admin, recebido)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("recebido");

        long rascunho = t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null));
        t.pedidos.cancelar(admin, rascunho);
        assertThat(t.pedidos.buscar(admin, rascunho).status()).isEqualTo(StatusPedido.CANCELADO);
    }

    /** Só o rascunho muda; envio exige itens; um produto por pedido; custo proposto pelo vínculo ou pelo produto. */
    @Test
    void rascunhoEnvioEItens() {
        long pao = produto("Pão de forma", "7896004000015", "5");
        long pedido = t.pedidos.criar(admin, new DadosPedido(fornecedor, null, "  entregar cedo  "));
        assertThat(t.pedidos.buscar(admin, pedido).observacao()).isEqualTo("entregar cedo");
        assertThatThrownBy(() -> t.pedidos.enviar(admin, pedido)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("não tem itens");

        // Sem entrada do fornecedor, o custo proposto é o do produto.
        assertThat(t.pedidos.custoProposto(admin, pedido, leite)).isEqualByComparingTo("3.20");
        long item = t.pedidos.adicionarItem(admin, pedido, leite, new BigDecimal("144"), null);
        assertThat(item(pedido, leite).custoPrevisto()).isEqualByComparingTo("3.20");
        assertThat(item(pedido, leite).quantidadeNaUnidadeDeCompra()).isEqualByComparingTo("12");
        assertThatThrownBy(() -> t.pedidos.adicionarItem(admin, pedido, leite, BigDecimal.ONE, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("já está neste pedido");
        assertThatThrownBy(() -> t.pedidos.adicionarItem(admin, pedido, pao, new BigDecimal("1.5"), null))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> t.pedidos.adicionarItem(admin, pedido, pao, BigDecimal.ZERO, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("maior que zero");
        t.pedidos.alterarItem(admin, pedido, item, new BigDecimal("120"), new BigDecimal("3.1"));
        assertThat(item(pedido, leite).quantidade()).isEqualByComparingTo("120");
        assertThat(t.pedidos.buscar(admin, pedido).valorPrevisto()).isEqualByComparingTo("372.00");

        t.pedidos.enviar(admin, pedido);
        PedidoCompra enviado = t.pedidos.buscar(admin, pedido);
        assertThat(enviado.status()).isEqualTo(StatusPedido.ENVIADO);
        assertThat(enviado.enviadoEm()).isNotNull();
        assertThatThrownBy(() -> t.pedidos.adicionarItem(admin, pedido, pao, BigDecimal.ONE, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("só o rascunho");
        assertThatThrownBy(() -> t.pedidos.removerItem(admin, pedido, item)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> t.pedidos.enviar(admin, pedido)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> t.pedidos.criar(admin, new DadosPedido(fornecedor,
                LocalDate.now(t.relogio).minusDays(1), null))).isInstanceOf(NegocioException.class)
                .hasMessageContaining("antes de hoje");

        // Depois de uma entrada confirmada do fornecedor, o custo proposto é o último custo do vínculo.
        t.compras.confirmar(admin, entrada(pedido, "77", leite, "120", "3.75"));
        long outro = t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null));
        assertThat(t.pedidos.custoProposto(admin, outro, leite)).isEqualByComparingTo("3.75");
        assertThat(t.pedidos.custoProposto(admin, outro, pao)).as("sem vínculo, o custo do produto")
                .isEqualByComparingTo("3.20");
        assertThat(contarAuditoria("PEDIDO_ENVIADO")).isEqualTo(1);
        assertThat(contarAuditoria("PEDIDO_CRIADO")).isEqualTo(2);
    }

    /** A entrada só recebe pedido ENVIADO ou PARCIAL do mesmo fornecedor e não troca de fornecedor com pedido. */
    @Test
    void vinculoDaEntradaComOPedido() {
        long rascunho = t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null));
        t.pedidos.adicionarItem(admin, rascunho, leite, BigDecimal.TEN, null);
        long doOutro = pedidoEnviado(outroFornecedor, leite, "10", null);
        long certo = pedidoEnviado(fornecedor, leite, "10", null);
        long id = t.compras.criar(admin, new DadosEntrada(fornecedor, "1", null, null));

        assertThat(t.compras.pedidosParaVincular(admin, id)).extracting(PedidoCompra::id).containsExactly(certo);
        assertThatThrownBy(() -> t.compras.vincularPedido(admin, id, rascunho)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("rascunho");
        assertThatThrownBy(() -> t.compras.vincularPedido(admin, id, doOutro)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("Distribuidora Ribeirão");
        t.compras.vincularPedido(admin, id, certo);
        EntradaResumo e = t.compras.buscar(admin, id);
        assertThat(e.pedidoId()).isEqualTo(certo);
        assertThatThrownBy(() -> t.compras.alterarDados(admin, id, new DadosEntrada(outroFornecedor, "1", null,
                null))).isInstanceOf(NegocioException.class).hasMessageContaining("Desvincule o pedido");
        t.compras.alterarDados(admin, id, new DadosEntrada(fornecedor, "2", null, null));
        assertThatThrownBy(() -> t.compras.conferencia(admin, t.compras.criar(admin,
                new DadosEntrada(fornecedor, "3", null, null)))).isInstanceOf(NegocioException.class)
                .hasMessageContaining("não está vinculada");
    }

    /**
     * Fornecedor inativo não recebe pedido novo (nem envio) e deixa de valer como principal na sugestão: prazo 0,
     * fator 1 e aviso na linha; o "Gerar pedido" deixa a linha de fora, com aviso.
     */
    @Test
    void fornecedorInativo() {
        vender(leite, "30");
        long rascunho = t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null));
        t.pedidos.adicionarItem(admin, rascunho, leite, BigDecimal.TEN, null);
        t.fornecedores.alterarSituacao(admin, fornecedor, false);

        assertThatThrownBy(() -> t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("inativo");
        assertThatThrownBy(() -> t.pedidos.enviar(admin, rascunho)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("inativo");
        assertThat(t.pedidos.fornecedores(admin, null)).extracting(f -> f.id()).containsExactly(outroFornecedor);
        assertThat(t.pedidos.fornecedores(admin, fornecedor)).hasSize(2);

        SugestaoCompra.Linha l = linha(leite);
        assertThat(l.fornecedorId()).isNull();
        assertThat(l.prazoEntregaDias()).isZero();
        assertThat(l.fator()).isEqualByComparingTo("1");
        assertThat(l.aviso()).isEqualTo(SugestaoCompra.avisoInativo("Laticínios Vale do Pardo Ltda"));
        // Média 10 × 15 = 150 − 40 = 110, sem o múltiplo de 12.
        assertThat(l.sugestao()).isEqualByComparingTo("110");
        assertThat(t.sugestao.consultar(admin, fornecedor, null).linhas()).as("filtro pelo principal inativo")
                .isEmpty();

        assertThatThrownBy(() -> t.pedidos.gerarDaSugestao(admin, List.of(leite)))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("fornecedor principal inativo (Laticínios Vale do Pardo Ltda)");
    }

    /**
     * "Gerar pedido": um rascunho por fornecedor principal, com a sugestão e o último custo do vínculo (sem ele, o
     * custo do produto); linha sem principal ou sem sugestão fica de fora, com aviso.
     */
    @Test
    void gerarPedidoDaSugestao() {
        long pao = produto("Pão de forma", "7896004000015", "5");
        t.vinculos.salvar(admin, pao, null, new DadosVinculo(outroFornecedor, null, null, BigDecimal.ONE, 0, true));
        long semPrincipal = produto("Queijo prato", "7891234567895", "0");
        long semVenda = produto("Sal refinado", "7891000053508", "100");
        t.vinculos.salvar(admin, semVenda, null, new DadosVinculo(fornecedor, null, null, BigDecimal.ONE, 0, true));
        // Último custo do vínculo do leite: R$ 3,75 (entrada confirmada do fornecedor).
        long nota = t.compras.criar(admin, new DadosEntrada(fornecedor, "1", null, null));
        t.compras.adicionarItem(admin, nota, leite, BigDecimal.ONE, new BigDecimal("3.75"), null);
        t.compras.confirmar(admin, nota);
        vender(leite, "31");
        vender(pao, "6");
        vender(semPrincipal, "3");

        BigDecimal sugestaoLeite = linha(leite).sugestao();
        PedidoCompraService.Geracao g = t.pedidos.gerarDaSugestao(admin, List.of(leite, pao, semPrincipal, semVenda));
        assertThat(g.pedidos()).extracting(PedidoCompra::fornecedorNome)
                .containsExactly("Distribuidora Ribeirão Ltda", "Laticínios Vale do Pardo Ltda");
        assertThat(g.pedidos()).allSatisfy(p -> assertThat(p.status()).isEqualTo(StatusPedido.RASCUNHO));
        assertThat(g.avisos()).hasSize(2).anySatisfy(a -> assertThat(a).startsWith("Queijo prato: sem fornecedor"))
                .anySatisfy(a -> assertThat(a).startsWith("Sal refinado: sem sugestão"));

        ItemPedido doLeite = item(g.pedidos().get(1).id(), leite);
        assertThat(doLeite.quantidade()).isEqualByComparingTo(sugestaoLeite);
        assertThat(doLeite.quantidade().remainder(new BigDecimal("12"))).isEqualByComparingTo("0");
        assertThat(doLeite.custoPrevisto()).isEqualByComparingTo("3.75");
        ItemPedido doPao = item(g.pedidos().get(0).id(), pao);
        assertThat(doPao.custoPrevisto()).as("sem último custo no vínculo, o custo do produto")
                .isEqualByComparingTo("3.20");
        assertThat(t.pedidos.itens(admin, g.pedidos().get(1).id())).extracting(ItemPedido::produtoId)
                .containsExactly(leite);
        assertThat(linha(leite).pendente()).as("rascunho ainda não é pendente").isEqualByComparingTo("0");

        assertThatThrownBy(() -> t.pedidos.gerarDaSugestao(admin, List.of()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Marque");
        assertThatThrownBy(() -> t.pedidos.gerarDaSugestao(admin, List.of(semVenda)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Nenhum dos produtos");
    }

    /** PDF do pedido com a quantidade também na unidade de compra (144 ÷ 12 = 12 CX). */
    @Test
    void pdfDoPedidoComAUnidadeDeCompra() {
        long pedido = pedidoEnviado(fornecedor, leite, "144", "3.3333");
        ResultadoRelatorio<ItemPedido> r = t.pedidos.exportar(admin, pedido);
        assertThat(r.titulo()).isEqualTo("Pedido de compra nº " + pedido);
        assertThat(r.descricaoFiltros()).contains("Laticínios Vale do Pardo Ltda").contains("Previsão de entrega")
                .contains("Enviado");
        assertThat(r.colunas()).extracting(c -> c.titulo()).contains("Cód. no fornecedor", "Na unidade de compra");
        assertThat(RelPedidoCompra.naUnidadeDeCompra(r.linhas().get(0))).isEqualTo("12 CX");
        assertThat(r.linhas().get(0).codigoNoFornecedor()).isEqualTo("LT-01");
        byte[] pdf = new RelatorioPdf().gerar(r);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    /** R27: itens com pendente dos pedidos ENVIADO e PARCIAL, filtro de fornecedor e atrasados. */
    @Test
    void r27PedidosPendentes() {
        long pao = produto("Pão de forma", "7896004000015", "5");
        long pedido = t.pedidos.criar(admin, new DadosPedido(fornecedor, LocalDate.now(t.relogio), null));
        t.pedidos.adicionarItem(admin, pedido, leite, new BigDecimal("144"), new BigDecimal("3.00"));
        t.pedidos.adicionarItem(admin, pedido, pao, new BigDecimal("10"), new BigDecimal("4.00"));
        t.pedidos.enviar(admin, pedido);
        t.compras.confirmar(admin, entrada(pedido, "1", leite, "120", "3.00"));
        long outro = pedidoEnviado(outroFornecedor, pao, "5", "4.00");
        t.pedidos.criar(admin, new DadosPedido(fornecedor, null, null)); // rascunho fica de fora
        t.relogio.avancar(Duration.ofDays(1)); // a previsão do primeiro passou

        ResultadoRelatorio<RelPedidosPendentes.Linha> r27 = t.relatorios.gerar(admin, new RelPedidosPendentes(),
                Filtros.vazios());
        assertThat(r27.titulo()).isEqualTo("R27 — Pedidos pendentes");
        assertThat(r27.linhas()).extracting(RelPedidosPendentes.Linha::pedidoId).containsExactly(pedido, pedido,
                outro);
        RelPedidosPendentes.Linha l = r27.linhas().get(0);
        assertThat(l.situacao()).isEqualTo("Parcial");
        assertThat(l.pendente()).isEqualByComparingTo("24");
        assertThat(l.valorPendente()).isEqualByComparingTo("72.00");
        assertThat(l.atrasado()).isTrue();
        assertThat(r27.linhas().get(2).atrasado()).isFalse();
        assertThat(r27.totais()).contains("R$ 132,00", "2 atrasado(s)");

        ResultadoRelatorio<RelPedidosPendentes.Linha> soOutro = t.relatorios.gerar(admin, new RelPedidosPendentes(),
                Filtros.vazios().comFornecedor(new Opcao(outroFornecedor, "Distribuidora Ribeirão Ltda")));
        assertThat(soOutro.linhas()).extracting(RelPedidosPendentes.Linha::pedidoId).containsExactly(outro);
    }

    @Test
    void precisaDaPermissaoDeCompras() {
        long pedido = pedidoEnviado(fornecedor, leite, "10", null);
        t.usuarios.criar(admin, "estoquista", "Estoquista", false, EnumSet.of(Permissao.ESTOQUE), "inicial1",
                "inicial1");
        Sessao estoquista = t.acesso.entrar("estoquista", "inicial1");
        assertThatThrownBy(() -> t.pedidos.listar(estoquista, Set.of(), null))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.pedidos.cancelar(estoquista, pedido)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.pedidos.gerarDaSugestao(estoquista, List.of(leite)))
                .isInstanceOf(PermissaoNegadaException.class);
    }

    /** Fornecedor ou produto com pedido não é excluído (o fornecedor oferece inativar). */
    @Test
    void fornecedorEProdutoComPedidoNaoSaoExcluidos() {
        long pao = produto("Pão de forma", "7896004000015", "0");
        long pedido = t.pedidos.criar(admin, new DadosPedido(outroFornecedor, null, null));
        t.pedidos.adicionarItem(admin, pedido, pao, BigDecimal.ONE, null);
        assertThatThrownBy(() -> t.fornecedores.excluir(admin, outroFornecedor))
                .isInstanceOf(RegistroEmUsoException.class).hasMessageContaining("1 pedido de compra");
        assertThatThrownBy(() -> t.produtos.excluir(admin, pao)).isInstanceOf(RegistroEmUsoException.class)
                .hasMessageContaining("pedido de compra");
    }

    @Test
    void statusDoPedidoIgualAoCheckDoBanco() throws Exception {
        String definicao;
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_pc_status'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                definicao = rs.getString(1);
            }
        }
        Set<String> doBanco = new HashSet<>();
        Matcher m = Pattern.compile("'(\\w+)'").matcher(definicao);
        while (m.find()) {
            doBanco.add(m.group(1));
        }
        assertThat(Arrays.stream(StatusPedido.values()).map(Enum::name).collect(Collectors.toSet()))
                .isEqualTo(doBanco);
    }

    private long contarAuditoria(String acao) {
        try (Connection con = dataSource.getConnection()) {
            return BancoTeste.contar(con, "SELECT count(*) FROM auditoria WHERE acao = '" + acao + "'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
