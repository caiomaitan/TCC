package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * O que a leitura de um código de barras representa (F20.1): quantas unidades de venda e, se houver, o preço próprio
 * da embalagem (caixa, fardo).
 *
 * @param quantidade unidades de venda por leitura (1 no código da unidade)
 * @param preco      preço da embalagem; nulo sem preço próprio
 */
public record Embalagem(BigDecimal quantidade, BigDecimal preco) {

    /** O código da unidade: quantidade 1, sem preço próprio. */
    public static final Embalagem UNIDADE = new Embalagem(BigDecimal.ONE, null);

    /** Representa mais de uma unidade ou tem preço próprio? */
    public boolean ehEmbalagem() {
        return quantidade.compareTo(BigDecimal.ONE) != 0 || preco != null;
    }

    public boolean temPreco() {
        return preco != null;
    }
}
