package br.com.varesync.service;

import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.util.Gtin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Embalagens e preço de atacado dos dados de demonstração (F20.3): o fardo de 6 do refrigerante cola com preço próprio
 * e faixas de preço por quantidade em três produtos de mercearia. Entram no cadastro dos produtos, na véspera; os
 * preços, os códigos e os produtos do catálogo não mudam (0.9).
 */
final class AtacadoDemo {

    /** Produto do catálogo que tem o fardo. */
    static final String PRODUTO_FARDO = "Refrigerante cola PET 2 L";
    static final BigDecimal QUANTIDADE_FARDO = BigDecimal.valueOf(6);
    /** R$ 8,32 por unidade, contra R$ 9,49 da unidade solta (custo R$ 6,10). */
    static final BigDecimal PRECO_FARDO = new BigDecimal("49.90");
    /** Código do fardo: prefixo do catálogo + 90001 + DV (fora da sequência dos códigos dos produtos). */
    static final String CODIGO_FARDO = gtin("90001");

    /** Faixas por produto do catálogo: a partir de 3 (e de 6, no molho), abaixo do preço normal e acima do custo. */
    static final Map<String, List<FaixaPreco>> FAIXAS = Map.of(
            "Óleo de soja 900 ml", List.of(faixa(3, "6.99")),
            "Macarrão espaguete 500 g", List.of(faixa(3, "4.19")),
            "Molho de tomate 340 g", List.of(faixa(3, "2.79"), faixa(6, "2.59")));

    /** Vendas com o fardo, por mil vendas (sorteadas com semente própria). */
    static final int FARDOS_POR_MIL_VENDAS = 40;

    private AtacadoDemo() {
    }

    /** Os códigos do produto do catálogo: o principal e, no refrigerante cola, o fardo. */
    static List<CodigoBarras> codigos(String nome, String principal) {
        if (principal == null) {
            return List.of();
        }
        CodigoBarras unidade = new CodigoBarras(principal, true);
        return PRODUTO_FARDO.equals(nome)
                ? List.of(unidade, new CodigoBarras(CODIGO_FARDO, false, QUANTIDADE_FARDO, PRECO_FARDO))
                : List.of(unidade);
    }

    /** As faixas do produto do catálogo; nulo sem faixas (o cadastro não mexe). */
    static List<FaixaPreco> faixas(String nome) {
        return FAIXAS.get(nome);
    }

    private static FaixaPreco faixa(int quantidade, String preco) {
        return new FaixaPreco(0, BigDecimal.valueOf(quantidade), new BigDecimal(preco));
    }

    private static String gtin(String sequencia) {
        String semDigito = CatalogoDemo.PREFIXO_GTIN + sequencia;
        return semDigito + Gtin.digitoVerificador(semDigito);
    }
}
