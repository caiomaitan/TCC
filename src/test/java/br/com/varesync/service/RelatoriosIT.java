package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.Criterio;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.RelAlteracoesPreco;
import br.com.varesync.report.RelCancelamentos;
import br.com.varesync.report.RelCurvaAbc;
import br.com.varesync.report.RelEstoqueAtual;
import br.com.varesync.report.RelFormasPagamento;
import br.com.varesync.report.RelMaisVendidos;
import br.com.varesync.report.RelMovimentacao;
import br.com.varesync.report.RelPerdasAjustes;
import br.com.varesync.report.RelRentabilidadeCategoria;
import br.com.varesync.report.RelRentabilidadeProduto;
import br.com.varesync.report.RelSemGiro;
import br.com.varesync.report.RelVendasHoraDia;
import br.com.varesync.report.RelVendasOperador;
import br.com.varesync.report.RelVendasPeriodo;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Relatórios R01 a R05 (7.3) sobre vendas conhecidas: venda cancelada fora do faturamento, R01 e R05 batendo,
 * margem agregada, ranking, estoque atual e movimentação.
 *
 * <p>Cenário: dia 1 — venda A (3 × refrigerante a 5,49 + 0,345 kg de queijo a 39,90, desconto de 5% na venda),
 * venda B (2 × leite a 4,79, custo 4,89: prejuízo) e venda C (1 refrigerante) cancelada; dia 2 — venda D da Maria
 * (1 kg de queijo).
 */
class RelatoriosIT extends IntegracaoBase {

    private AcessoTeste t;
    private RelatorioService relatorios;
    private Sessao admin;
    private Sessao maria;
    private long refrigerante;
    private long queijo;
    private long leite;
    private long bebidas;
    private LocalDate dia1;
    private Periodo periodo;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        configurar("venda.permitir_estoque_negativo", "true");
        configurar("relatorio.margem_minima_pct", "15");
        relatorios = t.relatorios;
        admin = t.supervisor();
        bebidas = t.categorias.listar(admin, false).stream().filter(c -> c.nome().equals("Bebidas"))
                .map(Categoria::id).findFirst().orElseThrow();
        refrigerante = produto("Refrigerante cola 2 L", bebidas, "UN", "5.49", "3.20", "10", "50", "7891000315507");
        queijo = produto("Queijo muçarela", null, "KG", "39.90", "28.00", "2", "5", null);
        leite = produto("Leite UHT 1 L", bebidas, "UN", "4.79", "4.89", "0", "20", null);
        t.usuarios.criar(admin, "maria", "Maria Oliveira", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        maria = t.acesso.entrar("maria", "inicial1");

        dia1 = LocalDate.now(t.relogio);
        periodo = new Periodo(dia1, dia1.plusDays(1));
        t.caixa.abrir(admin, new BigDecimal("100.00"));

        Carrinho a = new Carrinho();
        incluir(admin, a, refrigerante, "3");
        incluir(admin, a, queijo, "0.345");
        t.vendas.aplicarDescontoVenda(admin, a, TipoDesconto.PERCENTUAL, BigDecimal.valueOf(5), null);
        assertThat(a.total()).isEqualByComparingTo("28.73");
        pagar(admin, a);

        Carrinho b = new Carrinho();
        incluir(admin, b, leite, "2");
        pagar(admin, b);

        Carrinho c = new Carrinho();
        incluir(admin, c, refrigerante, "1");
        long vendaC = pagar(admin, c);
        t.vendas.cancelar(admin, vendaC, "Cliente desistiu", null);

        t.relogio.avancar(Duration.ofDays(1));
        Carrinho d = new Carrinho();
        incluir(maria, d, queijo, "1");
        pagar(maria, d);
    }

    // ----- Apoio -----

