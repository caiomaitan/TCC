package br.com.varesync.model;

import br.com.varesync.util.CalculoDevolucao;
import java.math.BigDecimal;

/**
 * Item de uma venda visto pela devolução (F13.2): quanto foi vendido e quanto já foi devolvido, com o valor líquido, o
 * custo e o que deles já voltou. Itens cancelados durante a venda não podem ser devolvidos.
 */
public record ItemDevolucao(long vendaItemId, int sequencia, long produtoId, String codigo, String descricao,
                            String unidade, boolean fracionada, boolean cancelado, BigDecimal quantidade,
                            BigDecimal quantidadeDevolvida, BigDecimal valorLiquido, BigDecimal valorDevolvido,
                            BigDecimal custoUnitario, BigDecimal custoTotal, BigDecimal custoDevolvido) {

    /** Quantidade que ainda pode ser devolvida (zero no item cancelado). */
    public BigDecimal disponivel() {
        return cancelado ? BigDecimal.ZERO : quantidade.subtract(quantidadeDevolvida);
    }

    /** Valor a devolver por {@code q} unidades: proporcional, ou o restante na devolução que completa o item. */
    public BigDecimal valor(BigDecimal q) {
        return CalculoDevolucao.parte(valorLiquido, quantidade, quantidadeDevolvida, valorDevolvido, q);
    }

    /** Custo (CMV) que volta com {@code q} unidades, pela mesma regra do valor. */
    public BigDecimal custo(BigDecimal q) {
        return CalculoDevolucao.parte(custoTotal, quantidade, quantidadeDevolvida, custoDevolvido, q);
    }
}
