package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.IndicadoresDia;
import br.com.varesync.model.Painel;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoPainel;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Coluna;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelVendasPeriodo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Painel (7.5) sobre vendas conhecidas.
 *
 * <p>Terça, 06/10, 10h (horário do sistema): venda X1 (1 refrigerante, R$ 5,49); 13h: venda X2 (2 refrigerantes,
 * R$ 10,98). Terça seguinte, 13/10, 10h: H1 (2 refrigerantes), H2 (1 leite abaixo do custo, R$ 4,79) e H3
 * (cancelada). O painel é lido às 11h de 13/10: hoje = H1 + H2; a semana anterior até a mesma hora = só X1.
 */
class PainelServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private Sessao maria;
    private long refrigerante;
    private long leite;
    private LocalDate semanaPassada;
    private LocalDate hoje;
    private long caixaId;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        configurar("venda.permitir_estoque_negativo", "true");
        admin = t.supervisor();
        refrigerante = produto("Refrigerante cola 2 L", "5.49", "3.20", "10", "50");
        leite = produto("Leite UHT 1 L", "4.79", "4.89", "0", "20");
        produto("Arroz 5 kg", "25.90", "19.00", "5", "2"); // abaixo do mínimo
        t.usuarios.criar(admin, "maria", "Maria Oliveira", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        maria = t.acesso.entrar("maria", "inicial1");

        semanaPassada = LocalDate.now(t.relogio);
        caixaId = t.caixa.abrir(admin, new BigDecimal("100.00")).id();
        vender(refrigerante, "1");
        t.relogio.avancar(Duration.ofHours(3));
        vender(refrigerante, "2"); // depois da hora de comparação: fica fora da semana anterior

        t.relogio.avancar(Duration.ofDays(7).minusHours(3));
        hoje = LocalDate.now(t.relogio);
        vender(refrigerante, "2");
        vender(leite, "1");
        long cancelada = vender(refrigerante, "1");
        t.vendas.cancelar(admin, cancelada, "Teste", null);
        t.inventarios.criar(admin, "Contagem geral");
        t.relogio.avancar(Duration.ofHours(1));
    }

    private long produto(String nome, String preco, String custo, String minimo, String inicial) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, "UN", new BigDecimal(preco),
                new BigDecimal(custo), new BigDecimal(minimo), null, List.of(), new BigDecimal(inicial)));
    }

    private long vender(long produto, String quantidade) {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.produtoParaVenda(admin, produto),
                new BigDecimal(quantidade));
        FormaPagamento dinheiro = t.vendas.formasAtivas(admin).stream()
                .filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO).findFirst().orElseThrow();
        Recebimento r = new Recebimento(carrinho.total());
        r.adicionar(dinheiro, carrinho.total());
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private void configurar(String chave, String valor) throws Exception {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE configuracao SET valor = ? WHERE chave = ?")) {
            ps.setString(1, valor);
            ps.setString(2, chave);
            ps.executeUpdate();
        }
    }

    @Test
    void cartoesDeHojeComparadosAoMesmoDiaDaSemanaAnteriorAteAMesmaHora() {
        Painel painel = t.painel.carregar(admin);

        assertThat(painel.comIndicadores()).isTrue();
        IndicadoresDia dia = painel.hoje();
        assertThat(dia.vendas()).as("a venda cancelada fica de fora").isEqualTo(2);
        assertThat(dia.receita()).isEqualByComparingTo("15.77");
        assertThat(dia.lucro()).isEqualByComparingTo("4.48"); // 2 × (5,49 − 3,20) + (4,79 − 4,89)
        assertThat(dia.ticketMedio()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("7.89"));
        assertThat(dia.margem()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("28.41"));

        assertThat(painel.diaComparado()).isEqualTo(semanaPassada);
        IndicadoresDia antes = painel.semanaPassada();
        assertThat(antes.vendas()).as("só a venda das 10h; a das 13h ainda não tinha acontecido").isEqualTo(1);
        assertThat(antes.receita()).isEqualByComparingTo("5.49");
        assertThat(IndicadoresDia.variacao(dia.receita(), antes.receita()))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("187.25"));
    }

    @Test
    void cartoesBatemComOR01DeHoje() {
        Painel painel = t.painel.carregar(admin);
        BigDecimal receitaR01 = Coluna.soma(t.relatorios.gerar(admin, new RelVendasPeriodo(),
                Filtros.vazios().comPeriodo(new Periodo(hoje, hoje))).linhas(), RelVendasPeriodo.Linha::receita);
        assertThat(painel.hoje().receita()).isEqualByComparingTo(receitaR01);
    }

    @Test
    void graficoDeTrintaDiasETopDoMesPorLucro() {
        Painel painel = t.painel.carregar(admin);

        assertThat(painel.ultimos30Dias()).hasSize(30);
        assertThat(painel.ultimos30Dias().get(29).dia()).isEqualTo(hoje);
        assertThat(painel.ultimos30Dias().get(29).receita()).isEqualByComparingTo("15.77");
        assertThat(painel.ultimos30Dias().get(22).dia()).isEqualTo(semanaPassada);
        assertThat(painel.ultimos30Dias().get(22).receita()).as("o dia inteiro, X1 + X2")
                .isEqualByComparingTo("16.47");
        assertThat(painel.ultimos30Dias().get(10).receita()).isEqualByComparingTo("0");

        assertThat(painel.topMes()).extracting(ProdutoPainel::nome)
                .containsExactly("Refrigerante cola 2 L", "Leite UHT 1 L");
        assertThat(painel.topMes().get(0).lucro()).isEqualByComparingTo("11.45"); // 5 × 2,29
    }

    @Test
    void alertasParaQuemPodeAbrirCadaTela() {
        Painel painel = t.painel.carregar(admin);

        assertThat(painel.alertas().abaixoDoMinimo()).isEqualTo(1);
        assertThat(painel.alertas().precoAbaixoDoCusto()).isEqualTo(1);
        assertThat(painel.alertas().caixaAberto()).hasValueSatisfying(c -> assertThat(c.id()).isEqualTo(caixaId));
        assertThat(painel.alertas().inventariosAbertos()).isEqualTo(1);
        assertThat(painel.alertas().algum()).isTrue();
    }

    @Test
    void semRelatoriosSoAlertasOperacionais() {
        Painel painel = t.painel.carregar(maria);

        assertThat(painel.comIndicadores()).isFalse();
        assertThat(painel.hoje()).isEqualTo(IndicadoresDia.VAZIO);
        assertThat(painel.ultimos30Dias()).isEmpty();
        assertThat(painel.topMes()).isEmpty();
        assertThat(painel.alertas().abaixoDoMinimo()).as("sem ESTOQUE").isNull();
        assertThat(painel.alertas().precoAbaixoDoCusto()).as("sem PRODUTOS").isNull();
        assertThat(painel.alertas().inventariosAbertos()).as("sem INVENTARIO").isNull();
        assertThat(painel.alertas().caixaAberto()).as("tem PDV").isPresent();
    }

    @Test
    void semCaixaAbertoNaoHaAlertaDeCaixa() {
        t.caixa.fechar(admin, new BigDecimal("100.00"));
        assertThat(t.painel.carregar(admin).alertas().caixaAberto()).isEmpty();
    }
}
