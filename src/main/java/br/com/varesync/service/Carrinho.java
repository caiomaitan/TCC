package br.com.varesync.service;

import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.FaixaPreco;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Rateio;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Venda em andamento, só em memória (6.6): vai para o banco na finalização, numa única transação. Calcula os
 * totais, o limite de desconto e o rateio do desconto da venda. As alterações passam pelo {@link VendaService}, que
 * aplica as regras (estoque, limite, liberação, auditoria); por isso os métodos que alteram são do pacote.
 */
public final class Carrinho {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final int ESCALA_FRACAO = 8;
    /** {@code NUMERIC(12,2)} do valor da venda e do item. */
    static final BigDecimal LIMITE_VALOR = new BigDecimal("10000000000");

    /** Aviso do desconto da venda retirado porque ficou maior que o novo total (cancelamento de item ou faixa). */
    static final String DESCONTO_VENDA_RETIRADO = "O desconto da venda foi retirado porque ficou maior que o novo "
            + "total. Se for o caso, aplique de novo (F5).";

    private final List<ItemCarrinho> itens = new ArrayList<>();
    private final Map<Long, List<FaixaPreco>> faixas = new HashMap<>();
    private BigDecimal descontoVenda = Dinheiro.ZERO;
    private Liberacao liberacaoDesconto;
    private BigDecimal fracaoLiberada;
    private ClienteVenda cliente;

    public List<ItemCarrinho> itens() {
        return Collections.unmodifiableList(itens);
    }

    /** Sem nenhum item registrado (nem cancelado). */
    public boolean vazio() {
        return itens.isEmpty();
    }

    public boolean temItensAtivos() {
        return itens.stream().anyMatch(i -> !i.cancelado());
    }

    public long quantidadeItensAtivos() {
        return itens.stream().filter(i -> !i.cancelado()).count();
    }

    public Optional<ItemCarrinho> item(int sequencia) {
        return itens.stream().filter(i -> i.sequencia() == sequencia).findFirst();
    }

    public Optional<ItemCarrinho> ultimoItem() {
        return itens.isEmpty() ? Optional.empty() : Optional.of(itens.get(itens.size() - 1));
    }

    /** Cliente identificado (F11, F12.2); pode ser trocado ou removido até a finalização. */
    public Optional<ClienteVenda> cliente() {
        return Optional.ofNullable(cliente);
    }

    /** Soma do valor bruto dos itens não cancelados ({@code venda.valor_bruto}). */
    public BigDecimal valorBruto() {
        return ativos().map(ItemCarrinho::valorBruto).reduce(Dinheiro.ZERO, BigDecimal::add);
    }

    public BigDecimal descontoItens() {
        return ativos().map(ItemCarrinho::desconto).reduce(Dinheiro.ZERO, BigDecimal::add);
    }

    public BigDecimal descontoVenda() {
        return descontoVenda;
    }

    public BigDecimal descontos() {
        return descontoItens().add(descontoVenda);
    }

    /** Total a pagar = bruto − descontos dos itens − desconto da venda ({@code ck_venda_total}). */
    public BigDecimal total() {
        return valorBruto().subtract(descontos());
    }

    /** Base do desconto da venda e do rateio: bruto − descontos dos itens. */
    public BigDecimal baseDescontoVenda() {
        return valorBruto().subtract(descontoItens());
    }

