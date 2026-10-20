package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Cartões do painel (7.5) de um dia: faturamento, nº de vendas, lucro bruto e, calculados, ticket médio e margem
 * (Σ lucro ÷ Σ receita). Vendas e itens cancelados ficam de fora ({@code vw_venda_item_rentabilidade}).
 */
public record IndicadoresDia(BigDecimal receita, long vendas, BigDecimal lucro) {

    public static final IndicadoresDia VAZIO = new IndicadoresDia(Dinheiro.ZERO, 0, Dinheiro.ZERO);

    public Optional<BigDecimal> ticketMedio() {
        return Dinheiro.dividir(receita, BigDecimal.valueOf(vendas), Dinheiro.ESCALA_DINHEIRO);
    }

    public Optional<BigDecimal> margem() {
        return Dinheiro.percentualDe(lucro, receita);
    }

    public IndicadoresDia somar(BigDecimal receitaVenda, BigDecimal lucroVenda) {
        return new IndicadoresDia(receita.add(receitaVenda), vendas + 1, lucro.add(lucroVenda));
    }

    /**
     * Variação % de um valor em relação ao anterior: (atual − anterior) ÷ |anterior| × 100. Vazio se o anterior é
     * zero (não há base) ou se algum dos dois falta.
     */
    public static Optional<BigDecimal> variacao(Optional<BigDecimal> atual, Optional<BigDecimal> anterior) {
        if (atual.isEmpty() || anterior.isEmpty()) {
            return Optional.empty();
        }
        return Dinheiro.percentualDe(atual.get().subtract(anterior.get()), anterior.get().abs());
    }

    public static Optional<BigDecimal> variacao(BigDecimal atual, BigDecimal anterior) {
        return variacao(Optional.of(atual), Optional.of(anterior));
    }

    /** Variação da margem em pontos percentuais (margem atual − margem anterior); vazio se faltar uma delas. */
    public static Optional<BigDecimal> variacaoEmPontos(Optional<BigDecimal> atual, Optional<BigDecimal> anterior) {
        if (atual.isEmpty() || anterior.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Dinheiro.percentual(atual.get().subtract(anterior.get())));
    }
}
