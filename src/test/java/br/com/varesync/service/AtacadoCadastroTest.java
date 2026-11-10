package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Embalagem;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.model.ProdutoVenda;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regras do cadastro de embalagens e faixas de preço por quantidade (F20.3), o texto do F7 e a conversão da caixa na
 * entrada manual de mercadoria. Sem banco.
 */
class AtacadoCadastroTest {

    private static final String UNIDADE = "7891000315507";
    private static final String CAIXA = "17891000315504";

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private static FaixaPreco faixa(String quantidade, String preco) {
        return new FaixaPreco(0, v(quantidade), v(preco));
    }

    // ----- Embalagem -----

    @Test
    void embalagemComQuantidadeEPreco() {
        Embalagem caixa = ValidacaoProduto.embalagem(CAIXA, v("12"), v("59.9"), false);
        assertThat(caixa.quantidade()).isEqualByComparingTo("12");
        assertThat(caixa.preco()).isEqualByComparingTo("59.90");
        assertThat(ValidacaoProduto.embalagem(CAIXA, null, null, false)).isEqualTo(new Embalagem(v("1.000"), null));
    }

    @Test
    void embalagemRecusada() {
        assertThatThrownBy(() -> ValidacaoProduto.embalagem(CAIXA, v("0"), null, false))
                .hasMessage("A quantidade da embalagem do código " + CAIXA + " deve ser maior que zero.");
        assertThatThrownBy(() -> ValidacaoProduto.embalagem(CAIXA, v("1.5"), null, false))
                .hasMessageContaining("deve ser um número inteiro");
        assertThat(ValidacaoProduto.embalagem(CAIXA, v("2.5"), null, true).quantidade())
                .as("a unidade fracionada aceita").isEqualByComparingTo("2.5");
        assertThatThrownBy(() -> ValidacaoProduto.embalagem(CAIXA, v("1"), v("5.00"), false))
                .hasMessageContaining("só vale para o código de mais de uma unidade");
        assertThatThrownBy(() -> ValidacaoProduto.embalagem(CAIXA, v("12"), v("0"), false))
                .hasMessageContaining("deve ser maior que zero");
        assertThatThrownBy(() -> ValidacaoProduto.embalagem(CAIXA, v("12"), v("59.905"), false))
                .hasMessageContaining("no máximo 2 casas");
    }

    @Test
    void principalEOCodigoDaUnidade() {
        List<CodigoBarras> ok = ValidacaoProduto.codigos(List.of(new CodigoBarras(CAIXA, false, v("12"), v("59.90")),
                new CodigoBarras(UNIDADE, true)), false);
        assertThat(ok).extracting(CodigoBarras::codigo).containsExactly(UNIDADE, CAIXA);
        assertThat(ok.get(1).precoEmbalagem()).isEqualByComparingTo("59.90");
        assertThatThrownBy(() -> ValidacaoProduto.codigos(List.of(new CodigoBarras(CAIXA, true, v("12"), null),
                new CodigoBarras(UNIDADE, false)), false)).hasMessageContaining("O código principal é o da unidade");
        assertThatThrownBy(() -> ProdutoService.conferirEmbalagem(new CodigoBarras(UNIDADE, true), v("12"), null,
                false)).hasMessageContaining("O código principal é o da unidade");
    }

    // ----- Faixas -----

    @Test
    void faixasOrdenadasEComAsEscalasDoBanco() {
        List<FaixaPreco> f = ValidacaoProduto.faixas(List.of(faixa("12", "4.79"), faixa("6", "4.99")), v("5.49"),
                false);
        assertThat(f).extracting(FaixaPreco::quantidadeMinima).containsExactly(v("6.000"), v("12.000"));
        assertThat(f.get(0).preco()).isEqualTo(v("4.99"));
        assertThat(ValidacaoProduto.faixas(List.of(), v("5.49"), false)).isEmpty();
    }

    @Test
    void precoDaFaixaMenorQueONormalEQueODaFaixaMenor() {
        assertThatThrownBy(() -> ValidacaoProduto.faixas(List.of(faixa("6", "5.49")), v("5.49"), false))
                .hasMessage("O preço da faixa a partir de 6 (R$ 5,49) deve ser menor que o preço de venda (R$ 5,49).");
        assertThatThrownBy(() -> ValidacaoProduto.faixas(List.of(faixa("6", "4.99"), faixa("12", "4.99")),
                v("5.49"), false)).hasMessage("O preço da faixa a partir de 12 (R$ 4,99) deve ser menor que o da "
                + "faixa a partir de 6 (R$ 4,99).");
    }

    @Test
    void quantidadeDaFaixa() {
        assertThatThrownBy(() -> ValidacaoProduto.faixas(List.of(faixa("1", "4.99")), v("5.49"), false))
                .hasMessageContaining("a partir de mais de 1");
        assertThatThrownBy(() -> ValidacaoProduto.faixas(List.of(faixa("6.5", "4.99")), v("5.49"), false))
                .hasMessageContaining("deve ser um número inteiro");
        assertThatThrownBy(() -> ValidacaoProduto.faixas(List.of(faixa("6", "4.99"), faixa("6.000", "4.50")),
                v("5.49"), false)).hasMessageContaining("Há duas faixas de preço a partir de 6");
        assertThat(ValidacaoProduto.faixas(List.of(faixa("1.5", "35.00")), v("39.90"), true)).hasSize(1);
        assertThatThrownBy(() -> ProdutoService.conferirFaixa(0, v("6"), null, false))
                .hasMessage("Informe o preço da faixa a partir de 6.");
    }

