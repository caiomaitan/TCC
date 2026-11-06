package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AlertasPainel;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.Lote;
import br.com.varesync.model.LoteEntrada;
import br.com.varesync.model.MovimentoLote;
import br.com.varesync.model.ParteLote;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.SaldoEstoque;
import br.com.varesync.model.TipoAjuste;
import br.com.varesync.report.FiltroValidade;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelValidade;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validade e lotes, parte B (F17.3), pelos services que as telas usam: o alerta do painel (7 dias ou menos, vencidos
 * com saldo e "SEM LOTE" negativo), o R29, o filtro "Só com SEM LOTE negativo", a aba Lotes (situação e razão), o
 * prazo do alerta em Configurações, o produto com o controle pela tela de cadastro e a entrada com a divisão
 * incompleta.
 */
class LoteTelasIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private LocalDate hoje;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        hoje = LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    private static BigDecimal q(String valor) {
        return new BigDecimal(valor);
    }

    private long produto(String nome, boolean controla, String custo) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, "UN", q("8.00"), q(custo), null, null, List.of(),
                null, controla, null));
    }

    private void entrar(long produto, String codigo, int dias, String quantidade) {
        t.estoque.ajustar(admin, produto, TipoAjuste.ENTRADA, q(quantidade), "Compra sem nota", null,
                ParteLote.novo(codigo, hoje.plusDays(dias), null));
    }

    private Lote lote(long produto, String codigo) {
        return t.estoque.lotes(admin, produto).stream()
                .filter(l -> codigo == null ? l.semLote() : l.codigo().equals(codigo)).findFirst().orElseThrow();
    }

    private AlertasPainel.Lotes alertas(Sessao sessao) {
        return t.painel.carregar(sessao).alertas().lotes();
    }

    /**
     * Aceite do alerta: aparece quando faltam 7 dias ou menos (o de 8 dias não entra); o vencido com saldo entra em
     * "N lotes vencidos"; o zerado, o "SEM LOTE", o produto sem controle e o inativo ficam de fora.
     */
    @Test
    void alertaDeSeteDiasEVencidosDoAceite() {
        long iogurte = produto("Iogurte natural 170 g", true, "1.9000");
        entrar(iogurte, "HOJE", 0, "2");
        entrar(iogurte, "SETE", 7, "3");
        entrar(iogurte, "OITO", 8, "4");
        entrar(iogurte, "VENCIDO", -1, "5");
        entrar(iogurte, "ZERADO", 3, "1");
        t.estoque.ajustar(admin, iogurte, TipoAjuste.SAIDA, q("1"), "Acerto de contagem", null,
                ParteLote.existente(lote(iogurte, "ZERADO").id(), null));
        t.estoque.ajustar(admin, iogurte, TipoAjuste.ENTRADA, q("6"), "Contagem", null, null); // "SEM LOTE"

        long inativo = produto("Requeijão 200 g", true, "5.9000");
        entrar(inativo, "R1", 2, "3");
        t.produtos.alterarSituacao(admin, inativo, false);
        long desligado = produto("Manteiga 200 g", true, "9.8000");
        entrar(desligado, "M1", 2, "3");
        t.produtos.definirControleValidade(admin, desligado, false);

        AlertasPainel.Lotes lotes = alertas(admin);
        assertThat(lotes.dias()).isEqualTo(Configuracoes.VALIDADE_ALERTA_PADRAO);
        assertThat(lotes.vencendo()).as("HOJE e SETE").isEqualTo(2);
        assertThat(lotes.vencidos()).isEqualTo(1);
        assertThat(lotes.semLoteNegativo()).isZero();
        assertThat(t.painel.carregar(admin).alertas().algum()).isTrue();

        // Quem não tem ESTOQUE não recebe o alerta.
        t.usuarios.criar(admin, "maria", "Maria Oliveira", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        assertThat(alertas(t.acesso.entrar("maria", "inicial1"))).isNull();
    }

    /** R29: vencem em até N dias e, se marcado, os vencidos; sem o "SEM LOTE"; valor a custo e dias para vencer. */
    @Test
    void r29ComVencendoEVencidos() {
        long iogurte = produto("Iogurte natural 170 g", true, "1.9000");
        entrar(iogurte, "A", 5, "10");
        entrar(iogurte, "B", 30, "10");
        entrar(iogurte, "V", -2, "3");
        t.estoque.ajustar(admin, iogurte, TipoAjuste.ENTRADA, q("4"), "Contagem", null, null);

        ResultadoRelatorio<RelValidade.Linha> padrao = t.relatorios.gerar(admin, new RelValidade(),
                Filtros.vazios());
        assertThat(padrao.filtros().validade().dias()).as("validade.alerta_dias").isEqualTo(7);
        assertThat(padrao.linhas()).extracting(RelValidade.Linha::lote).containsExactly("A");
        RelValidade.Linha a = padrao.linhas().get(0);
        assertThat(a.diasParaVencer()).isEqualTo(5);
        assertThat(a.valorACusto()).isEqualByComparingTo("19.00");

        ResultadoRelatorio<RelValidade.Linha> comVencidos = t.relatorios.gerar(admin, new RelValidade(),
                Filtros.vazios().comValidade(new FiltroValidade(30, true)));
        assertThat(comVencidos.linhas()).extracting(RelValidade.Linha::lote).containsExactly("V", "A", "B");
        assertThat(comVencidos.linhas().get(0).diasParaVencer()).isEqualTo(-2);
        assertThat(comVencidos.linhas().get(0).vencido()).isTrue();

        assertThatThrownBy(() -> t.relatorios.gerar(admin, new RelValidade(),
                Filtros.vazios().comValidade(new FiltroValidade(-1, false))))
                .hasMessageContaining("dias para vencer");
    }

    /** O prazo do alerta vem de Configurações (aba Estoque): com 3 dias, o lote de 5 sai do alerta e do R29. */
    @Test
    void prazoDoAlertaNasConfiguracoes() {
        long iogurte = produto("Iogurte natural 170 g", true, "1.9000");
        entrar(iogurte, "A", 5, "10");
        t.configuracoes.salvar(admin, t.configuracoes.carregar(admin).comValidadeAlertaDias(3));
        assertThat(t.configuracoes.carregar(admin).validadeAlertaDias()).isEqualTo(3);
        assertThat(alertas(admin).vencendo()).isZero();
        assertThat(alertas(admin).dias()).isEqualTo(3);
        assertThat(t.relatorios.gerar(admin, new RelValidade(), Filtros.vazios()).linhas()).isEmpty();
        assertThat(t.relatorios.validadeAlertaDias(admin)).isEqualTo(3);
        assertThatThrownBy(() -> t.configuracoes.salvar(admin,
                t.configuracoes.carregar(admin).comValidadeAlertaDias(400)))
                .hasMessageContaining("alerta de validade");
    }

    /** "SEM LOTE" negativo: entra no alerta e no filtro "Só com SEM LOTE negativo" da consulta de estoque. */
    @Test
    void semLoteNegativoNoAlertaENoFiltro() {
        long requeijao = produto("Requeijão 200 g", true, "5.9000");
        entrar(requeijao, "A", 10, "2");
        t.estoque.ajustar(admin, requeijao, TipoAjuste.SAIDA, q("5"), "Acerto de contagem", null, null);
        long outro = produto("Iogurte natural 170 g", true, "1.9000");
        entrar(outro, "B", 10, "2");

        assertThat(lote(requeijao, null).saldo()).isEqualByComparingTo("-3");
        assertThat(alertas(admin).semLoteNegativo()).isEqualTo(1);
        assertThat(t.estoque.pesquisar(admin, "", false, false, true)).extracting(SaldoEstoque::produtoId)
                .containsExactly(requeijao);
        assertThat(t.estoque.pesquisar(admin, "", false, false, false)).hasSize(2);
    }

    /** Aba Lotes: situação de cada lote e o razão do lote selecionado, com o documento de cada linha. */
    @Test
    void abaLotesComSituacaoERazao() {
        long iogurte = produto("Iogurte natural 170 g", true, "1.9000");
        long semControle = produto("Arroz 5 kg", false, "19.0000");
        assertThat(t.estoque.lotesDoProduto(admin, semControle).controla()).isFalse();

        entrar(iogurte, "A", 3, "10");
        entrar(iogurte, "B", 20, "10");
        entrar(iogurte, "V", -1, "2");
        t.estoque.transferirEntreLotes(admin, iogurte, lote(iogurte, "A").id(), ParteLote.novo("C", hoje.plusDays(9),
                q("4")), "Lote digitado errado");

        EstoqueService.LotesDoProduto lotes = t.estoque.lotesDoProduto(admin, iogurte);
        assertThat(lotes.controla()).isTrue();
        assertThat(lotes.alertaDias()).isEqualTo(7);
        assertThat(lotes.situacao(lote(iogurte, "A"))).isEqualTo("Vence em 3 dias");
        assertThat(lotes.vencendo(lote(iogurte, "A"))).isTrue();
        assertThat(lotes.situacao(lote(iogurte, "B"))).isEqualTo("OK");
        assertThat(lotes.situacao(lote(iogurte, "V"))).isEqualTo("Vencido");
        assertThat(lotes.critico(lote(iogurte, "V"))).isTrue();
        assertThat(lotes.diasParaVencer(lote(iogurte, "V"))).isEqualTo(-1);

        List<MovimentoLote> razaoA = t.estoque.razaoDoLote(admin, iogurte, lote(iogurte, "A").id());
        assertThat(razaoA).extracting(MovimentoLote::tipo).containsExactly("Ajuste de entrada", "Transferência");
        assertThat(razaoA.get(0).documento()).isEqualTo("Compra sem nota");
        assertThat(razaoA.get(1).documento()).startsWith("Para o lote C (validade ")
                .endsWith(": Lote digitado errado");
        assertThat(razaoA.get(1).quantidade()).isEqualByComparingTo("-4");
        assertThat(t.estoque.razaoDoLote(admin, iogurte, lote(iogurte, "C").id()).get(0).documento())
                .startsWith("Do lote A (validade ");
        assertThatThrownBy(() -> t.estoque.razaoDoLote(admin, semControle, lote(iogurte, "A").id()))
                .hasMessageContaining("não é deste produto");
    }

    /** Ligar o controle pelo cadastro (o "alterar" da tela): o saldo vai para o "SEM LOTE"; o produto mostra a caixa. */
    @Test
    void controlePeloCadastroDoProduto() {
        long p = t.produtos.criar(admin, new DadosProduto("Queijo minas 500 g", null, "UN", q("20.00"), q("12.00"),
                null, null, List.of(), q("10")));
        assertThat(t.produtos.buscar(admin, p).controlaValidade()).isFalse();
        t.produtos.alterar(admin, p, new DadosProduto("Queijo minas 500 g", null, "UN", q("20.00"), q("12.00"),
                null, null, List.of(), null, true, null));
        assertThat(t.produtos.buscar(admin, p).controlaValidade()).isTrue();
        assertThat(lote(p, null).saldo()).isEqualByComparingTo("10");

        // Produto novo com estoque inicial num lote.
        long novo = t.produtos.criar(admin, new DadosProduto("Iogurte grego 100 g", null, "UN", q("4.00"),
                q("2.00"), null, null, List.of(), q("12"), true, ParteLote.novo("ig1", hoje.plusDays(15), null)));
        assertThat(lote(novo, "IG1").saldo()).isEqualByComparingTo("12");
        assertThat(lote(novo, "IG1").validade()).isEqualTo(hoje.plusDays(15));
    }

    /**
     * Aceite da entrada pela tela: o item mostra a divisão incompleta (o que falta) até "Lotes…" completar a divisão;
     * a confirmação com a divisão incompleta diz qual produto falta; completa, cria os lotes.
     */
    @Test
    void entradaComDivisaoIncompletaDoAceite() {
        long manteiga = produto("Manteiga 200 g", true, "9.8000");
        long arroz = produto("Arroz 5 kg", false, "19.0000");
        long fornecedor = t.fornecedores.criar(admin, new DadosFornecedor("Laticínios Vale do Pardo Ltda", null,
                "AB12CD34EFGH83", null, null, null, null, null, null));
        long entrada = t.compras.criar(admin, new DadosEntrada(fornecedor, "901", hoje, null));
        long item = t.compras.adicionarItem(admin, entrada, manteiga, q("30"), q("9.5000"), null);
        t.compras.adicionarItem(admin, entrada, arroz, q("5"), q("19.0000"), null);

        ItemEntrada semDivisao = itemDe(entrada, manteiga);
        assertThat(semDivisao.controlaValidade()).isTrue();
        assertThat(semDivisao.divisaoIncompleta()).isTrue();
        assertThat(semDivisao.faltaDividir()).isEqualByComparingTo("30");
        assertThat(itemDe(entrada, arroz).divisaoIncompleta()).isFalse();

        t.compras.salvarLotesDoItem(admin, entrada, item, List.of(new LoteEntrada("M1", hoje.plusDays(40),
                q("18"))));
        assertThat(itemDe(entrada, manteiga).faltaDividir()).isEqualByComparingTo("12");
        assertThatThrownBy(() -> t.compras.confirmar(admin, entrada)).hasMessageContaining("Manteiga 200 g");

        t.compras.salvarLotesDoItem(admin, entrada, item, List.of(new LoteEntrada("M1", hoje.plusDays(40), q("18")),
                new LoteEntrada("M2", hoje.plusDays(50), q("12"))));
        assertThat(itemDe(entrada, manteiga).divisaoIncompleta()).isFalse();
        t.compras.confirmar(admin, entrada);
        assertThat(lote(manteiga, "M1").saldo()).isEqualByComparingTo("18");
        assertThat(lote(manteiga, "M2").saldo()).isEqualByComparingTo("12");
    }

    private ItemEntrada itemDe(long entrada, long produto) {
        return t.compras.itens(admin, entrada).stream().filter(i -> i.produtoId() == produto).findFirst()
                .orElseThrow();
    }
}