    private long produto(String nome, Long categoria, String unidade, String preco, String custo, String minimo,
                         String inicial, String codigo) {
        List<CodigoBarras> codigos = codigo == null ? List.of() : List.of(new CodigoBarras(codigo, true));
        return t.produtos.criar(admin, new DadosProduto(nome, categoria, unidade, new BigDecimal(preco),
                new BigDecimal(custo), new BigDecimal(minimo), null, codigos, new BigDecimal(inicial)));
    }

    private void incluir(Sessao sessao, Carrinho carrinho, long produto, String quantidade) {
        t.vendas.adicionarItem(sessao, carrinho, t.vendas.produtoParaVenda(sessao, produto),
                new BigDecimal(quantidade));
    }

    private long pagar(Sessao sessao, Carrinho carrinho) {
        FormaPagamento dinheiro = t.vendas.formasAtivas(sessao).stream()
                .filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO).findFirst().orElseThrow();
        Recebimento r = new Recebimento(carrinho.total());
        r.adicionar(dinheiro, carrinho.total());
        return t.vendas.finalizar(sessao, carrinho, r).vendaId();
    }

    private void configurar(String chave, String valor) throws Exception {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE configuracao SET valor = ? WHERE chave = ?")) {
            ps.setString(1, valor);
            ps.setString(2, chave);
            assertThat(ps.executeUpdate()).as(chave).isEqualTo(1);
        }
    }

    private BigDecimal umValor(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    private Filtros noPeriodo() {
        return Filtros.vazios().comPeriodo(periodo);
    }

    // ----- Testes -----

    @Test
    void r01PorDiaDeixaAVendaCanceladaForaDoFaturamento() throws Exception {
        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = relatorios.gerar(admin, new RelVendasPeriodo(),
                noPeriodo());

        assertThat(r01.linhas()).hasSize(2);
        RelVendasPeriodo.Linha d1 = r01.linhas().get(0);
        assertThat(d1.dia()).isEqualTo(dia1);
        assertThat(d1.vendas()).isEqualTo(2);
        assertThat(d1.receita()).isEqualByComparingTo("38.31");
        assertThat(d1.descontos()).isEqualByComparingTo("1.51");
        assertThat(d1.cmv()).isEqualByComparingTo("29.04");
        assertThat(d1.lucro()).isEqualByComparingTo("9.27");
        assertThat(d1.margem()).isEqualByComparingTo("24.20");
        assertThat(d1.ticketMedio()).isEqualByComparingTo("19.16");
        assertThat(r01.totais()).containsExactly("Total (2 linhas)", "3", "R$ 78,21", "R$ 1,51", "R$ 57,04",
                "R$ 21,17", "27,07%", "R$ 26,07");

        BigDecimal finalizadas = umValor("SELECT sum(valor_total) FROM venda WHERE status = 'FINALIZADA'");
        BigDecimal todas = umValor("SELECT sum(valor_total) FROM venda");
        assertThat(finalizadas).isEqualByComparingTo("78.21");
        assertThat(todas).as("a venda cancelada existe, mas não fatura").isEqualByComparingTo("83.70");
    }

    @Test
    void r01FiltraPorOperadorEDetalhaPorVenda() {
        ResultadoRelatorio<RelVendasPeriodo.Linha> soMaria = relatorios.gerar(admin, new RelVendasPeriodo(),
                noPeriodo().comOperador(new Opcao(maria.usuarioId(), "Maria Oliveira")));
        assertThat(soMaria.linhas()).hasSize(1);
        assertThat(soMaria.linhas().get(0).dia()).isEqualTo(dia1.plusDays(1));
        assertThat(soMaria.linhas().get(0).receita()).isEqualByComparingTo("39.90");
        assertThat(soMaria.descricaoFiltros()).contains("Operador: Maria Oliveira");

        ResultadoRelatorio<RelVendasPeriodo.Linha> porVenda = relatorios.gerar(admin, new RelVendasPeriodo(),
                noPeriodo().comDetalhe(true));
        assertThat(porVenda.linhas()).hasSize(3);
        assertThat(porVenda.linhas()).extracting(RelVendasPeriodo.Linha::operador)
                .containsExactly("Ana Admin", "Ana Admin", "Maria Oliveira");
        assertThat(porVenda.linhas().get(0).receita()).isEqualByComparingTo("28.73");
    }

    @Test
    void r01ER05BatemNoMesmoPeriodoComMargemAgregada() {
        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = relatorios.gerar(admin, new RelVendasPeriodo(),
                noPeriodo());
        ResultadoRelatorio<RelRentabilidadeProduto.Linha> r05 = relatorios.gerar(admin,
                new RelRentabilidadeProduto(), noPeriodo());

        BigDecimal receita01 = r01.linhas().stream().map(RelVendasPeriodo.Linha::receita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lucro01 = r01.linhas().stream().map(RelVendasPeriodo.Linha::lucro)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal receita05 = r05.linhas().stream().map(RelRentabilidadeProduto.Linha::receita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lucro05 = r05.linhas().stream().map(RelRentabilidadeProduto.Linha::lucro)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receita05).isEqualByComparingTo(receita01).isEqualByComparingTo("78.21");
        assertThat(lucro05).isEqualByComparingTo(lucro01).isEqualByComparingTo("21.17");

        assertThat(r05.linhas()).extracting(RelRentabilidadeProduto.Linha::produto)
                .containsExactly("Queijo muçarela", "Refrigerante cola 2 L", "Leite UHT 1 L");
        RelRentabilidadeProduto.Linha refri = r05.linhas().get(1);
        assertThat(refri.quantidade()).as("a venda cancelada não conta").isEqualByComparingTo("3");
        assertThat(refri.receita()).isEqualByComparingTo("15.65");
        assertThat(refri.cmv()).isEqualByComparingTo("9.60");
        assertThat(refri.margem()).isEqualByComparingTo("38.66");
        assertThat(refri.markup()).isEqualByComparingTo("63.02");
        assertThat(r05.linhas()).extracting(RelRentabilidadeProduto.Linha::situacao)
                .containsExactly("OK", "OK", "Negativa");
        // Margem do período = 21,17 ÷ 78,21 (não a média das três margens)
        assertThat(r05.totais().get(7)).isEqualTo("27,07%");
        assertThat(r05.totais().get(7)).isEqualTo(r01.totais().get(6));
    }

    @Test
    void r02RanqueiaPeloCriterioComTopNEFiltroDeCategoria() {
        ResultadoRelatorio<RelMaisVendidos.Linha> porQuantidade = relatorios.gerar(admin, new RelMaisVendidos(),
                noPeriodo().comRanking(2, Criterio.QUANTIDADE));
        assertThat(porQuantidade.linhas()).extracting(RelMaisVendidos.Linha::produto)
                .containsExactly("Refrigerante cola 2 L", "Leite UHT 1 L");
        assertThat(porQuantidade.linhas().get(0).percentualReceita()).isEqualByComparingTo("20.01");

        ResultadoRelatorio<RelMaisVendidos.Linha> porLucro = relatorios.gerar(admin, new RelMaisVendidos(),
                noPeriodo().comRanking(null, Criterio.LUCRO));
        assertThat(porLucro.linhas()).extracting(RelMaisVendidos.Linha::posicao).containsExactly(1, 2, 3);
        assertThat(porLucro.linhas().get(0).produto()).isEqualTo("Queijo muçarela");

        long bebidas = t.categorias.listar(admin, false).stream().filter(c -> c.nome().equals("Bebidas"))
                .map(Categoria::id).findFirst().orElseThrow();
        ResultadoRelatorio<RelMaisVendidos.Linha> soBebidas = relatorios.gerar(admin, new RelMaisVendidos(),
                noPeriodo().comCategoria(new Opcao(bebidas, "Bebidas")).comRanking(null, Criterio.RECEITA));
        assertThat(soBebidas.linhas()).extracting(RelMaisVendidos.Linha::produto)
                .containsExactly("Refrigerante cola 2 L", "Leite UHT 1 L");
        // 15,65 ÷ (15,65 + 9,58): o % é sobre a receita do filtro
        assertThat(soBebidas.linhas().get(0).percentualReceita()).isEqualByComparingTo("62.03");
    }

    @Test
    void r03MostraSaldoEValorComFiltrosDeInativosEMinimo() {
        long parado = produto("Produto parado", null, "UN", "2.00", "1.50", "10", "3", null);
        long inativo = produto("Produto inativo", null, "UN", "2.00", "1.00", "0", "4", null);
        t.produtos.alterarSituacao(admin, inativo, false);

        ResultadoRelatorio<RelEstoqueAtual.Linha> ativos = relatorios.gerar(admin, new RelEstoqueAtual(),
                Filtros.vazios());
        assertThat(ativos.linhas()).extracting(RelEstoqueAtual.Linha::produtoId)
                .containsExactlyInAnyOrder(refrigerante, queijo, leite, parado);
        RelEstoqueAtual.Linha refri = ativos.linhas().stream().filter(l -> l.produtoId() == refrigerante)
                .findFirst().orElseThrow();
        assertThat(refri.saldo()).as("50 − 3 − 1 + 1 do cancelamento").isEqualByComparingTo("47");
        assertThat(refri.valorEmEstoque()).isEqualByComparingTo("150.40");
        RelEstoqueAtual.Linha q = ativos.linhas().stream().filter(l -> l.produtoId() == queijo).findFirst()
                .orElseThrow();
        assertThat(q.saldo()).isEqualByComparingTo("3.655");
        // 150,40 + 102,34 (3,655 × 28) + 88,02 (18 × 4,89) + 4,50
        assertThat(ativos.totais().get(7)).isEqualTo("R$ 345,26");

        assertThat(relatorios.gerar(admin, new RelEstoqueAtual(), Filtros.vazios().comEstoque(false, true))
                .linhas()).extracting(RelEstoqueAtual.Linha::produtoId).contains(inativo);
        assertThat(relatorios.gerar(admin, new RelEstoqueAtual(), Filtros.vazios().comEstoque(true, false))
                .linhas()).extracting(RelEstoqueAtual.Linha::produtoId).containsExactly(parado);
    }

    @Test
    void r04MostraOKardexDoProdutoComEntradasESaidasACusto() {
        ResultadoRelatorio<RelMovimentacao.Linha> r04 = relatorios.gerar(admin, new RelMovimentacao(),
                noPeriodo().comProduto(relatorios.produto(admin, "7891000315507")));

        assertThat(r04.linhas()).extracting(RelMovimentacao.Linha::tipo).containsExactly(
                TipoMovimento.AJUSTE_ENTRADA, TipoMovimento.VENDA, TipoMovimento.VENDA,
                TipoMovimento.CANCELAMENTO_VENDA);
        assertThat(r04.linhas()).extracting(RelMovimentacao.Linha::saldoApos).extracting(BigDecimal::intValue)
                .containsExactly(50, 47, 46, 47);
        assertThat(r04.linhas().get(1).origem()).startsWith("Venda nº ");
        assertThat(r04.linhas().get(0).origem()).isEqualTo("Estoque inicial");
        assertThat(r04.totais().get(3)).isEqualTo("47");
        assertThat(r04.totais().get(6)).isEqualTo("R$ 163,20");
        assertThat(r04.totais().get(7)).isEqualTo("R$ 12,80");

        ResultadoRelatorio<RelMovimentacao.Linha> soVendas = relatorios.gerar(admin, new RelMovimentacao(),
                noPeriodo().comTipo(TipoMovimento.VENDA));
        assertThat(soVendas.linhas()).hasSize(5);
        assertThat(soVendas.totais().get(3)).as("sem produto, quantidades não se somam").isEmpty();
    }

    @Test
    void produtoDoFiltroPeloCodigoInternoOuDeBarras() {
        assertThat(relatorios.produto(admin, String.valueOf(queijo)).nome()).isEqualTo("Queijo muçarela");
        assertThatThrownBy(() -> relatorios.produto(admin, "999999"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Produto não encontrado");
    }

    @Test
    void semPermissaoRelatoriosOServiceRecusa() {
        assertThatThrownBy(() -> relatorios.gerar(maria, new RelVendasPeriodo(), noPeriodo()))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> relatorios.opcoes(maria)).isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void periodoInvertidoOuIncompletoERecusado() {
        assertThatThrownBy(() -> relatorios.gerar(admin, new RelVendasPeriodo(),
                Filtros.vazios().comPeriodo(new Periodo(dia1.plusDays(1), dia1))))
                .isInstanceOf(NegocioException.class).hasMessageContaining("depois da final");
        assertThatThrownBy(() -> relatorios.gerar(admin, new RelVendasPeriodo(),
                Filtros.vazios().comPeriodo(new Periodo(null, dia1))))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Informe");
        assertThatThrownBy(() -> relatorios.gerar(admin, new RelMaisVendidos(),
                noPeriodo().comRanking(0, Criterio.RECEITA))).isInstanceOf(NegocioException.class);
    }

    @Test
    void opcoesECabecalho() {
        RelatorioService.OpcoesFiltro opcoes = relatorios.opcoes(admin);
        assertThat(opcoes.operadores()).extracting(Opcao::nome).contains("Ana Admin", "Maria Oliveira");
        assertThat(opcoes.categorias()).extracting(Opcao::nome).contains("Bebidas");
        ResultadoRelatorio<RelVendasPeriodo.Linha> r = relatorios.gerar(admin, new RelVendasPeriodo(), noPeriodo());
        assertThat(r.loja()).isEqualTo("Mercado Bom Preço");
        assertThat(r.emitidoPor()).isEqualTo("Ana Admin");
    }

    // ----- Fase 6, parte 2: R06 a R10, R12, R14 a R16 -----

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    private BigDecimal receitaDoR01() {
        return Coluna.soma(relatorios.gerar(admin, new RelVendasPeriodo(), noPeriodo()).linhas(),
                RelVendasPeriodo.Linha::receita);
    }

    @Test
    void r06CategoriasSomamOLucroDoR05ESemCategoriaApareceSeparada() {
        ResultadoRelatorio<RelRentabilidadeCategoria.Linha> r06 = relatorios.gerar(admin,
                new RelRentabilidadeCategoria(), noPeriodo());

        assertThat(r06.linhas()).extracting(RelRentabilidadeCategoria.Linha::categoria)
                .containsExactlyInAnyOrder("Bebidas", "Sem categoria");
        assertThat(Coluna.soma(r06.linhas(), RelRentabilidadeCategoria.Linha::lucro)).isEqualByComparingTo("21.17");
        assertThat(r06.totais()).containsExactly("Total (2 linhas)", "R$ 78,21", "R$ 57,04", "R$ 21,17", "27,07%",
                "100,00%");
    }

    @Test
    void r07CurvaAbcPorReceitaEPorLucro() {
        ResultadoRelatorio<RelCurvaAbc.Linha> receita = relatorios.gerar(admin, new RelCurvaAbc(),
                noPeriodo().comCriterio(Criterio.RECEITA));
        assertThat(receita.linhas()).extracting(RelCurvaAbc.Linha::produto)
                .containsExactly("Queijo muçarela", "Refrigerante cola 2 L", "Leite UHT 1 L");
        assertThat(receita.linhas().get(0).classe()).isEqualTo("A");
        assertThat(receita.totais().get(3)).isEqualTo("R$ 78,21");

        // Por lucro, o leite (vendido abaixo do custo) fica no fim, na classe C, sem % acumulado.
        ResultadoRelatorio<RelCurvaAbc.Linha> lucro = relatorios.gerar(admin, new RelCurvaAbc(),
                noPeriodo().comCriterio(Criterio.LUCRO));
        RelCurvaAbc.Linha leiteAbc = lucro.linhas().get(2);
        assertThat(leiteAbc.produto()).isEqualTo("Leite UHT 1 L");
        assertThat(leiteAbc.valor()).isEqualByComparingTo("-0.20");
        assertThat(leiteAbc.classe()).isEqualTo("C");
        assertThat(leiteAbc.percentualAcumulado()).isNull();

        assertThatThrownBy(() -> relatorios.gerar(admin, new RelCurvaAbc(),
                noPeriodo().comCriterio(Criterio.QUANTIDADE))).isInstanceOf(NegocioException.class);
    }

    @Test
    void r08SomaDasFormasEAReceitaDoR01EUmaVendaEmDuasFormasContaUmaVez() {
        Carrinho e = new Carrinho();
        incluir(maria, e, leite, "1");
        Recebimento r = new Recebimento(e.total());
        r.adicionar(forma(TipoFormaPagamento.PIX), new BigDecimal("2.00"));
        r.adicionar(forma(TipoFormaPagamento.DINHEIRO), new BigDecimal("2.79"));
        t.vendas.finalizar(maria, e, r);

        ResultadoRelatorio<RelFormasPagamento.Linha> r08 = relatorios.gerar(admin, new RelFormasPagamento(),
                noPeriodo());

        assertThat(r08.linhas()).extracting(RelFormasPagamento.Linha::forma).containsExactly("Dinheiro", "PIX");
        assertThat(r08.linhas().get(0).vendas()).isEqualTo(4);
        assertThat(r08.linhas().get(0).valor()).isEqualByComparingTo("81.00");
        assertThat(r08.linhas().get(1).vendas()).isEqualTo(1);
        assertThat(Coluna.soma(r08.linhas(), RelFormasPagamento.Linha::valor)).isEqualByComparingTo(receitaDoR01());
        assertThat(r08.totais()).containsExactly("Total (2 linhas)", "4", "R$ 83,00", "100,00%");
    }

    @Test
    void r09PorOperadorComCancelamentos() {
        ResultadoRelatorio<RelVendasOperador.Linha> r09 = relatorios.gerar(admin, new RelVendasOperador(),
                noPeriodo());
        assertThat(r09.linhas()).extracting(RelVendasOperador.Linha::operador)
                .containsExactly("Maria Oliveira", "Ana Admin");
        RelVendasOperador.Linha ana = r09.linhas().get(1);
        assertThat(ana.vendas()).isEqualTo(2);
        assertThat(ana.receita()).isEqualByComparingTo("38.31");
        assertThat(ana.descontos()).isEqualByComparingTo("1.51");
        assertThat(ana.vendasCanceladas()).isEqualTo(1);
        assertThat(r09.totais()).containsExactly("Total (2 linhas)", "3", "R$ 78,21", "R$ 26,07", "R$ 1,51", "1",
                "0");
    }

    @Test
    void r10GradeSomaAReceitaEAsVendasDoPeriodo() {
        ResultadoRelatorio<RelVendasHoraDia.Linha> receita = relatorios.gerar(admin, new RelVendasHoraDia(),
                noPeriodo().comCriterio(Criterio.RECEITA));
        assertThat(receita.totais().get(8)).isEqualTo("R$ 78,21");
        ResultadoRelatorio<RelVendasHoraDia.Linha> vendas = relatorios.gerar(admin, new RelVendasHoraDia(),
                noPeriodo().comCriterio(Criterio.VENDAS));
        assertThat(vendas.totais().get(8)).isEqualTo("3");
        assertThat(vendas.descricaoFiltros()).contains("Critério: nº de vendas");
    }

    @Test
    void r12SemGiroPelosDiasSemVendaComSaldoPositivo() {
        long fermento = produto("Fermento químico 100 g", null, "UN", "4.50", "1.80", "0", "5", null);
        produto("Produto sem estoque", null, "UN", "2.00", "1.00", "0", "0", null);
        long inativo = produto("Produto inativo", null, "UN", "2.00", "1.00", "0", "3", null);
        t.produtos.alterarSituacao(admin, inativo, false);

        // Hoje é o dia 2: sem venda desde ontem = 1 dia (o queijo vendeu hoje).
        ResultadoRelatorio<RelSemGiro.Linha> umDia = relatorios.gerar(admin, new RelSemGiro(),
                Filtros.vazios().comDias(1));
        assertThat(umDia.linhas()).extracting(RelSemGiro.Linha::produto)
                .containsExactly("Refrigerante cola 2 L", "Leite UHT 1 L", "Fermento químico 100 g");
        assertThat(umDia.linhas().get(0).diasSemVenda()).isEqualTo(1L);
        assertThat(umDia.linhas().get(0).valorParado()).isEqualByComparingTo("150.40"); // 47 × 3,20
        assertThat(umDia.linhas().get(2).ultimaVenda()).isNull();

        // Sem dias informados vale relatorio.dias_sem_giro (30): só o que nunca vendeu.
        ResultadoRelatorio<RelSemGiro.Linha> padrao = relatorios.gerar(admin, new RelSemGiro(), Filtros.vazios());
        assertThat(padrao.filtros().dias()).isEqualTo(30);
        assertThat(padrao.linhas()).extracting(RelSemGiro.Linha::produtoId).containsExactly(fermento);
        assertThat(padrao.descricaoFiltros()).contains("Sem venda há 30 dias ou mais");

        assertThatThrownBy(() -> relatorios.gerar(admin, new RelSemGiro(), Filtros.vazios().comDias(0)))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void r14PerdasEAjustesPorMotivoComValorACusto() {
        t.estoque.ajustar(admin, refrigerante, TipoAjuste.PERDA, new BigDecimal("2"), "Vencimento", "lote 12");
        t.estoque.ajustar(admin, refrigerante, TipoAjuste.PERDA, BigDecimal.ONE, "Vencimento", null);

        ResultadoRelatorio<RelPerdasAjustes.Linha> perdas = relatorios.gerar(admin, new RelPerdasAjustes(),
                noPeriodo().comTipo(TipoMovimento.PERDA));
        assertThat(perdas.linhas()).hasSize(1);
        RelPerdasAjustes.Linha perda = perdas.linhas().get(0);
        assertThat(perda.motivo()).isEqualTo("Vencimento");
        assertThat(perda.movimentos()).isEqualTo(2);
        assertThat(perda.quantidade()).isEqualByComparingTo("-3");
        assertThat(perda.valor()).isEqualByComparingTo("-9.60"); // 3 × 3,20

        ResultadoRelatorio<RelPerdasAjustes.Linha> todos = relatorios.gerar(admin, new RelPerdasAjustes(),
                noPeriodo());
        assertThat(todos.linhas()).anySatisfy(l -> {
            assertThat(l.tipo()).isEqualTo(TipoMovimento.AJUSTE_ENTRADA);
            assertThat(l.motivo()).isEqualTo("Estoque inicial");
        });
        assertThat(todos.linhas()).noneMatch(l -> l.tipo() == TipoMovimento.VENDA);
        assertThatThrownBy(() -> relatorios.gerar(admin, new RelPerdasAjustes(),
                noPeriodo().comTipo(TipoMovimento.VENDA))).isInstanceOf(NegocioException.class);
    }

    @Test
    void r15SoAlteracoesDePrecoComOCodigoDeBarras() {
        t.produtos.alterar(admin, refrigerante, new DadosProduto("Refrigerante cola 2 L", bebidas, "UN",
                new BigDecimal("5.99"), new BigDecimal("3.20"), BigDecimal.TEN, null,
                List.of(new CodigoBarras("7891000315507", true)), null));
        t.produtos.alterar(admin, leite, new DadosProduto("Leite UHT 1 L", bebidas, "UN", new BigDecimal("4.79"),
                new BigDecimal("4.50"), BigDecimal.ZERO, null, List.of(), null)); // só o custo

        ResultadoRelatorio<RelAlteracoesPreco.Linha> r15 = relatorios.gerar(admin, new RelAlteracoesPreco(),
                noPeriodo());

        assertThat(r15.linhas()).hasSize(1);
        RelAlteracoesPreco.Linha l = r15.linhas().get(0);
        assertThat(l.produto()).isEqualTo("Refrigerante cola 2 L");
        assertThat(l.codigoBarras()).isEqualTo("7891000315507");
        assertThat(l.precoAnterior()).isEqualByComparingTo("5.49");
        assertThat(l.precoNovo()).isEqualByComparingTo("5.99");
        assertThat(l.variacao()).isEqualByComparingTo("9.11");
        assertThat(l.usuario()).isEqualTo("Ana Admin");
    }

    @Test
    void r16CancelamentosComQuemCancelouEQuemLiberou() throws Exception {
        configurar("venda.cancelar_item_exige_supervisor", "true");
        try {
            Carrinho f = new Carrinho();
            incluir(maria, f, refrigerante, "1");
            incluir(maria, f, leite, "2");
            t.vendas.cancelarItem(maria, f, 2, t.acesso.liberar("admin", "segredo1", Permissao.CANCELAR_VENDA));
            long vendaF = pagar(maria, f);
            t.vendas.cancelar(maria, vendaF, "Cliente sem dinheiro",
                    t.acesso.liberar("admin", "segredo1", Permissao.CANCELAR_VENDA));

            ResultadoRelatorio<RelCancelamentos.Linha> r16 = relatorios.gerar(admin, new RelCancelamentos(),
                    noPeriodo());

            assertThat(r16.linhas()).extracting(RelCancelamentos.Linha::tipo)
                    .containsExactly("Venda", "Item", "Venda");
            RelCancelamentos.Linha vendaC = r16.linhas().get(0);
            assertThat(vendaC.motivo()).isEqualTo("Cliente desistiu");
            assertThat(vendaC.canceladoPor()).isEqualTo("Ana Admin");
            assertThat(vendaC.liberadoPor()).isEmpty();
            RelCancelamentos.Linha item = r16.linhas().get(1);
            assertThat(item.vendaId()).isEqualTo(vendaF);
            assertThat(item.descricao()).isEqualTo("Item 2: 2 UN x Leite UHT 1 L");
            assertThat(item.valor()).isEqualByComparingTo("9.58");
            assertThat(item.canceladoPor()).isEqualTo("Maria Oliveira");
            assertThat(item.liberadoPor()).isEqualTo("Ana Admin (admin)");
            RelCancelamentos.Linha vendaFCancelada = r16.linhas().get(2);
            assertThat(vendaFCancelada.descricao()).isEqualTo("Venda inteira (1 item)");
            assertThat(vendaFCancelada.valor()).isEqualByComparingTo("5.49");
            assertThat(vendaFCancelada.canceladoPor()).isEqualTo("Maria Oliveira");
            assertThat(vendaFCancelada.liberadoPor()).isEqualTo("Ana Admin (admin)");

            ResultadoRelatorio<RelCancelamentos.Linha> soMaria = relatorios.gerar(admin, new RelCancelamentos(),
                    noPeriodo().comOperador(new Opcao(maria.usuarioId(), "Maria Oliveira")));
            assertThat(soMaria.linhas()).hasSize(2);
        } finally {
            configurar("venda.cancelar_item_exige_supervisor", "false");
        }
    }
}
