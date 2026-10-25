package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.DadosCliente;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelClientes;
import br.com.varesync.report.RelVendasPeriodo;
import br.com.varesync.report.ResultadoRelatorio;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Identificação do cliente na venda (F12.2, F11 do PDV): a venda grava o cliente, que pode ser trocado ou removido
 * até a finalização; o comprovante mostra o nome e o CPF mascarado; o R18 soma as compras do período.
 */
class ClienteVendaIT extends IntegracaoBase {

    private AcessoTeste t;
    private VendaService vendas;
    private Sessao admin;
    private long refrigerante;
    private long maria;
    private long jose;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        vendas = t.vendas;
        admin = t.supervisor();
        refrigerante = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 2 L", null, "UN",
                new BigDecimal("5.00"), new BigDecimal("3.00"), null, null,
                List.of(new CodigoBarras("7891000315507", true)), new BigDecimal("100")));
        maria = t.clientes.criar(admin, DadosCliente.rapido("Maria da Silva", "52998224725"));
        jose = t.clientes.criar(admin, DadosCliente.rapido("José Antônio", null));
        t.caixa.abrir(admin, BigDecimal.ZERO);
    }

    private Carrinho carrinho(Sessao sessao, String quantidade) {
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(sessao, carrinho, vendas.produtoParaVenda(sessao, refrigerante),
                new BigDecimal(quantidade));
        return carrinho;
    }

    private VendaService.VendaFinalizada finalizar(Sessao sessao, Carrinho carrinho) {
        Recebimento r = new Recebimento(carrinho.total());
        r.adicionar(vendas.formasAtivas(sessao).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), carrinho.total());
        return vendas.finalizar(sessao, carrinho, r);
    }

    private Long clienteDaVenda(long vendaId) throws Exception {
        long semCliente = t.contar("SELECT count(*) FROM venda WHERE cliente_id IS NULL AND id = " + vendaId);
        return semCliente == 1 ? null
                : t.contar("SELECT cliente_id FROM venda WHERE id = " + vendaId);
    }

    @Test
    void vendaIdentificadaGravaOClienteEOComprovanteMostraNomeECpfMascarado() throws Exception {
        Carrinho carrinho = carrinho(admin, "2");
        ClienteVenda identificado = vendas.identificarCliente(admin, carrinho, maria);
        assertThat(identificado.descricao()).isEqualTo("Maria da Silva (***.982.247-**)");
        assertThat(carrinho.cliente()).contains(identificado);

        VendaService.VendaFinalizada venda = finalizar(admin, carrinho);
        assertThat(clienteDaVenda(venda.vendaId())).isEqualTo(maria);
        Comprovante comprovante = venda.comprovante();
        assertThat(comprovante.cliente()).isEqualTo(new Comprovante.Cliente("Maria da Silva", "***.982.247-**"));
        assertThat(comprovante.texto()).contains("Cliente: Maria da Silva", "CPF: ***.982.247-**")
                .doesNotContain("529.982.247-25", "52998224725");
        assertThat(vendas.comprovante(admin, venda.vendaId()).texto()).as("reimpressão (F9)")
                .contains("CPF: ***.982.247-**");
        assertThat(t.contar("SELECT count(*) FROM vw_venda_item_rentabilidade WHERE cliente_id = " + maria))
                .as("a view de rentabilidade traz o cliente").isEqualTo(1);
    }

    @Test
    void clientePodeSerTrocadoOuRemovidoAteAFinalizacao() throws Exception {
        Carrinho trocado = carrinho(admin, "1");
        vendas.identificarCliente(admin, trocado, maria);
        vendas.identificarCliente(admin, trocado, jose);
        long vendaJose = finalizar(admin, trocado).vendaId();
        assertThat(clienteDaVenda(vendaJose)).isEqualTo(jose);
        assertThat(vendas.comprovante(admin, vendaJose).texto()).contains("Cliente: José Antônio")
                .doesNotContainPattern("(?m)^CPF:");

        Carrinho removido = carrinho(admin, "1");
        vendas.identificarCliente(admin, removido, maria);
        vendas.removerCliente(admin, removido);
        assertThat(removido.cliente()).isEmpty();
        long semCliente = finalizar(admin, removido).vendaId();
        assertThat(clienteDaVenda(semCliente)).isNull();
        assertThat(vendas.comprovante(admin, semCliente).texto()).doesNotContain("Cliente:");
    }

    @Test
    void clienteInativoNaoEIdentificadoENemFinalizaSeForInativadoNoMeio() throws Exception {
        t.clientes.alterarSituacao(admin, jose, false);
        Carrinho carrinho = carrinho(admin, "1");
        assertThatThrownBy(() -> vendas.identificarCliente(admin, carrinho, jose))
                .hasMessageContaining("está inativo e não pode ser identificado");
        assertThat(carrinho.cliente()).isEmpty();

        vendas.identificarCliente(admin, carrinho, maria);
        t.clientes.alterarSituacao(admin, maria, false); // em outro computador, durante a venda
        assertThatThrownBy(() -> finalizar(admin, carrinho)).hasMessageContaining("Maria da Silva")
                .hasMessageContaining("Remova ou troque o cliente (F11)");
        assertThat(t.contar("SELECT count(*) FROM venda")).as("nada gravado").isZero();
        assertThat(carrinho.cliente()).as("carrinho preservado").isPresent();

        vendas.removerCliente(admin, carrinho);
        assertThat(clienteDaVenda(finalizar(admin, carrinho).vendaId())).isNull();
    }

    @Test
    void operadorDoPdvIdentificaSemAPermissaoClientes() throws Exception {
        t.usuarios.criar(admin, "caixa", "Operador caixa", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao caixa = t.acesso.entrar("caixa", "inicial1");
        Carrinho carrinho = carrinho(caixa, "1");
        ClienteVenda achado = t.clientes.pesquisarParaVenda(caixa, "529.982.247-25").get(0);
        vendas.identificarCliente(caixa, carrinho, achado.id());
        assertThat(clienteDaVenda(finalizar(caixa, carrinho).vendaId())).isEqualTo(maria);

        t.usuarios.criar(admin, "estoque", "Estoquista", false, EnumSet.of(Permissao.ESTOQUE), "inicial1", "inicial1");
        Sessao estoquista = t.acesso.entrar("estoque", "inicial1");
        assertThatThrownBy(() -> vendas.identificarCliente(estoquista, new Carrinho(), maria))
                .isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void r18SomaAsComprasDoPeriodoPorClienteEBateComOR01() {
        // Maria: 2 compras (10,00 e 5,00); José: 1 compra (15,00); uma venda sem cliente e uma de Maria cancelada.
        Carrinho c1 = carrinho(admin, "2");
        vendas.identificarCliente(admin, c1, maria);
        finalizar(admin, c1);
        t.relogio.avancar(Duration.ofHours(1));
        Carrinho c2 = carrinho(admin, "3");
        vendas.identificarCliente(admin, c2, jose);
        finalizar(admin, c2);
        t.relogio.avancar(Duration.ofHours(1));
        Carrinho c3 = carrinho(admin, "1");
        vendas.identificarCliente(admin, c3, maria);
        long ultimaMaria = finalizar(admin, c3).vendaId();
        Carrinho c4 = carrinho(admin, "4");
        finalizar(admin, c4);
        Carrinho c5 = carrinho(admin, "6");
        vendas.identificarCliente(admin, c5, maria);
        long cancelada = finalizar(admin, c5).vendaId();
        vendas.cancelar(admin, cancelada, "Cliente desistiu", null);

        LocalDate hoje = LocalDate.now(t.relogio);
        Filtros filtros = Filtros.vazios().comPeriodo(new Periodo(hoje, hoje));
        ResultadoRelatorio<RelClientes.Linha> r18 = t.relatorios.gerar(admin, new RelClientes(), filtros);
        assertThat(r18.linhas()).extracting(RelClientes.Linha::cliente).containsExactly("José Antônio",
                "Maria da Silva");
        RelClientes.Linha linhaMaria = r18.linhas().get(1);
        assertThat(linhaMaria.compras()).isEqualTo(2);
        assertThat(linhaMaria.valor()).isEqualByComparingTo("15.00");
        assertThat(linhaMaria.ticketMedio()).isEqualByComparingTo("7.50");
        assertThat(linhaMaria.documentoOculto()).isEqualTo("***.982.247-**");
        assertThat(linhaMaria.ultimaCompra()).isEqualTo(vendas.comprovante(admin, ultimaMaria).dataHora());
        assertThat(r18.totais()).containsExactly("Total (2 linhas)", "", "", "3", "R$ 30,00", "R$ 10,00", "");

        // As compras identificadas mais a venda sem cliente dão a receita do R01 no mesmo período.
        ResultadoRelatorio<RelVendasPeriodo.Linha> r01 = t.relatorios.gerar(admin, new RelVendasPeriodo(), filtros);
        BigDecimal receitaR01 = r01.linhas().stream().map(RelVendasPeriodo.Linha::receita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receitaR01).isEqualByComparingTo(new BigDecimal("30.00").add(new BigDecimal("20.00")));

        ResultadoRelatorio<RelClientes.Linha> amanha = t.relatorios.gerar(admin, new RelClientes(),
                Filtros.vazios().comPeriodo(new Periodo(hoje.plusDays(1), hoje.plusDays(1))));
        assertThat(amanha.linhas()).isEmpty();
    }
}
