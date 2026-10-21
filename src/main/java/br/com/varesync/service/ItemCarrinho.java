package br.com.varesync.service;

import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;

/**
 * Item da venda em andamento (6.6). {@code valorBruto} = round(quantidade × preço, 2), ou o valor da etiqueta de
 * balança com preço (6.9); o desconto do item fica em reais. Item cancelado continua na lista (riscado), sem
 * desconto, e não soma.
 */
public final class ItemCarrinho {

    private final int sequencia;
    private final ProdutoVenda produto;
    private final BigDecimal quantidade;
    private final BigDecimal precoUnitario;
    private final Long promocaoId;
    private final BigDecimal valorBruto;
    private BigDecimal desconto = Dinheiro.ZERO;
    private boolean cancelado;

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
        return cancelado;
    }

    /** Valor do item depois do desconto do item (antes do rateio do desconto da venda); zero se cancelado. */
    public BigDecimal base() {
        return cancelado ? Dinheiro.ZERO : valorBruto.subtract(desconto);
    }

    void definirDesconto(BigDecimal valor) {
        this.desconto = Dinheiro.dinheiro(valor);
    }

    void cancelar() {
        this.cancelado = true;
        this.desconto = Dinheiro.ZERO;
    }
}
