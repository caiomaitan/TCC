package br.com.varesync.service;

import static br.com.varesync.service.CarrinhoTest.CANCELAMENTO;
import static br.com.varesync.service.CarrinhoTest.REFRIGERANTE;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.FaixaPreco;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Preço por quantidade no carrinho (F20.2): faixa alcançada, volta ao preço anterior, promoção e descontos. Sem banco. */
class CarrinhoFaixaTest {

    private static final long PROMOCAO = 7L;
    private static final FaixaPreco SEIS = new FaixaPreco(1, v("6"), v("4.99"));
    private static final FaixaPreco DOZE = new FaixaPreco(2, v("12"), v("4.59"));

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    /** Registra uma unidade do refrigerante, como o VendaService: inclui, guarda as faixas e as aplica. */
    private static List<String> registrar(Carrinho carrinho, String quantidade, BigDecimal preco, Long promocao,
                                          List<FaixaPreco> faixas) {
        carrinho.adicionar(REFRIGERANTE, v(quantidade), preco, promocao, null, true);
        carrinho.definirFaixas(REFRIGERANTE.id(), faixas);
        return carrinho.aplicarFaixas(REFRIGERANTE.id());
    }

    @Test
    void aSextaUnidadePoeTodasNaFaixaECancelarUmaVoltaAoPrecoNormal() {
        Carrinho carrinho = new Carrinho();
        for (int i = 0; i < 5; i++) {
            registrar(carrinho, "1", REFRIGERANTE.precoVenda(), null, List.of(SEIS));
        }
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("27.45");
        assertThat(carrinho.itens()).allSatisfy(i -> assertThat(i.faixa()).isNull());

        registrar(carrinho, "1", REFRIGERANTE.precoVenda(), null, List.of(SEIS));
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("29.94");
        assertThat(carrinho.itens()).allSatisfy(i -> {
            assertThat(i.faixa()).isEqualTo(SEIS);
            assertThat(i.precoUnitario()).isEqualByComparingTo("4.99");
        });

        carrinho.cancelar(6, CANCELAMENTO);
        carrinho.aplicarFaixas(REFRIGERANTE.id());
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("27.45");
        assertThat(carrinho.itens().subList(0, 5)).allSatisfy(i -> {
            assertThat(i.faixa()).isNull();
            assertThat(i.precoUnitario()).isEqualByComparingTo("5.49");
        });
    }

    @Test
    void valeAMaiorFaixaAlcancadaEUmItemComVariasUnidadesContaTodas() {
        Carrinho carrinho = new Carrinho();
        registrar(carrinho, "6", REFRIGERANTE.precoVenda(), null, List.of(SEIS, DOZE));
        assertThat(carrinho.itens().get(0).faixa()).isEqualTo(SEIS);
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("29.94");

        registrar(carrinho, "6", REFRIGERANTE.precoVenda(), null, List.of(SEIS, DOZE));
        assertThat(carrinho.itens()).allSatisfy(i -> assertThat(i.faixa()).isEqualTo(DOZE));
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("55.08"); // 12 × 4,59
    }

    @Test
    void promocaoMenorQueAFaixaContinuaValendo() {
        Carrinho carrinho = new Carrinho();
        for (int i = 0; i < 6; i++) {
            registrar(carrinho, "1", v("4.79"), PROMOCAO, List.of(SEIS));
        }
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("28.74");
        assertThat(carrinho.itens()).allSatisfy(i -> {
            assertThat(i.faixa()).isNull();
            assertThat(i.promocaoId()).isEqualTo(PROMOCAO);
        });
    }

    @Test
    void faixaMenorQueAPromocaoTiraAPromocaoDoItem() {
        Carrinho carrinho = new Carrinho();
        registrar(carrinho, "6", v("5.19"), PROMOCAO, List.of(SEIS));
        ItemCarrinho item = carrinho.itens().get(0);
        assertThat(item.faixa()).isEqualTo(SEIS);
        assertThat(item.promocaoId()).isNull();
        assertThat(item.valorBruto()).isEqualByComparingTo("29.94");
    }

