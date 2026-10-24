package br.com.varesync.service;

import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Item da venda em andamento (6.6). {@code valorBruto} = round(quantidade × preço, 2), ou o valor da etiqueta de
 * balança com preço (6.9); o desconto do item fica em reais. Item cancelado continua na lista (riscado), sem
 * desconto, e não soma. O cancelamento guarda quando, quem cancelou e quem liberou (F10.3), gravados em
 * {@code venda_item} na finalização.
 */
public final class ItemCarrinho {

    /** Cancelamento do item (F6): quando, quem cancelou e quem liberou (nulo sem liberação). */
    public record Cancelamento(OffsetDateTime em, long porUsuarioId, Long liberadoPorUsuarioId) {
    }

    private final int sequencia;
    private final ProdutoVenda produto;
    private final BigDecimal quantidade;
    private final BigDecimal precoUnitario;
    private final Long promocaoId;
    private final BigDecimal valorBruto;
    private BigDecimal desconto = Dinheiro.ZERO;
    private Cancelamento cancelamento;

    ItemCarrinho(int sequencia, ProdutoVenda produto, BigDecimal quantidade, BigDecimal precoUnitario,
                 Long promocaoId) {
        this(sequencia, produto, quantidade, precoUnitario, promocaoId, null);
    }

    /**
     * @param valorFixo valor bruto já dado (etiqueta de balança com preço, 6.9: o valor impresso, sem recálculo);
     *                  nulo = round(quantidade × preço, 2)
     */
    ItemCarrinho(int sequencia, ProdutoVenda produto, BigDecimal quantidade, BigDecimal precoUnitario,
                 Long promocaoId, BigDecimal valorFixo) {
        this.sequencia = sequencia;
        this.produto = produto;
        this.quantidade = quantidade;
        this.precoUnitario = precoUnitario;
        this.promocaoId = promocaoId;
        this.valorBruto = valorFixo != null ? Dinheiro.dinheiro(valorFixo)
                : Dinheiro.multiplicar(quantidade, precoUnitario);
    }

    public int sequencia() {
        return sequencia;
    }

    public ProdutoVenda produto() {
        return produto;
    }

    public BigDecimal quantidade() {
        return quantidade;
    }

    public BigDecimal precoUnitario() {
        return precoUnitario;
    }

    public Long promocaoId() {
        return promocaoId;
    }

    public BigDecimal valorBruto() {
        return valorBruto;
    }

    public BigDecimal desconto() {
        return desconto;
    }

    public boolean cancelado() {
        return cancelamento != null;
    }

    /** Dados do cancelamento; nulo se o item não foi cancelado. */
    public Cancelamento cancelamento() {
        return cancelamento;
    }

    /** Valor do item depois do desconto do item (antes do rateio do desconto da venda); zero se cancelado. */
    public BigDecimal base() {
        return cancelado() ? Dinheiro.ZERO : valorBruto.subtract(desconto);
    }

    void definirDesconto(BigDecimal valor) {
        this.desconto = Dinheiro.dinheiro(valor);
    }

    void cancelar(Cancelamento dados) {
        this.cancelamento = dados;
        this.desconto = Dinheiro.ZERO;
    }
}
