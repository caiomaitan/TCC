package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.VendaResumo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
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
 * PDV (6.6, segunda parte da Fase 4): venda completa (saldo, movimentos, pagamentos, totais), cancelamento de venda,
 * cancelamento de item, abandono, descontos com limite e liberação, regra de estoque e o roteiro de aceite da fase.
 */
class VendaServiceIT extends IntegracaoBase {

    private static final String COD_REFRIGERANTE = "7891000315507";

    private AcessoTeste t;
    private VendaService vendas;
    private Sessao admin;
    private long refrigerante;
    private long queijo;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        configurar("venda.permitir_estoque_negativo", "true");
        configurar("venda.desconto_maximo_operador_pct", "5");
        configurar("venda.cancelar_item_exige_supervisor", "false");
        vendas = t.vendas;
        admin = t.supervisor();
        refrigerante = produto("Refrigerante cola 2 L", "UN", "5.49", "3.20", "10", COD_REFRIGERANTE);
        queijo = produto("Queijo muçarela", "KG", "39.90", "28.00", "2", null);
    }

    // ----- Apoio -----

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private long produto(String nome, String unidade, String preco, String custo, String inicial, String codigo) {
        List<CodigoBarras> codigos = codigo == null ? List.of() : List.of(new CodigoBarras(codigo, true));
        return t.produtos.criar(admin, new DadosProduto(nome, null, unidade, v(preco), v(custo), null, null, codigos,
                inicial == null ? null : v(inicial)));
    }

    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar(login, "inicial1");
    }

    private void configurar(String chave, String valor) throws Exception {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE configuracao SET valor = ? WHERE chave = ?")) {
            ps.setString(1, valor);
            ps.setString(2, chave);
            assertThat(ps.executeUpdate()).as(chave).isEqualTo(1);
        }
    }

    /** O que o PDV faz com o texto digitado ou lido: interpreta, lê o produto e inclui. */
    private VendaService.Inclusao ler(Sessao sessao, Carrinho carrinho, String entrada) {
        EntradaPdv e = EntradaPdv.interpretar(entrada);
        ProdutoVenda produto = vendas.lerProduto(sessao, e.codigo());
        return vendas.adicionarItem(sessao, carrinho, produto,
                e.quantidade() == null ? BigDecimal.ONE : e.quantidade());
    }

    private FormaPagamento forma(Sessao sessao, TipoFormaPagamento tipo) {
        return vendas.formasAtivas(sessao).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private Recebimento emDinheiro(Sessao sessao, Carrinho carrinho, String valor) {
        Recebimento r = new Recebimento(carrinho.total());
        if (carrinho.total().signum() > 0) {
            r.adicionar(forma(sessao, TipoFormaPagamento.DINHEIRO), v(valor));
        }
        return r;
    }

    private BigDecimal saldo(long produtoId) throws Exception {
        return umValor("SELECT quantidade FROM produto_estoque WHERE produto_id = " + produtoId);
    }

    private BigDecimal esperado(long caixaId) throws Exception {
        return umValor("SELECT dinheiro_esperado FROM vw_caixa_resumo WHERE caixa_sessao_id = " + caixaId);
    }

    private BigDecimal umValor(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    private List<List<Object>> linhas(String sql) throws Exception {
        List<List<Object>> linhas = new ArrayList<>();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int colunas = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> linha = new ArrayList<>();
                for (int i = 1; i <= colunas; i++) {
                    Object valor = rs.getObject(i);
                    linha.add(valor instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : valor);
                }
                linhas.add(linha);
            }
        }
        return linhas;
    }

    // ----- Roteiro de aceite da Fase 4 -----

    @Test
    void roteiroDeAceiteDaFase4() throws Exception {
        Sessao caixa1 = operador("caixa1", Permissao.PDV);
        long caixaId = t.caixa.abrir(caixa1, v("100")).id();

        // 3 × produto de R$ 5,49 (leitor) + 0,345 kg de produto KG (código interno com multiplicador)
        Carrinho carrinho = new Carrinho();
        ler(caixa1, carrinho, "3*" + COD_REFRIGERANTE);
        ler(caixa1, carrinho, "0,345*" + queijo);
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("30.24");

        // 5% na venda, dentro do limite, com rateio pelo maior resto
        vendas.aplicarDescontoVenda(caixa1, carrinho, TipoDesconto.PERCENTUAL, v("5"), null);
        assertThat(carrinho.descontoVenda()).isEqualByComparingTo("1.51");
        assertThat(carrinho.total()).isEqualByComparingTo("28.73");

        // R$ 10,00 no PIX e o restante em dinheiro com uma nota de R$ 50,00
        Recebimento recebimento = new Recebimento(carrinho.total());
        recebimento.adicionar(forma(caixa1, TipoFormaPagamento.PIX), v("10.00"));
        recebimento.adicionar(forma(caixa1, TipoFormaPagamento.DINHEIRO), v("50.00"));
        t.relogio.avancar(Duration.ofMinutes(3));
        VendaService.VendaFinalizada venda = vendas.finalizar(caixa1, carrinho, recebimento);
        assertThat(venda.troco()).isEqualByComparingTo("31.27");
        long id = venda.vendaId();

        assertThat(linhas("SELECT caixa_sessao_id, usuario_id, status, valor_bruto, desconto_itens, desconto_venda, "
                + "valor_total, troco FROM venda WHERE id = " + id)).containsExactly(List.of(caixaId,
                caixa1.usuarioId(), "FINALIZADA", "30.24", "0", "1.51", "28.73", "31.27"));
        assertThat(umValor("SELECT extract(epoch FROM data_hora)::numeric FROM venda WHERE id = " + id).longValue())
                .isEqualTo(t.relogio.instant().getEpochSecond());
        assertThat(linhas("SELECT sequencia, produto_id, quantidade, preco_unitario, valor_bruto, desconto, "
                + "desconto_rateado, valor_liquido, custo_unitario, custo_total, cancelado FROM venda_item "
                + "WHERE venda_id = " + id + " ORDER BY sequencia")).containsExactly(
                List.of(1, refrigerante, "3", "5.49", "16.47", "0", "0.82", "15.65", "3.2", "9.6", false),
                List.of(2, queijo, "0.345", "39.9", "13.77", "0", "0.69", "13.08", "28", "9.66", false));
        assertThat(linhas("SELECT fp.tipo, vp.valor FROM venda_pagamento vp JOIN forma_pagamento fp "
                + "ON fp.id = vp.forma_pagamento_id WHERE vp.venda_id = " + id + " ORDER BY vp.id"))
                .containsExactly(List.of("PIX", "10"), List.of("DINHEIRO", "18.73"));

        // Saldos baixados e um movimento VENDA por item
        assertThat(saldo(refrigerante)).isEqualByComparingTo("7");
        assertThat(saldo(queijo)).isEqualByComparingTo("1.655");
        assertThat(linhas("SELECT me.produto_id, me.quantidade, vi.sequencia FROM movimento_estoque me "
                + "JOIN venda_item vi ON vi.id = me.venda_item_id WHERE me.tipo = 'VENDA' AND vi.venda_id = " + id
                + " ORDER BY vi.sequencia")).containsExactly(List.of(refrigerante, "-3", 1), List.of(queijo, "-0.345", 2));
        assertThat(esperado(caixaId)).isEqualByComparingTo("118.73");

        // Comprovante
        Comprovante comprovante = venda.comprovante();
        assertThat(comprovante.texto()).contains(Comprovante.NAO_FISCAL, "Venda nº " + id, "Operador caixa1",
                "R$ 28,73", "Troco", "R$ 31,27", "Mercado Bom Preço", "Obrigado pela preferência!");
        assertThat(comprovante.pagamentos()).containsExactly(new Comprovante.Pagamento("PIX", v("10.00")),
                new Comprovante.Pagamento("Dinheiro", v("50.00")));

        // Cancelar a venda com liberação → saldos devolvidos e venda fora do dinheiro esperado
        assertThatThrownBy(() -> vendas.cancelar(caixa1, id, "Cliente desistiu", null))
                .isInstanceOf(PermissaoNegadaException.class);
        Liberacao liberacao = t.acesso.liberar("admin", "segredo1", Permissao.CANCELAR_VENDA);
        vendas.cancelar(caixa1, id, "Cliente desistiu", liberacao);
        assertThat(linhas("SELECT status, cancelada_por, motivo_cancelamento FROM venda WHERE id = " + id))
                .containsExactly(List.of("CANCELADA", admin.usuarioId(), "Cliente desistiu"));
        assertThat(saldo(refrigerante)).isEqualByComparingTo("10");
        assertThat(saldo(queijo)).isEqualByComparingTo("2");
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'CANCELAMENTO_VENDA'")).isEqualTo(2);
        assertThat(esperado(caixaId)).isEqualByComparingTo("100.00");
        assertThat(t.auditoria(AcaoAuditoria.VENDA_CANCELADA)).containsExactly("Venda nº " + id
                + " de R$ 28,73 cancelada no caixa nº " + caixaId + ". Motivo: Cliente desistiu "
                + "(liberado por Ana Admin (admin))");
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = 'VENDA_CANCELADA' AND usuario_id = "
                + caixa1.usuarioId())).isEqualTo(1);
        assertThat(comprovante(caixa1, id).cancelada()).isTrue();

        // Sangria de R$ 50,00 e fechamento às cegas com diferença
        t.caixa.registrarMovimento(caixa1, TipoCaixaMovimento.SANGRIA, v("50"), "Recolhimento ao cofre",
                t.acesso.liberar("admin", "segredo1", Permissao.SANGRIA_SUPRIMENTO));
        RelatorioFechamento fechamento = t.caixa.fechar(caixa1, v("49"));
        assertThat(fechamento.sessao().valorEsperado()).isEqualByComparingTo("50.00");
        assertThat(fechamento.diferenca()).isEqualByComparingTo("-1.00");
        assertThat(fechamento.resumo().quantidadeCanceladas()).isEqualTo(1);

        // Nenhuma venda com o caixa fechado: nada é gravado e o carrinho continua
        Carrinho depois = new Carrinho();
        ler(caixa1, depois, COD_REFRIGERANTE);
        assertThatThrownBy(() -> vendas.finalizar(caixa1, depois, emDinheiro(caixa1, depois, "10")))
                .hasMessage(CaixaService.SEM_CAIXA_ABERTO);
        assertThat(t.contar("SELECT count(*) FROM venda")).isEqualTo(1);
        assertThat(depois.itens()).hasSize(1);
    }

    private Comprovante comprovante(Sessao sessao, long vendaId) {
        return vendas.comprovante(sessao, vendaId);
    }

    // ----- Leitura de produto -----

    @Test
    void leituraPorCodigoDeBarrasCodigoInternoEBloqueios() {
        assertThat(vendas.lerProduto(admin, COD_REFRIGERANTE).id()).isEqualTo(refrigerante);
        ProdutoVenda porCodigo = vendas.lerProduto(admin, String.valueOf(queijo));
        assertThat(porCodigo.id()).isEqualTo(queijo);
        assertThat(porCodigo.codigo()).isEqualTo(String.valueOf(queijo));
        assertThat(vendas.produtoParaVenda(admin, refrigerante).codigo()).isEqualTo(COD_REFRIGERANTE);

        assertThatThrownBy(() -> vendas.lerProduto(admin, "7891234567895"))
                .hasMessage("Produto não encontrado (código 7891234567895).");
        assertThatThrownBy(() -> vendas.lerProduto(admin, "9".repeat(30))).hasMessageContaining("não encontrado");

        long semPreco = produto("Brinde", "UN", "0", "1", null, null);
        assertThatThrownBy(() -> vendas.lerProduto(admin, String.valueOf(semPreco)))
                .hasMessage("O produto \"Brinde\" está sem preço de venda e não pode ser vendido.");
        t.produtos.alterarSituacao(admin, queijo, false);
        assertThatThrownBy(() -> vendas.lerProduto(admin, String.valueOf(queijo)))
                .hasMessage("O produto \"Queijo muçarela\" está inativo e não pode ser vendido.");
        assertThat(vendas.consultarPreco(admin, String.valueOf(queijo)).ativo()).isFalse(); // F7 mostra mesmo assim

        assertThat(vendas.pesquisarProdutos(admin, "refriger")).extracting(p -> p.id()).containsExactly(refrigerante);
        assertThat(vendas.pesquisarProdutos(admin, "queijo")).isEmpty(); // inativo fica fora
    }

    @Test
    void quantidadeFracionadaSoEmUnidadeFracionada() {
        Carrinho carrinho = new Carrinho();
        ProdutoVenda refri = vendas.lerProduto(admin, COD_REFRIGERANTE);
        assertThatThrownBy(() -> vendas.adicionarItem(admin, carrinho, refri, v("1.5")))
                .hasMessageContaining("deve ser um número inteiro");
        assertThatThrownBy(() -> vendas.adicionarItem(admin, carrinho, refri, v("0")))
                .hasMessage("A quantidade deve ser maior que zero.");
        ProdutoVenda kg = vendas.lerProduto(admin, String.valueOf(queijo));
        assertThatThrownBy(() -> vendas.adicionarItem(admin, carrinho, kg, v("0.3455")))
                .hasMessageContaining("no máximo 3 casas");
        assertThat(vendas.adicionarItem(admin, carrinho, kg, v("0.345")).item().valorBruto())
                .isEqualByComparingTo("13.77");
    }

    // ----- Estoque -----

    @Test
    void estoqueNegativoBloqueadoContandoOCarrinhoOuSoAvisado() throws Exception {
        configurar("venda.permitir_estoque_negativo", "false");
        Carrinho carrinho = new Carrinho();
        ProdutoVenda kg = vendas.lerProduto(admin, String.valueOf(queijo)); // saldo 2 KG
        assertThatThrownBy(() -> vendas.adicionarItem(admin, carrinho, kg, v("2.001")))
                .hasMessage("Estoque insuficiente de \"Queijo muçarela\": saldo 2,000 KG.");
        assertThat(vendas.adicionarItem(admin, carrinho, kg, v("1.5")).aviso()).isNull();
        assertThatThrownBy(() -> vendas.adicionarItem(admin, carrinho, kg, v("0.6")))
                .hasMessage("Estoque insuficiente de \"Queijo muçarela\": saldo 2,000 KG, já há 1,500 KG nesta venda.");
        vendas.adicionarItem(admin, carrinho, kg, v("0.5"));

        // Reconfere na finalização: outra saída depois da leitura deixa o saldo menor que a venda
        t.estoque.ajustar(admin, queijo, TipoAjuste.SAIDA, v("1"), "Acerto", null);
        long caixaId = t.caixa.abrir(admin, v("0")).id();
        assertThatThrownBy(() -> vendas.finalizar(admin, carrinho, emDinheiro(admin, carrinho, "100")))
                .hasMessage("Estoque insuficiente de \"Queijo muçarela\": saldo 1,000 KG, na venda 2,000 KG.");
        assertThat(t.contar("SELECT count(*) FROM venda")).isZero();
        assertThat(esperado(caixaId)).isEqualByComparingTo("0");

        configurar("venda.permitir_estoque_negativo", "true");
        assertThat(vendas.adicionarItem(admin, carrinho, kg, v("1")).aviso())
                .isEqualTo("Atenção: o estoque de \"Queijo muçarela\" ficará negativo (saldo 1,000 KG).");
        vendas.finalizar(admin, carrinho, emDinheiro(admin, carrinho, "200"));
        assertThat(saldo(queijo)).isEqualByComparingTo("-2");
    }

    @Test
    void produtoInativadoDepoisDeLidoNaoFinalizaEOCarrinhoContinua() throws Exception {
        t.caixa.abrir(admin, v("0"));
        Carrinho carrinho = new Carrinho();
        ler(admin, carrinho, COD_REFRIGERANTE);
        t.produtos.alterarSituacao(admin, refrigerante, false);
        assertThatThrownBy(() -> vendas.finalizar(admin, carrinho, emDinheiro(admin, carrinho, "10")))
                .hasMessageContaining("foi inativado");
        assertThat(t.contar("SELECT count(*) FROM venda")).isZero();
        assertThat(saldo(refrigerante)).isEqualByComparingTo("10");
        assertThat(carrinho.itens()).hasSize(1);
    }

    // ----- Descontos -----

    @Test
    void descontoAcimaDoLimiteExigeLiberacaoEAuditaQuemLiberou() throws Exception {
        Sessao caixa1 = operador("caixa1", Permissao.PDV);
        t.caixa.abrir(caixa1, v("0"));
        Carrinho carrinho = new Carrinho();
        ler(caixa1, carrinho, "2*" + COD_REFRIGERANTE); // 10,98

        assertThatThrownBy(() -> vendas.aplicarDescontoVenda(caixa1, carrinho, TipoDesconto.PERCENTUAL, v("10"), null))
                .isInstanceOf(LimiteDescontoException.class)
                .hasMessageContaining("passa do limite de 5,00% do operador");
        assertThat(carrinho.descontoVenda()).isEqualByComparingTo("0"); // nada mudou

        Liberacao errada = t.acesso.liberar("admin", "segredo1", Permissao.CANCELAR_VENDA);
        assertThatThrownBy(() -> vendas.aplicarDescontoVenda(caixa1, carrinho, TipoDesconto.PERCENTUAL, v("10"),
                errada)).isInstanceOf(PermissaoNegadaException.class);

        Liberacao liberacao = t.acesso.liberar("admin", "segredo1", Permissao.DESCONTO_ACIMA_LIMITE);
        vendas.aplicarDescontoVenda(caixa1, carrinho, TipoDesconto.PERCENTUAL, v("10"), liberacao);
        assertThat(carrinho.descontoVenda()).isEqualByComparingTo("1.10");
        assertThat(vendas.descontoPrecisaLiberacao(caixa1, carrinho)).isFalse();

        // Um desconto no item depois disso passa do que foi liberado e pede nova liberação
        assertThatThrownBy(() -> vendas.aplicarDescontoItem(caixa1, carrinho, 1, TipoDesconto.VALOR, v("1"), null))
                .isInstanceOf(LimiteDescontoException.class);
        assertThat(carrinho.item(1).orElseThrow().desconto()).isEqualByComparingTo("0");

        long id = vendas.finalizar(caixa1, carrinho, emDinheiro(caixa1, carrinho, "10")).vendaId();
        assertThat(t.auditoria(AcaoAuditoria.DESCONTO_LIBERADO)).containsExactly("Desconto de R$ 1,10 na venda nº "
                + id + " (10,02% do valor de R$ 10,98), acima do limite de 5,00% do operador (liberado por Ana Admin "
                + "(admin))");
    }

    @Test
    void quemTemAPermissaoDaDescontoAcimaDoLimiteETotalZeroDispensaPagamento() throws Exception {
        t.caixa.abrir(admin, v("0"));
        Carrinho carrinho = new Carrinho();
        ler(admin, carrinho, COD_REFRIGERANTE);
        ler(admin, carrinho, COD_REFRIGERANTE);
        vendas.aplicarDescontoItem(admin, carrinho, 1, TipoDesconto.PERCENTUAL, v("100"), null);
        vendas.aplicarDescontoVenda(admin, carrinho, TipoDesconto.VALOR, v("5.49"), null);
        assertThat(carrinho.total()).isEqualByComparingTo("0");

        long id = vendas.finalizar(admin, carrinho, new Recebimento(carrinho.total())).vendaId();
        assertThat(linhas("SELECT valor_bruto, desconto_itens, desconto_venda, valor_total, troco FROM venda "
                + "WHERE id = " + id)).containsExactly(List.of("10.98", "5.49", "5.49", "0", "0"));
        assertThat(t.contar("SELECT count(*) FROM venda_pagamento")).isZero();
        assertThat(t.auditoria(AcaoAuditoria.DESCONTO_LIBERADO)).singleElement().asString()
                .endsWith("(permissão de Ana Admin (admin))");
        assertThat(saldo(refrigerante)).isEqualByComparingTo("8");
    }

    // ----- Cancelamento de item e abandono -----

    @Test
    void itemCanceladoFicaGravadoSemRateioSemMovimentoEAuditado() throws Exception {
        long caixaId = t.caixa.abrir(admin, v("0")).id();
        Carrinho carrinho = new Carrinho();
        ler(admin, carrinho, COD_REFRIGERANTE);
        ler(admin, carrinho, "0,5*" + queijo);
        ler(admin, carrinho, COD_REFRIGERANTE);
        vendas.aplicarDescontoVenda(admin, carrinho, TipoDesconto.VALOR, v("0.50"), null);
        assertThat(vendas.cancelarItemPrecisaLiberacao(admin)).isFalse();
        assertThat(vendas.cancelarItem(admin, carrinho, 2, null)).isNull();
        assertThat(t.auditoria(AcaoAuditoria.ITEM_CANCELADO))
                .containsExactly("Item 2 cancelado durante a venda: 0,500 KG x Queijo muçarela (R$ 19,95)");

        long id = vendas.finalizar(admin, carrinho, emDinheiro(admin, carrinho, "20")).vendaId();
        assertThat(linhas("SELECT sequencia, valor_bruto, desconto_rateado, valor_liquido, cancelado FROM venda_item "
                + "WHERE venda_id = " + id + " ORDER BY sequencia")).containsExactly(
                List.of(1, "5.49", "0.25", "5.24", false),
                List.of(2, "19.95", "0", "19.95", true),
                List.of(3, "5.49", "0.25", "5.24", false));
        assertThat(linhas("SELECT valor_bruto, desconto_venda, valor_total FROM venda WHERE id = " + id))
                .containsExactly(List.of("10.98", "0.5", "10.48"));
        assertThat(t.contar("SELECT count(*) FROM movimento_estoque WHERE tipo = 'VENDA'")).isEqualTo(2);
        assertThat(saldo(queijo)).isEqualByComparingTo("2");
        assertThat(saldo(refrigerante)).isEqualByComparingTo("8");
        assertThat(esperado(caixaId)).isEqualByComparingTo("10.48");
    }

    @Test
    void cancelarItemComConfiguracaoLigadaPedeLiberacao() throws Exception {
        configurar("venda.cancelar_item_exige_supervisor", "true");
        Sessao caixa1 = operador("caixa1", Permissao.PDV);
        Carrinho carrinho = new Carrinho();
        ler(caixa1, carrinho, COD_REFRIGERANTE);
        assertThat(vendas.cancelarItemPrecisaLiberacao(caixa1)).isTrue();
        assertThat(vendas.cancelarItemPrecisaLiberacao(admin)).isFalse();
        assertThatThrownBy(() -> vendas.cancelarItem(caixa1, carrinho, 1, null))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThat(carrinho.temItensAtivos()).isTrue();
        assertThat(t.auditoria(AcaoAuditoria.ITEM_CANCELADO)).isEmpty();

        vendas.cancelarItem(caixa1, carrinho, 1, t.acesso.liberar("admin", "segredo1", Permissao.CANCELAR_VENDA));
        assertThat(carrinho.temItensAtivos()).isFalse();
        assertThat(t.auditoria(AcaoAuditoria.ITEM_CANCELADO)).singleElement().asString()
                .endsWith("(liberado por Ana Admin (admin))");
    }

    @Test
    void abandonarAuditaItensETotalENaoGravaVenda() throws Exception {
        Carrinho carrinho = new Carrinho();
        vendas.abandonar(admin, carrinho); // vazio: nada a registrar
        assertThat(t.auditoria(AcaoAuditoria.VENDA_ABANDONADA)).isEmpty();

        ler(admin, carrinho, "2*" + COD_REFRIGERANTE);
        ler(admin, carrinho, "0,345*" + queijo);
        vendas.cancelarItem(admin, carrinho, 2, null);
        vendas.abandonar(admin, carrinho);

        assertThat(carrinho.vazio()).isTrue();
        assertThat(t.auditoria(AcaoAuditoria.VENDA_ABANDONADA)).containsExactly("Venda abandonada com 1 item(ns), "
                + "total R$ 10,98. Itens: 2 UN x Refrigerante cola 2 L (R$ 10,98); (cancelado) 0,345 KG x Queijo "
                + "muçarela (R$ 13,77)");
        assertThat(t.contar("SELECT count(*) FROM venda")).isZero();
        assertThat(saldo(refrigerante)).isEqualByComparingTo("10");
    }

    // ----- Vendas da sessão e cancelamento -----

    @Test
    void vendasDaSessaoESoCancelaVendaDoCaixaAberto() throws Exception {
        assertThat(vendas.vendasDaSessao(admin)).isEmpty(); // sem caixa aberto
        t.caixa.abrir(admin, v("0"));
        Carrinho primeira = new Carrinho();
        ler(admin, primeira, COD_REFRIGERANTE);
        long id1 = vendas.finalizar(admin, primeira, emDinheiro(admin, primeira, "10")).vendaId();
        t.caixa.fechar(admin, v("5.49"));

        t.caixa.abrir(admin, v("0"));
        Carrinho segunda = new Carrinho();
        ler(admin, segunda, "2*" + COD_REFRIGERANTE);
        long id2 = vendas.finalizar(admin, segunda, emDinheiro(admin, segunda, "20")).vendaId();

        List<VendaResumo> daSessao = vendas.vendasDaSessao(admin);
        assertThat(daSessao).extracting(VendaResumo::id).containsExactly(id2);
        assertThat(daSessao.get(0).total()).isEqualByComparingTo("10.98");
        assertThat(daSessao.get(0).itens()).isEqualTo(1);

        assertThatThrownBy(() -> vendas.cancelar(admin, id1, "Erro", null))
                .hasMessageContaining("Só é possível cancelar vendas do caixa aberto neste terminal");
        assertThatThrownBy(() -> vendas.cancelar(admin, id2, "  ", null))
                .hasMessage("Informe o motivo do cancelamento.");
        vendas.cancelar(admin, id2, "Erro de digitação", null);
        assertThatThrownBy(() -> vendas.cancelar(admin, id2, "De novo", null)).hasMessageContaining("já está cancelada");
        assertThat(vendas.vendasDaSessao(admin).get(0).status()).isEqualTo(StatusVenda.CANCELADA);
        assertThat(linhas("SELECT cancelada_por FROM venda WHERE id = " + id2))
                .containsExactly(List.of(admin.usuarioId())); // tem a permissão: ele mesmo autoriza
        assertThat(saldo(refrigerante)).isEqualByComparingTo("9");
    }

    @Test
    void semPermissaoPdvNadaFunciona() {
        Sessao gerente = operador("gerente", Permissao.RELATORIOS);
        Carrinho carrinho = new Carrinho();
        assertThatThrownBy(() -> vendas.lerProduto(gerente, COD_REFRIGERANTE))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> vendas.finalizar(gerente, carrinho, new Recebimento(BigDecimal.ZERO)))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> vendas.vendasDaSessao(gerente)).isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void enumsDaVendaIguaisAosChecksDoBanco() throws Exception {
        assertThat(nomes(StatusVenda.values())).isEqualTo(valoresDoCheck("ck_venda_status"));
        assertThat(nomes(TipoFormaPagamento.values())).isEqualTo(valoresDoCheck("ck_forma_pagamento_tipo"));
    }

    // ----- Métrica 9.4 -----

    @Test
    void registrarUmItemComCincoMilProdutosLevaMenosDe300ms() throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("INSERT INTO produto (nome, unidade, preco_venda, custo, criado_por, atualizado_por) "
                    + "SELECT 'Produto ' || g, 'UN', 1.99, 1, " + admin.usuarioId() + ", " + admin.usuarioId()
                    + " FROM generate_series(1, 5000) g");
            st.executeUpdate("INSERT INTO produto_codigo_barras (produto_id, codigo, principal) "
                    + "SELECT id, lpad(id::text, 13, '0'), TRUE FROM produto WHERE nome LIKE 'Produto %'");
            st.executeUpdate("ANALYZE produto; ANALYZE produto_codigo_barras");
        }
        String codigo = linhas("SELECT codigo FROM produto_codigo_barras ORDER BY id DESC LIMIT 1").get(0).get(0)
                .toString();
        Carrinho carrinho = new Carrinho();
        ler(admin, carrinho, codigo); // aquece conexões e caches

        long maior = 0;
        for (int i = 0; i < 20; i++) {
            long inicio = System.nanoTime();
            ler(admin, carrinho, codigo);
            maior = Math.max(maior, System.nanoTime() - inicio);
        }
        long ms = Duration.ofNanos(maior).toMillis();
        System.out.printf("Métrica 9.4: registrar um item com 5 mil produtos — pior de 20 leituras: %d ms%n", ms);
        assertThat(ms).isLessThan(300);
    }

    private static Set<String> nomes(Enum<?>[] valores) {
        return Arrays.stream(valores).map(Enum::name).collect(Collectors.toSet());
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
