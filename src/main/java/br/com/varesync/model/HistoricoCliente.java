package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Compras do cliente (F12.2), de {@code vw_venda_item_rentabilidade}: só vendas finalizadas e itens não cancelados.
 * Total gasto = soma das compras; ticket médio = total ÷ número de compras.
 *
 * @param compras  da mais recente para a mais antiga
 * @param produtos do mais comprado (em número de compras) para o menos
 */
public record HistoricoCliente(List<Compra> compras, List<ProdutoComprado> produtos) {

    /** Uma venda do cliente: número, data/hora, itens (linhas não canceladas) e valor (receita). */
    public record Compra(long vendaId, OffsetDateTime dataHora, int itens, BigDecimal valor) {
    }

    /** Produto comprado: em quantas compras, a quantidade somada e o valor. */
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
        return Dinheiro.dividir(totalGasto(), BigDecimal.valueOf(compras.size()), Dinheiro.ESCALA_DINHEIRO);
    }

    public Optional<OffsetDateTime> ultimaCompra() {
        return compras.isEmpty() ? Optional.empty() : Optional.of(compras.get(0).dataHora());
    }
}
