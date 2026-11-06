package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Item de uma entrada de mercadoria: produto, quantidade, custo unitário (4 casas) e valor total da linha. Traz também
 * o custo médio e o preço atuais do produto, para comparar antes de confirmar, e (F17.3) se o produto controla
 * validade e quanto já foi dividido em lotes.
 *
 * @param quantidadeEmLotes soma da divisão do item em lotes (zero sem divisão)
 */
public record ItemEntrada(long id, long produtoId, String produtoNome, String codigoBarras, String unidade,
                          boolean unidadeFracionada, BigDecimal quantidade, BigDecimal custoUnitario,
                          BigDecimal valorTotal, BigDecimal custoAtual, BigDecimal precoVenda,
                          boolean controlaValidade, BigDecimal quantidadeEmLotes) {

    /** Sem o controle de validade (o construtor de antes da F17). */
    public ItemEntrada(long id, long produtoId, String produtoNome, String codigoBarras, String unidade,
                       boolean unidadeFracionada, BigDecimal quantidade, BigDecimal custoUnitario,
                       BigDecimal valorTotal, BigDecimal custoAtual, BigDecimal precoVenda) {
        this(id, produtoId, produtoNome, codigoBarras, unidade, unidadeFracionada, quantidade, custoUnitario,
                valorTotal, custoAtual, precoVenda, false, BigDecimal.ZERO);
    }

    /** Quanto falta dividir em lotes (negativo = a divisão passou da quantidade); zero se não controla. */
    public BigDecimal faltaDividir() {
        return controlaValidade ? quantidade.subtract(quantidadeEmLotes) : BigDecimal.ZERO;
    }

    /** Produto com controle cuja divisão em lotes não soma a quantidade do item: impede a confirmação. */
    public boolean divisaoIncompleta() {
        return controlaValidade && faltaDividir().signum() != 0;
    }
}