    @Test
    void embalagemEFaixaAbaixoDoCustoPedemConfirmacao() {
        DadosProduto dados = new DadosProduto("Refrigerante", null, "UN", v("5.49"), v("3.20"), null, null,
                List.of(new CodigoBarras(UNIDADE, true), new CodigoBarras(CAIXA, false, v("12"), v("30.00"))), null,
                null, null, null, null, List.of(faixa("6", "3.10"), faixa("12", "4.00")));
        assertThat(ProdutoService.avisosAbaixoDoCusto(dados)).containsExactly(
                "O preço da embalagem do código " + CAIXA + " (R$ 30,00 por 12) está abaixo do custo dessas unidades "
                        + "(R$ 38,40): cada venda dará prejuízo.",
                "O preço da faixa a partir de 6 (R$ 3,10) está abaixo do custo: cada venda nessa faixa dará prejuízo.");
    }

    // ----- F7 -----

    @Test
    void consultaDePrecoMostraFaixasEEmbalagens() {
        ProdutoVenda refri = new ProdutoVenda(1, UNIDADE, "Refrigerante Cola 2 L", "UN", false, v("5.49"), v("3.20"),
                true, v("100"));
        VendaService.ConsultaPreco normal = new VendaService.ConsultaPreco(refri,
                new PrecoService.Preco(v("5.49"), null), List.of(new FaixaPreco(3, v("6.000"), v("4.99"))),
                List.of(new CodigoBarras(CAIXA, false, v("12"), v("59.90"))), new Embalagem(v("12"), v("59.90")));
        assertThat(normal.texto()).isEqualTo("""
                Refrigerante Cola 2 L
                R$ 5,49 / UN
                Código lido: embalagem com 12 un
                A partir de 6 un: R$ 4,99 / UN
                Embalagem com 12 un (17891000315504): R$ 59,90
                Estoque: 100 UN""");

        VendaService.ConsultaPreco promocao = new VendaService.ConsultaPreco(refri,
                new PrecoService.Preco(v("4.79"), 7L), List.of(new FaixaPreco(3, v("6"), v("4.99"))),
                List.of(new CodigoBarras(CAIXA, false, v("12"), v("59.90"))), null);
        assertThat(promocao.texto()).contains("R$ 4,79 / UN (promoção; preço normal R$ 5,49)",
                "A partir de 6 un: R$ 4,99 / UN (não vale: a promoção é menor)",
                "Embalagem com 12 un (17891000315504): R$ 57,48 pelas unidades (preço da embalagem R$ 59,90)")
                .doesNotContain("Código lido");
    }

    // ----- Entrada manual -----

    /** Aceite "Entrada manual": 5 caixas de 12 a R$ 39,00 → 60 UN a R$ 3,2500. */
    @Test
    void cincoCaixasDe12A3900Lancam60UnA32500() {
        EntradaService.Valores v = EntradaService.valoresDaEmbalagem(v("5"), v("39.00"), null, v("12"), false);
        assertThat(v.quantidade()).isEqualByComparingTo("60");
        assertThat(v.custo()).isEqualTo(v("3.2500"));
        assertThat(v.total()).isEqualTo(v("195.00"));

        EntradaService.Valores pelaLinha = EntradaService.valoresDaEmbalagem(v("5"), null, v("195.00"), v("12"), false);
        assertThat(pelaLinha.custo()).isEqualTo(v("3.2500"));
        assertThatThrownBy(() -> EntradaService.valoresDaEmbalagem(v("1.5"), v("39.00"), null, v("12"), false))
                .hasMessageContaining("informe a quantidade em embalagens, um número inteiro");
    }

    @Test
    void conversaoMostradaNaTela() {
        ProdutoVenda refri = new ProdutoVenda(1, CAIXA, "Refrigerante Cola 2 L", "UN", false, v("5.49"), v("3.20"),
                true, v("100"));
        EntradaService.LeituraEntrada caixa = new EntradaService.LeituraEntrada(refri, v("12.000"));
        assertThat(caixa.ehEmbalagem()).isTrue();
        assertThat(caixa.instrucao()).isEqualTo("embalagem com 12 UN: quantidade em embalagens e custo por embalagem");
        assertThat(caixa.conversao(v("5"), v("39"), false))
                .contains("5 embalagens × 12 = 60 UN a R$ 3,2500 (R$ 195,00)");
        assertThat(caixa.conversao(v("1"), null, false)).contains("1 embalagem × 12 = 12 UN");
        assertThat(caixa.conversao(null, v("39"), false)).isEmpty();
        assertThat(new EntradaService.LeituraEntrada(refri, BigDecimal.ONE).conversao(v("5"), v("39"), false))
                .isEqualTo(Optional.empty());
    }
}
