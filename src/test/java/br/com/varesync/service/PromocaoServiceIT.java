package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.FiltroPromocao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Promocao;
import br.com.varesync.model.TipoFormaPagamento;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Promoções (6.8): sobreposição recusada, avisos, filtros, permissão e o preço do PDV pela data da venda, com o
 * {@code promocao_id} gravado no item.
 */
class PromocaoServiceIT extends IntegracaoBase {

    private static final String COD_REFRIGERANTE = "7891000315507";

    private AcessoTeste t;
    private PromocaoService promocoes;
    private Sessao admin;
    private long refrigerante;
    private LocalDate hoje;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        promocoes = t.promocoes;
        admin = t.supervisor();
        refrigerante = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 2 L", null, "UN", v("5.49"),
                v("3.20"), null, null, List.of(new CodigoBarras(COD_REFRIGERANTE, true)), v("20")));
        hoje = promocoes.hoje();
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private DadosPromocao promocao(String preco, LocalDate inicio, LocalDate fim) {
        return new DadosPromocao(refrigerante, v(preco), inicio, fim, true);
    }

    @Test
    void promocaoSobrepostaERecusadaEEncostadaEAceita() {
        long primeira = promocoes.criar(admin, promocao("4.99", hoje, hoje.plusDays(6)));
        assertThatThrownBy(() -> promocoes.criar(admin, promocao("4.49", hoje.plusDays(6), hoje.plusDays(10))))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("Já existe uma promoção ativa de \"Refrigerante cola 2 L\"")
                .hasMessageContaining("(nº " + primeira + ", R$ 4,99)");
        assertThatThrownBy(() -> promocoes.criar(admin, promocao("4.49", hoje.minusDays(3), hoje.plusDays(1))))
                .hasMessageContaining("se sobrepõe");
        // Começa no dia seguinte ao fim da outra: sem sobreposição (fim é inclusive).
        long segunda = promocoes.criar(admin, promocao("4.49", hoje.plusDays(7), hoje.plusDays(10)));
        // Desativada não conta na sobreposição; reativar confere de novo.
        promocoes.alterarSituacao(admin, primeira, false);
        long terceira = promocoes.criar(admin, promocao("4.79", hoje, hoje.plusDays(3)));
        assertThatThrownBy(() -> promocoes.alterarSituacao(admin, primeira, true)).hasMessageContaining("nº " + terceira);
        // Alterar o período de uma promoção para cima de outra também é recusado.
        assertThatThrownBy(() -> promocoes.alterar(admin, segunda, promocao("4.49", hoje.plusDays(2),
                hoje.plusDays(10)))).hasMessageContaining("nº " + terceira);
    }

    @Test
    void validacoesEAvisos() {
        assertThatThrownBy(() -> promocoes.criar(admin, promocao("0", hoje, hoje)))
                .hasMessage("O preço promocional deve ser maior que zero.");
        assertThatThrownBy(() -> promocoes.criar(admin, promocao("4.999", hoje, hoje)))
                .hasMessageContaining("casas decimais");
        assertThatThrownBy(() -> promocoes.criar(admin, promocao("4.99", hoje, hoje.minusDays(1))))
                .hasMessage("A data de fim é antes da data de início.");
        assertThatThrownBy(() -> promocoes.criar(admin, promocao("4.99", hoje.minusDays(5), hoje.minusDays(1))))
                .hasMessageContaining("já passou");
        assertThatThrownBy(() -> promocoes.criar(admin, new DadosPromocao(null, v("4.99"), hoje, hoje, true)))
                .hasMessage("Escolha o produto da promoção.");

        assertThat(promocoes.avisos(admin, promocao("4.99", hoje, hoje))).isEmpty();
        assertThat(promocoes.avisos(admin, promocao("2.99", hoje, hoje))).singleElement().asString()
                .contains("abaixo do custo (R$ 3,2000)");
        assertThat(promocoes.avisos(admin, promocao("5.49", hoje, hoje))).singleElement().asString()
                .contains("não é menor que o preço de venda (R$ 5,49)");
    }

    @Test
    void filtrosVigentesFuturasEEncerradas() {
        long vigente = promocoes.criar(admin, promocao("4.99", hoje.minusDays(2), hoje));
        long futura = promocoes.criar(admin, promocao("4.79", hoje.plusDays(1), hoje.plusDays(5)));
        long desativada = promocoes.criar(admin, promocao("4.59", hoje.plusDays(10), hoje.plusDays(12)));
        promocoes.alterarSituacao(admin, desativada, false);

        assertThat(promocoes.listar(admin, FiltroPromocao.VIGENTES, null)).extracting(Promocao::id)
                .containsExactly(vigente);
        assertThat(promocoes.listar(admin, FiltroPromocao.FUTURAS, null)).extracting(Promocao::id)
                .containsExactly(futura);
        assertThat(promocoes.listar(admin, FiltroPromocao.ENCERRADAS, null)).extracting(Promocao::id)
                .containsExactly(desativada);
        assertThat(promocoes.listar(admin, FiltroPromocao.TODAS, "REFRIGERANTE")).hasSize(3);
        assertThat(promocoes.listar(admin, FiltroPromocao.TODAS, "cafe")).isEmpty();

        // No dia seguinte a vigente vira encerrada e a futura vira vigente.
        t.relogio.avancar(Duration.ofDays(1));
        assertThat(promocoes.listar(admin, FiltroPromocao.VIGENTES, null)).extracting(Promocao::id)
                .containsExactly(futura);
        assertThat(promocoes.listar(admin, FiltroPromocao.ENCERRADAS, null)).extracting(Promocao::id)
                .containsExactlyInAnyOrder(vigente, desativada);
        Promocao p = promocoes.listar(admin, FiltroPromocao.TODAS, null).stream().filter(x -> x.id() == vigente)
                .findFirst().orElseThrow();
        assertThat(p.situacao(promocoes.hoje())).isEqualTo("Encerrada");
    }

    @Test
    void pdvUsaAPromocaoDaDataDaVendaEGravaOPromocaoId() throws Exception {
        long promocao = promocoes.criar(admin, promocao("4.99", hoje, hoje.plusDays(1)));
        t.caixa.abrir(admin, v("0"));

        Carrinho carrinho = new Carrinho();
        var produto = t.vendas.lerProduto(admin, COD_REFRIGERANTE).produto();
        ItemCarrinho item = t.vendas.adicionarItem(admin, carrinho, produto, v("2")).item();
        assertThat(item.precoUnitario()).isEqualByComparingTo("4.99");
        assertThat(item.promocaoId()).isEqualTo(promocao);
        assertThat(item.valorBruto()).isEqualByComparingTo("9.98");
        assertThat(t.vendas.consultarPreco(admin, COD_REFRIGERANTE).preco().promocional()).isTrue();

        Recebimento r = new Recebimento(carrinho.total());
        r.adicionar(t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), v("10"));
        long vendaId = t.vendas.finalizar(admin, carrinho, r).vendaId();
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT preco_unitario, promocao_id, valor_bruto FROM venda_item "
                     + "WHERE venda_id = " + vendaId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("4.99");
            assertThat(rs.getLong(2)).isEqualTo(promocao);
            assertThat(rs.getBigDecimal(3)).isEqualByComparingTo("9.98");
        }
        // Já usada em venda: não exclui, só desativa.
        assertThatThrownBy(() -> promocoes.excluir(admin, promocao)).isInstanceOf(RegistroEmUsoException.class);

        // Depois do fim (inclusive), volta o preço de venda.
        t.relogio.avancar(Duration.ofDays(2));
        Carrinho depois = new Carrinho();
        ItemCarrinho normal = t.vendas.adicionarItem(admin, depois, produto, v("1")).item();
        assertThat(normal.precoUnitario()).isEqualByComparingTo("5.49");
        assertThat(normal.promocaoId()).isNull();
    }

    @Test
    void promocaoDesativadaOuFuturaNaoValeNoPdv() {
        long futura = promocoes.criar(admin, promocao("4.79", hoje.plusDays(1), hoje.plusDays(3)));
        long hojeDesativada = promocoes.criar(admin, promocao("3.99", hoje, hoje));
        promocoes.alterarSituacao(admin, hojeDesativada, false);
        var produto = t.vendas.lerProduto(admin, COD_REFRIGERANTE).produto();
        assertThat(t.vendas.adicionarItem(admin, new Carrinho(), produto, v("1")).item().precoUnitario())
                .isEqualByComparingTo("5.49");
        assertThat(futura).isPositive();
    }

    @Test
    void alteracoesAuditadasExclusaoEPermissao() throws Exception {
        long id = promocoes.criar(admin, promocao("4.99", hoje, hoje.plusDays(6)));
        promocoes.alterar(admin, id, promocao("4.89", hoje, hoje.plusDays(9)));
        promocoes.excluir(admin, id);
        assertThat(promocoes.listar(admin, FiltroPromocao.TODAS, null)).isEmpty();
        List<String> auditoria = t.auditoria(AcaoAuditoria.PROMOCAO_ALTERADA);
        assertThat(auditoria).hasSize(3);
        assertThat(auditoria.get(0)).startsWith("Promoção nº " + id + " criada: \"Refrigerante cola 2 L\"")
                .contains("a R$ 4,99");
        assertThat(auditoria.get(1)).contains("preço de R$ 4,99 para R$ 4,89").contains("período de");
        assertThat(auditoria.get(2)).contains("excluída");

        EnumSet<Permissao> semProdutos = EnumSet.of(Permissao.PDV);
        t.usuarios.criar(admin, "caixa1", "Caixa", false, semProdutos, "inicial1", "inicial1");
        Sessao caixa = t.acesso.entrar("caixa1", "inicial1");
        assertThatThrownBy(() -> promocoes.listar(caixa, FiltroPromocao.TODAS, null))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> promocoes.criar(caixa, promocao("4.99", hoje, hoje)))
                .isInstanceOf(PermissaoNegadaException.class);
    }
}