    @Test
    void itemForaDasFaixasNaoContaNemMudaDePreco() {
        Carrinho carrinho = new Carrinho();
        // Embalagem com preço próprio: 12 un por R$ 59,90, fora das faixas.
        carrinho.adicionar(REFRIGERANTE, v("12"), v("4.99"), null, v("59.90"), false);
        registrar(carrinho, "5", REFRIGERANTE.precoVenda(), null, List.of(SEIS));
        assertThat(carrinho.itens().get(1).faixa()).isNull();
        assertThat(carrinho.valorBruto()).isEqualByComparingTo("87.35"); // 59,90 + 5 × 5,49
        assertThat(carrinho.itens().get(0).valorBruto()).isEqualByComparingTo("59.90");
    }

    @Test
    void descontoQueCabeNoNovoValorFicaEOQueNaoCabeERetiradoComAviso() {
        Carrinho carrinho = new Carrinho();
        registrar(carrinho, "1", REFRIGERANTE.precoVenda(), null, List.of(SEIS));
        registrar(carrinho, "4", REFRIGERANTE.precoVenda(), null, List.of(SEIS));
        carrinho.definirDescontoItem(1, v("5.00"));   // 5,49 → 4,99: não cabe
        carrinho.definirDescontoItem(2, v("1.00"));   // 21,96 → 19,96: cabe

        List<String> avisos = registrar(carrinho, "1", REFRIGERANTE.precoVenda(), null, List.of(SEIS));

        assertThat(carrinho.item(1).orElseThrow().desconto()).isEqualByComparingTo("0.00");
        assertThat(carrinho.item(2).orElseThrow().desconto()).isEqualByComparingTo("1.00");
        assertThat(avisos).singleElement().asString()
                .contains("desconto do item 1").contains("R$ 5,00").contains("R$ 4,99");
    }

    @Test
    void descontoDaVendaQueCabeFicaEOQueNaoCabeERetirado() {
        Carrinho carrinho = new Carrinho();
        registrar(carrinho, "5", REFRIGERANTE.precoVenda(), null, List.of(SEIS));
        carrinho.definirDescontoVenda(v("27.00"));
        assertThat(registrar(carrinho, "1", REFRIGERANTE.precoVenda(), null, List.of(SEIS))).isEmpty(); // 29,94
        assertThat(carrinho.descontoVenda()).isEqualByComparingTo("27.00");

        FaixaPreco umReal = new FaixaPreco(3, v("6"), v("1.00"));
        Carrinho outro = new Carrinho();
        registrar(outro, "5", REFRIGERANTE.precoVenda(), null, List.of(umReal));
        outro.definirDescontoVenda(v("27.00"));
        List<String> avisos = registrar(outro, "1", REFRIGERANTE.precoVenda(), null, List.of(umReal));
        assertThat(outro.valorBruto()).isEqualByComparingTo("6.00");
        assertThat(outro.descontoVenda()).isEqualByComparingTo("0.00");
        assertThat(avisos).containsExactly(Carrinho.DESCONTO_VENDA_RETIRADO);
    }

    @Test
    void liberacaoDoDescontoNaoValeQuandoAFaixaAumentaAProporcao() {
        FaixaPreco umReal = new FaixaPreco(3, v("6"), v("1.00"));
        Carrinho carrinho = new Carrinho();
        registrar(carrinho, "5", REFRIGERANTE.precoVenda(), null, List.of(umReal));
        carrinho.definirDescontoVenda(v("5.00"));
        carrinho.registrarLiberacaoDesconto(new Liberacao(9, "gerente", "Gerente",
                br.com.varesync.model.Permissao.DESCONTO_ACIMA_LIMITE));
        assertThat(carrinho.liberacaoDescontoValida()).isPresent();

        // 6 un a R$ 1,00: o desconto de R$ 5,00 passa de 18% para 83% do valor e pede nova liberação.
        registrar(carrinho, "1", REFRIGERANTE.precoVenda(), null, List.of(umReal));
        assertThat(carrinho.descontoVenda()).isEqualByComparingTo("5.00");
        assertThat(carrinho.liberacaoDescontoValida()).isEmpty();
    }
}
