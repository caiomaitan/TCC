package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Compras do cliente (F12.2 e F13.3), de {@code vw_resultado_item}: só vendas finalizadas e itens não cancelados,
 * menos o que foi devolvido de cada uma. Total gasto = soma das compras já sem as devoluções; ticket médio = valor
 * comprado ÷ número de compras (só as vendas, como no R01 e no R18).
 *
 * @param compras  da mais recente para a mais antiga
 * @param produtos do mais comprado (em número de compras) para o menos
 */
public record HistoricoCliente(List<Compra> compras, List<ProdutoComprado> produtos) {

    /**
     * Uma venda do cliente: número, data/hora, itens (linhas não canceladas), valor comprado e o que dele já foi
     * devolvido (em qualquer data).
     */
    public record Compra(long vendaId, OffsetDateTime dataHora, int itens, BigDecimal comprado, BigDecimal devolvido) {

        /** Valor da compra já sem as devoluções. */
        public BigDecimal valor() {
            return comprado.subtract(devolvido);
        }
    }

    /** Produto comprado: em quantas compras, a quantidade e o valor, já sem as devoluções. */
    public record ProdutoComprado(long produtoId, String nome, String unidade, boolean fracionada, int compras,
                                  BigDecimal quantidade, BigDecimal valor) {
    }

    public HistoricoCliente {
        compras = List.copyOf(compras);
        produtos = List.copyOf(produtos);
    }

    public BigDecimal totalGasto() {
        return compras.stream().map(Compra::valor).reduce(Dinheiro.ZERO, BigDecimal::add);
    }

    /** Vazio sem compras. */
    public Optional<BigDecimal> ticketMedio() {
        BigDecimal comprado = compras.stream().map(Compra::comprado).reduce(Dinheiro.ZERO, BigDecimal::add);
        return Dinheiro.dividir(comprado, BigDecimal.valueOf(compras.size()), Dinheiro.ESCALA_DINHEIRO);
    }

    public Optional<OffsetDateTime> ultimaCompra() {
        return compras.isEmpty() ? Optional.empty() : Optional.of(compras.get(0).dataHora());
    }
}
