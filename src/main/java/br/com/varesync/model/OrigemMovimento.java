package br.com.varesync.model;

/**
 * Documento que originou um movimento de estoque. Cada tipo exige a sua origem ({@code ck_me_origem}):
 * venda e cancelamento → item de venda; inventário → item de inventário; entrada de compra → item da entrada;
 * ajustes e perdas → nenhuma (vale o motivo).
 */
public record OrigemMovimento(Long vendaItemId, Long inventarioItemId, Long entradaItemId) {

    public static final OrigemMovimento NENHUMA = new OrigemMovimento(null, null, null);

    public static OrigemMovimento vendaItem(long id) {
        return new OrigemMovimento(id, null, null);
    }

    public static OrigemMovimento inventarioItem(long id) {
        return new OrigemMovimento(null, id, null);
    }

    public static OrigemMovimento entradaItem(long id) {
        return new OrigemMovimento(null, null, id);
    }

    /** A origem é a que o tipo exige? */
    public boolean compativelCom(TipoMovimento tipo) {
        return switch (tipo) {
            case VENDA, CANCELAMENTO_VENDA -> vendaItemId != null && inventarioItemId == null && entradaItemId == null;
            case INVENTARIO -> inventarioItemId != null && vendaItemId == null && entradaItemId == null;
            case ENTRADA_COMPRA -> entradaItemId != null && vendaItemId == null && inventarioItemId == null;
            case AJUSTE_ENTRADA, AJUSTE_SAIDA, PERDA -> equals(NENHUMA);
        };
    }
}
