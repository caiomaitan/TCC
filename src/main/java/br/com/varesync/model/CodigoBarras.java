package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Código de barras de um produto (GTIN-8/12/13/14); um produto pode ter vários e um principal. Cada código diz quantas
 * unidades de venda a leitura dele representa (F20.1: 1 na unidade, 12 na caixa com 12) e, se houver, o preço próprio
 * da embalagem.
 *
 * @param quantidade     unidades de venda por leitura (o principal tem 1)
 * @param precoEmbalagem preço da embalagem; nulo sem preço próprio
 */
public record CodigoBarras(String codigo, boolean principal, BigDecimal quantidade, BigDecimal precoEmbalagem) {

    /** Escalas do banco (quantidade 3, preço 2), para dois códigos iguais serem iguais (1 = 1,000). */
    public CodigoBarras {
        quantidade = escala(quantidade == null ? BigDecimal.ONE : quantidade, 3);
        precoEmbalagem = precoEmbalagem == null ? null : escala(precoEmbalagem, 2);
    }

    /** Só acrescenta zeros: o valor com mais casas fica como veio, para a validação recusar. */
    private static BigDecimal escala(BigDecimal valor, int casas) {
        return valor.stripTrailingZeros().scale() <= casas ? valor.setScale(casas) : valor;
    }

    /** Código da unidade: quantidade 1, sem preço de embalagem. */
    public CodigoBarras(String codigo, boolean principal) {
        this(codigo, principal, BigDecimal.ONE, null);
    }

    public Embalagem embalagem() {
        return new Embalagem(quantidade, precoEmbalagem);
    }

    /** Representa mais de uma unidade ou tem preço próprio (caixa, fardo). */
    public boolean ehEmbalagem() {
        return embalagem().ehEmbalagem();
    }

    public CodigoBarras comPrincipal(boolean outro) {
        return new CodigoBarras(codigo, outro, quantidade, precoEmbalagem);
    }

    public CodigoBarras comEmbalagem(BigDecimal outraQuantidade, BigDecimal outroPreco) {
        return new CodigoBarras(codigo, principal, outraQuantidade, outroPreco);
    }
}
