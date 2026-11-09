package br.com.varesync.service;

import br.com.varesync.model.FaixaPreco;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Item da venda em andamento (6.6). {@code valorBruto} = round(quantidade × preço, 2), ou um valor fixo: o da
 * etiqueta de balança com preço (6.9) ou o da embalagem com preço próprio (F20.2). O desconto do item fica em reais.
 * Item cancelado continua na lista (riscado), sem desconto, e não soma. O cancelamento guarda quando, quem cancelou e
 * quem liberou (F10.3), gravados em {@code venda_item} na finalização.
 *
 * <p>Preço por quantidade (F20.2): o item guarda o preço vigente (normal ou promoção) e, quando a quantidade do
 * produto no carrinho alcança uma faixa mais barata, a faixa ({@link Carrinho#aplicarFaixas}); então o preço do item
 * é o da faixa e ele deixa de ser promocional. Só participa das faixas o item sem valor fixo e que não veio da balança.
 */
public final class ItemCarrinho {

    /** Cancelamento do item (F6): quando, quem cancelou e quem liberou (nulo sem liberação). */
    public record Cancelamento(OffsetDateTime em, long porUsuarioId, Long liberadoPorUsuarioId) {
    }

    private final int sequencia;
    private final ProdutoVenda produto;
    private final BigDecimal quantidade;
    private final BigDecimal precoVigente;
    private final Long promocaoVigente;
    private final BigDecimal valorFixo;
    private final boolean participaFaixa;
    private FaixaPreco faixa;
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
        this(sequencia, produto, quantidade, precoUnitario, promocaoId, valorFixo, valorFixo == null);
    }

    /** @param participaFaixa entra na contagem e no preço das faixas por quantidade (F20.2) */
    ItemCarrinho(int sequencia, ProdutoVenda produto, BigDecimal quantidade, BigDecimal precoUnitario,
                 Long promocaoId, BigDecimal valorFixo, boolean participaFaixa) {
        this.sequencia = sequencia;
        this.produto = produto;
        this.quantidade = quantidade;
        this.precoVigente = precoUnitario;
        this.promocaoVigente = promocaoId;
        this.valorFixo = valorFixo == null ? null : Dinheiro.dinheiro(valorFixo);
        this.participaFaixa = participaFaixa && valorFixo == null;
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

    /** Preço do item: o da faixa alcançada ou, sem ela, o vigente (normal ou promoção). */
    public BigDecimal precoUnitario() {
        return faixa != null ? faixa.preco() : precoVigente;
    }

    /** Promoção que deu o preço; nula sem promoção ou quando vale a faixa. */
    public Long promocaoId() {
        return faixa != null ? null : promocaoVigente;
    }

    /** Faixa de preço por quantidade que deu o preço (F20.2); nula sem faixa. */
    public FaixaPreco faixa() {
        return faixa;
    }

    public boolean participaFaixa() {
        return participaFaixa;
    }

    /** Preço vigente sem a faixa (normal ou promoção). */
    BigDecimal precoVigente() {
        return precoVigente;
    }

    public BigDecimal valorBruto() {
        return valorFixo != null ? valorFixo : Dinheiro.multiplicar(quantidade, precoUnitario());
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
        return cancelado() ? Dinheiro.ZERO : valorBruto().subtract(desconto);
    }

    void definirDesconto(BigDecimal valor) {
        this.desconto = Dinheiro.dinheiro(valor);
    }

    /** @param nova nula volta ao preço vigente */
    void definirFaixa(FaixaPreco nova) {
        this.faixa = nova;
    }

    void cancelar(Cancelamento dados) {
        this.cancelamento = dados;
        this.desconto = Dinheiro.ZERO;
    }
}