    /** Quantidade do produto já no carrinho (itens não cancelados), para a regra de estoque. */
    public BigDecimal quantidadeDoProduto(long produtoId) {
        return ativos().filter(i -> i.produto().id() == produtoId).map(ItemCarrinho::quantidade)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Descontos ÷ valor bruto, em percentual com 2 casas; vazio sem itens. */
    public Optional<BigDecimal> percentualDesconto() {
        return Dinheiro.percentualDe(descontos(), valorBruto());
    }

    /** (descontos dos itens + desconto da venda) ÷ valor bruto ≤ limite % (6.6), sem arredondar. */
    public boolean dentroDoLimite(BigDecimal limitePercentual) {
        return descontos().multiply(CEM).compareTo(limitePercentual.multiply(valorBruto())) <= 0;
    }

    /** Rateio do desconto da venda entre os itens não cancelados, proporcional à base (A.4): sequência → valor. */
    public Map<Integer, BigDecimal> rateio() {
        List<ItemCarrinho> ativos = ativos().toList();
        List<BigDecimal> partes = Rateio.ratear(descontoVenda, ativos.stream().map(ItemCarrinho::base).toList());
        Map<Integer, BigDecimal> rateio = new LinkedHashMap<>();
        for (int i = 0; i < ativos.size(); i++) {
            rateio.put(ativos.get(i).sequencia(), partes.get(i));
        }
        return rateio;
    }

    /**
     * Liberação do desconto acima do limite que ainda vale: a que foi dada para um desconto igual ou maior que o
     * atual (cancelar um item aumenta a proporção e pede nova liberação).
     */
    public Optional<Liberacao> liberacaoDescontoValida() {
        if (liberacaoDesconto == null || fracaoDesconto().compareTo(fracaoLiberada) > 0) {
            return Optional.empty();
        }
        return Optional.of(liberacaoDesconto);
    }

    // ----- Alterações (pelo VendaService) -----

    ItemCarrinho adicionar(ProdutoVenda produto, BigDecimal quantidade, BigDecimal preco, Long promocaoId) {
        return adicionar(produto, quantidade, preco, promocaoId, null);
    }

    /** @param valorFixo valor bruto da etiqueta de balança com preço (6.9); nulo = quantidade × preço */
    ItemCarrinho adicionar(ProdutoVenda produto, BigDecimal quantidade, BigDecimal preco, Long promocaoId,
                           BigDecimal valorFixo) {
        return adicionar(produto, quantidade, preco, promocaoId, valorFixo, valorFixo == null);
    }

    /**
     * Inclui o item, sem mexer nas faixas: quem chama aplica {@link #aplicarFaixas} em seguida.
     *
     * @param valorFixo      valor bruto da etiqueta de balança com preço (6.9) ou da embalagem com preço próprio
     *                       (F20.2); nulo = quantidade × preço
     * @param participaFaixa entra na contagem e no preço das faixas por quantidade (F20.2)
     */
    ItemCarrinho adicionar(ProdutoVenda produto, BigDecimal quantidade, BigDecimal preco, Long promocaoId,
                           BigDecimal valorFixo, boolean participaFaixa) {
        int sequencia = itens.isEmpty() ? 1 : itens.get(itens.size() - 1).sequencia() + 1;
        ItemCarrinho item = new ItemCarrinho(sequencia, produto, quantidade, preco, promocaoId, valorFixo,
                participaFaixa);
        if (item.valorBruto().compareTo(LIMITE_VALOR) >= 0
                || valorBruto().add(item.valorBruto()).compareTo(LIMITE_VALOR) >= 0) {
            throw new NegocioException("O valor da venda passaria do limite permitido. Confira a quantidade.");
        }
        itens.add(item);
        return item;
    }

    /** Guarda as faixas de preço por quantidade do produto (F20.2), lidas na inclusão do item. */
    void definirFaixas(long produtoId, List<FaixaPreco> faixasDoProduto) {
        faixas.put(produtoId, List.copyOf(faixasDoProduto));
    }

    /**
     * Preço por quantidade (F20.2): soma a quantidade dos itens não cancelados do produto que participam das faixas e
     * põe todos eles no preço da maior faixa alcançada, se for menor que o preço vigente do item (normal ou
     * promoção); abaixo da menor faixa, voltam ao preço vigente. Item que muda de preço mantém o desconto se ele
     * couber no novo valor; senão, o desconto é retirado. O desconto da venda maior que o novo total também é
     * retirado.
     *
     * @return avisos para a tela (descontos retirados); vazio se nada foi retirado
     */
    List<String> aplicarFaixas(long produtoId) {
        List<FaixaPreco> doProduto = faixas.getOrDefault(produtoId, List.of());
        List<ItemCarrinho> participantes = ativos()
                .filter(i -> i.produto().id() == produtoId && i.participaFaixa()).toList();
        BigDecimal quantidade = participantes.stream().map(ItemCarrinho::quantidade)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        FaixaPreco alcancada = null;
        for (FaixaPreco f : doProduto) {
            if (quantidade.compareTo(f.quantidadeMinima()) >= 0
                    && (alcancada == null || f.quantidadeMinima().compareTo(alcancada.quantidadeMinima()) > 0)) {
                alcancada = f;
            }
        }
        List<String> avisos = new ArrayList<>();
        for (ItemCarrinho item : participantes) {
            FaixaPreco nova = alcancada != null && alcancada.preco().compareTo(item.precoVigente()) < 0
                    ? alcancada : null;
            if (java.util.Objects.equals(nova, item.faixa())) {
                continue;
            }
            item.definirFaixa(nova);
            if (item.desconto().compareTo(item.valorBruto()) > 0) {
                avisos.add("O desconto do item " + item.sequencia() + " (" + Formatos.moeda(item.desconto())
                        + ") foi retirado porque ficou maior que o novo valor do item ("
                        + Formatos.moeda(item.valorBruto()) + "). Se for o caso, aplique de novo (F4).");
                item.definirDesconto(Dinheiro.ZERO);
            }
        }
        if (descontoVenda.compareTo(baseDescontoVenda()) > 0) {
            descontoVenda = Dinheiro.ZERO;
            avisos.add(DESCONTO_VENDA_RETIRADO);
        }
        return avisos;
    }

    /** Desconto do item em reais (0 remove): até o valor do item, sem deixar o desconto da venda maior que a base. */
    void definirDescontoItem(int sequencia, BigDecimal valor) {
        ItemCarrinho item = itemAtivo(sequencia);
        if (valor.signum() < 0) {
            throw new NegocioException("O desconto não pode ser negativo.");
        }
        if (valor.compareTo(item.valorBruto()) > 0) {
            throw new NegocioException("O desconto não pode passar do valor do item (" + Formatos.moeda(item.valorBruto())
                    + ").");
        }
        BigDecimal anterior = item.desconto();
        item.definirDesconto(valor);
        if (descontoVenda.compareTo(baseDescontoVenda()) > 0) {
            item.definirDesconto(anterior);
            throw new NegocioException("Com este desconto no item, o desconto da venda ficaria maior que o total. "
                    + "Reduza o desconto da venda (F5) antes.");
        }
    }

    /** Desconto da venda em reais (0 remove): até o total depois dos descontos dos itens. */
    void definirDescontoVenda(BigDecimal valor) {
        if (!temItensAtivos()) {
            throw new NegocioException("Registre ao menos um item antes de dar desconto.");
        }
        if (valor.signum() < 0) {
            throw new NegocioException("O desconto não pode ser negativo.");
        }
        if (valor.compareTo(baseDescontoVenda()) > 0) {
            throw new NegocioException("O desconto não pode passar do total da venda ("
                    + Formatos.moeda(baseDescontoVenda()) + ").");
        }
        descontoVenda = Dinheiro.dinheiro(valor);
    }

    /**
     * Cancela o item: fica riscado, sem desconto, e sai do total.
     *
     * @return true se o desconto da venda passou a ser maior que o total e foi removido
     */
    boolean cancelar(int sequencia, ItemCarrinho.Cancelamento dados) {
        itemAtivo(sequencia).cancelar(dados);
        if (descontoVenda.compareTo(baseDescontoVenda()) > 0) {
            descontoVenda = Dinheiro.ZERO;
            return true;
        }
        return false;
    }

    /** Guarda a liberação do desconto atual (vale enquanto o desconto não ficar proporcionalmente maior). */
    void registrarLiberacaoDesconto(Liberacao liberacao) {
        this.liberacaoDesconto = liberacao;
        this.fracaoLiberada = fracaoDesconto();
    }

    /** @param novo nulo remove a identificação */
    void definirCliente(ClienteVenda novo) {
        this.cliente = novo;
    }

    void limpar() {
        itens.clear();
        faixas.clear();
        descontoVenda = Dinheiro.ZERO;
        liberacaoDesconto = null;
        fracaoLiberada = null;
        cliente = null;
    }

    ItemCarrinho itemAtivo(int sequencia) {
        ItemCarrinho item = item(sequencia).orElseThrow(() -> new NegocioException("Item " + sequencia
                + " não encontrado na venda."));
        if (item.cancelado()) {
            throw new NegocioException("O item " + sequencia + " já foi cancelado.");
        }
        return item;
    }

    private BigDecimal fracaoDesconto() {
        BigDecimal bruto = valorBruto();
        return bruto.signum() == 0 ? BigDecimal.ZERO
                : descontos().divide(bruto, ESCALA_FRACAO, Dinheiro.ARREDONDAMENTO);
    }

    private Stream<ItemCarrinho> ativos() {
        return itens.stream().filter(i -> !i.cancelado());
    }
}
