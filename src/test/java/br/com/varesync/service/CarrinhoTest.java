package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.TipoDesconto;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Cálculo do carrinho (6.6, 9.1): totais, descontos, limite, cancelamento e rateio. Sem banco. */
class CarrinhoTest {

    static final ProdutoVenda REFRIGERANTE = new ProdutoVenda(1, "7891000315507", "Refrigerante", "UN", false,
            new BigDecimal("5.49"), new BigDecimal("3.2000"), true, new BigDecimal("10"));
    static final ProdutoVenda QUEIJO = new ProdutoVenda(2, "2", "Queijo muçarela", "KG", true,
            new BigDecimal("39.90"), new BigDecimal("28.0000"), true, new BigDecimal("1"));

    /** Cancelamento sem liberação, feito pelo usuário 1 (os dados só são gravados na finalização). */
    static final ItemCarrinho.Cancelamento CANCELAMENTO = new ItemCarrinho.Cancelamento(
            java.time.OffsetDateTime.parse("2026-10-24T10:00:00-03:00"), 1, null);

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    /** 3 × R$ 5,49 + 0,345 kg × R$ 39,90 (roteiro de aceite da Fase 4). */
    private static Carrinho carrinhoDoAceite() {
        Carrinho carrinho = new Carrinho();
        carrinho.adicionar(REFRIGERANTE, v("3"), REFRIGERANTE.precoVenda(), null);
        carrinho.adicionar(QUEIJO, v("0.345"), QUEIJO.precoVenda(), null);
        return carrinho;
    }

    @Test
    void itemFracionadoArredondaNoCentavoA11() {
        Carrinho carrinho = new Carrinho();
        ItemCarrinho item = carrinho.adicionar(QUEIJO, v("0.345"), v("39.90"), null);
        assertThat(item.valorBruto()).isEqualByComparingTo("13.77"); // 13,7655
        assertThat(item.sequencia()).isEqualTo(1);
    }

