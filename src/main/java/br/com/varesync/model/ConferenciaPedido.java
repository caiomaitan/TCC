package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Conferência pedido × nota (F16.3): uma linha por produto do pedido ou da nota.
 *
 * @param pedido   o pedido vinculado à entrada
 * @param linhas   produtos do pedido (na ordem do pedido) e, depois, os que só estão na nota
 */
public record ConferenciaPedido(PedidoCompra pedido, List<Linha> linhas) {

    public ConferenciaPedido {
        linhas = List.copyOf(linhas);
    }

    /** Há quantidade ou custo divergente em alguma linha? */
    public boolean temDivergencia() {
        return linhas.stream().anyMatch(l -> l.quantidadeDivergente() || l.custoDivergente());
    }

    /**
     * @param pedida        quantidade do pedido; nula se o produto não está no pedido
     * @param recebidaAntes recebida no pedido por outras entradas (0 fora do pedido)
     * @param nestaNota     quantidade nesta entrada; nula se o produto não veio nela
     * @param custoPrevisto do pedido; nulo fora do pedido
     * @param custoNota     custo unitário da nota; nulo se não veio
     */
    public record Linha(long produtoId, String produto, String unidade, boolean fracionada, BigDecimal pedida,
                        BigDecimal recebidaAntes, BigDecimal nestaNota, BigDecimal custoPrevisto,
                        BigDecimal custoNota) {

        public static final String FORA_DO_PEDIDO = "Fora do pedido";
        public static final String NAO_VEIO = "Não veio nesta nota";
        public static final String ACIMA = "Acima do pedido";
        public static final String ABAIXO = "Abaixo do pendente";
        public static final String CONFERE = "Confere";

        public boolean foraDoPedido() {
            return pedida == null;
        }

        /** O que faltava receber antes desta nota (nunca negativo); nulo fora do pedido. */
        public BigDecimal pendenteAntes() {
            return pedida == null ? null : pedida.subtract(recebidaAntes).max(BigDecimal.ZERO);
        }

        /** O que fica faltando depois desta nota (nunca negativo); nulo fora do pedido. */
        public BigDecimal pendenteDepois() {
            if (pedida == null) {
                return null;
            }
            BigDecimal nota = nestaNota == null ? BigDecimal.ZERO : nestaNota;
            return pedida.subtract(recebidaAntes).subtract(nota).max(BigDecimal.ZERO);
        }

        /** Fora do pedido, não veio, ou veio diferente do que faltava receber. */
        public boolean quantidadeDivergente() {
            return foraDoPedido() || nestaNota == null || nestaNota.compareTo(pendenteAntes()) != 0;
        }

        /** Custo da nota diferente do previsto (só quando há os dois). */
        public boolean custoDivergente() {
            return custoPrevisto != null && custoNota != null && custoNota.compareTo(custoPrevisto) != 0;
        }

        public String situacao() {
            if (foraDoPedido()) {
                return FORA_DO_PEDIDO;
            }
            if (nestaNota == null) {
                return NAO_VEIO;
            }
            int c = nestaNota.compareTo(pendenteAntes());
            return c > 0 ? ACIMA : c < 0 ? ABAIXO : CONFERE;
        }
    }
}