    @Test
    void totaisDoAceiteComCincoPorCentoNaVendaERateio() {
        Carrinho carrinho = carrinhoDoAceite();
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("30.24");

        BigDecimal cincoPorCento = TipoDesconto.PERCENTUAL.emReais(v("5"), carrinho.baseDescontoVenda());
        assertThat(cincoPorCento).isEqualByComparingTo("1.51"); // 1,512
        carrinho.definirDescontoVenda(cincoPorCento);

        assertThat(carrinho.total()).isEqualByComparingTo("28.73");
        assertThat(carrinho.dentroDoLimite(v("5"))).isTrue();
        assertThat(carrinho.rateio()).containsExactly(Map.entry(1, v("0.82")), Map.entry(2, v("0.69")));
        assertThat(carrinho.percentualDesconto()).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("4.99"));
    }

    @Test
    void limiteSomaDescontosDosItensEDaVenda() {
        Carrinho carrinho = carrinhoDoAceite();
        carrinho.definirDescontoItem(1, v("1.00"));
        carrinho.definirDescontoVenda(v("0.51"));
        assertThat(carrinho.descontos()).isEqualByComparingTo("1.51");
        assertThat(carrinho.dentroDoLimite(v("5"))).isTrue();

        carrinho.definirDescontoVenda(v("0.52")); // 1,52 ÷ 30,24 = 5,026%
        assertThat(carrinho.dentroDoLimite(v("5"))).isFalse();
        // O rateio usa a base depois do desconto do item: 15,47 e 13,77.
        assertThat(carrinho.rateio().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.52");
    }

    @Test
    void descontoNoItemAteOValorDoItemENaVendaAteOTotal() {
        Carrinho carrinho = carrinhoDoAceite();
        assertThatThrownBy(() -> carrinho.definirDescontoItem(1, v("16.48")))
                .hasMessageContaining("não pode passar do valor do item (R$ 16,47)");
        carrinho.definirDescontoItem(1, v("16.47"));
        assertThatThrownBy(() -> carrinho.definirDescontoVenda(v("13.78")))
                .hasMessageContaining("não pode passar do total da venda (R$ 13,77)");
        carrinho.definirDescontoVenda(v("13.77"));
        assertThat(carrinho.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void descontoNoItemNaoPodeDeixarODescontoDaVendaMaiorQueOTotal() {
        Carrinho carrinho = carrinhoDoAceite();
        carrinho.definirDescontoVenda(v("20.00"));
        assertThatThrownBy(() -> carrinho.definirDescontoItem(2, v("13.00")))
                .hasMessageContaining("Reduza o desconto da venda");
        assertThat(carrinho.item(2).orElseThrow().desconto()).isEqualByComparingTo("0");
    }

    @Test
    void itemCanceladoSaiDoTotalFicaSemDescontoESemRateio() {
        Carrinho carrinho = carrinhoDoAceite();
        carrinho.definirDescontoItem(2, v("0.77"));
        carrinho.definirDescontoVenda(v("1.00"));

        assertThat(carrinho.cancelar(2, CANCELAMENTO)).isFalse();
        ItemCarrinho cancelado = carrinho.item(2).orElseThrow();
        assertThat(cancelado.cancelado()).isTrue();
        assertThat(cancelado.desconto()).isEqualByComparingTo("0");
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("16.47");
        assertThat(carrinho.total()).isEqualByComparingTo("15.47");
        assertThat(carrinho.rateio()).containsOnlyKeys(1);
        assertThat(carrinho.itens()).hasSize(2); // continua na lista (riscado)
        assertThatThrownBy(() -> carrinho.cancelar(2, CANCELAMENTO)).hasMessageContaining("já foi cancelado");
    }

    @Test
    void cancelarItemQueDeixaODescontoDaVendaMaiorQueOTotalRetiraODesconto() {
        Carrinho carrinho = carrinhoDoAceite();
        carrinho.definirDescontoVenda(v("20.00"));
        assertThat(carrinho.cancelar(1, CANCELAMENTO)).isTrue();
        assertThat(carrinho.descontoVenda()).isEqualByComparingTo("0");
        assertThat(carrinho.total()).isEqualByComparingTo("13.77");
    }

    @Test
    void quantidadeDoProdutoSomaOsItensNaoCancelados() {
        Carrinho carrinho = carrinhoDoAceite();
        carrinho.adicionar(REFRIGERANTE, v("2"), REFRIGERANTE.precoVenda(), null);
        assertThat(carrinho.quantidadeDoProduto(1)).isEqualByComparingTo("5");
        carrinho.cancelar(1, CANCELAMENTO);
        assertThat(carrinho.quantidadeDoProduto(1)).isEqualByComparingTo("2");
        assertThat(carrinho.ultimoItem()).hasValueSatisfying(i -> assertThat(i.sequencia()).isEqualTo(3));
    }

    @Test
    void liberacaoDoDescontoValeAteODescontoLiberado() {
        Carrinho carrinho = carrinhoDoAceite();
        carrinho.definirDescontoVenda(v("3.02")); // ~10%
        Liberacao liberacao = new Liberacao(9, "admin", "Ana Admin", Permissao.DESCONTO_ACIMA_LIMITE);
        carrinho.registrarLiberacaoDesconto(liberacao);
        assertThat(carrinho.liberacaoDescontoValida()).contains(liberacao);

        carrinho.definirDescontoVenda(v("2.00")); // menor: continua coberto
        assertThat(carrinho.liberacaoDescontoValida()).contains(liberacao);

        carrinho.definirDescontoVenda(v("3.02"));
        carrinho.cancelar(2, CANCELAMENTO); // o desconto passa a ser 3,02 de 16,47: maior que o liberado
        assertThat(carrinho.liberacaoDescontoValida()).isEmpty();
    }

    @Test
    void percentualSobreABaseEmReaisArredondaNoCentavo() {
        assertThat(TipoDesconto.PERCENTUAL.emReais(v("10"), v("16.47"))).isEqualByComparingTo("1.65"); // 1,647
        assertThat(TipoDesconto.PERCENTUAL.emReais(v("100"), v("16.47"))).isEqualByComparingTo("16.47");
        assertThat(TipoDesconto.VALOR.emReais(v("2"), v("16.47"))).isEqualByComparingTo("2.00");
    }

    @Test
    void valorDoDescontoInformadoEValidado() {
        assertThat(VendaService.valorDesconto(TipoDesconto.PERCENTUAL, v("5"), v("30.24")))
                .isEqualByComparingTo("1.51");
        assertThatThrownBy(() -> VendaService.valorDesconto(TipoDesconto.PERCENTUAL, v("101"), v("10")))
                .hasMessage("O percentual de desconto deve estar entre 0 e 100.");
        assertThatThrownBy(() -> VendaService.valorDesconto(TipoDesconto.PERCENTUAL, v("5.555"), v("10")))
                .hasMessage("O percentual pode ter no máximo 2 casas decimais.");
        assertThatThrownBy(() -> VendaService.valorDesconto(TipoDesconto.VALOR, v("-1"), v("10")))
                .hasMessage("O desconto não pode ser negativo.");
        assertThatThrownBy(() -> VendaService.valorDesconto(TipoDesconto.VALOR, v("1.005"), v("10")))
                .hasMessage("O desconto pode ter no máximo 2 casas decimais.");
    }

    @Test
    void valorDaVendaAcimaDoLimiteDaColunaERecusado() {
        Carrinho carrinho = new Carrinho();
        assertThatThrownBy(() -> carrinho.adicionar(REFRIGERANTE, v("9999999999"), v("5.49"), null))
                .hasMessageContaining("limite permitido");
        assertThat(carrinho.vazio()).isTrue();
    }
}
