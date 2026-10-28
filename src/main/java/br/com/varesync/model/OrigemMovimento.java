package br.com.varesync.model;

/**
 * Documento que originou um movimento de estoque. Cada tipo exige a sua origem ({@code ck_me_origem}):
 * venda e cancelamento → item de venda; inventário → item de inventário; entrada de compra → item da entrada;
 * devolução de venda → item da devolução (F13); ajustes e perdas → nenhuma (vale o motivo).
 */
public record OrigemMovimento(Long vendaItemId, Long inventarioItemId, Long entradaItemId, Long devolucaoItemId) {

    public static final OrigemMovimento NENHUMA = new OrigemMovimento(null, null, null, null);

    public static OrigemMovimento vendaItem(long id) {
        return new OrigemMovimento(id, null, null, null);
    }

    public static OrigemMovimento inventarioItem(long id) {
        return new OrigemMovimento(null, id, null, null);
    }

    public static OrigemMovimento entradaItem(long id) {
        return new OrigemMovimento(null, null, id, null);
    }

    public static OrigemMovimento devolucaoItem(long id) {
        return new OrigemMovimento(null, null, null, id);
    }

    /** A origem é a que o tipo exige? */
    public boolean compativelCom(TipoMovimento tipo) {
        return switch (tipo) {
            case VENDA, CANCELAMENTO_VENDA -> vendaItemId != null && equals(vendaItem(vendaItemId));
            case INVENTARIO -> inventarioItemId != null && equals(inventarioItem(inventarioItemId));
            case ENTRADA_COMPRA -> entradaItemId != null && equals(entradaItem(entradaItemId));
            case DEVOLUCAO_VENDA -> devolucaoItemId != null && equals(devolucaoItem(devolucaoItemId));
            case AJUSTE_ENTRADA, AJUSTE_SAIDA, PERDA -> equals(NENHUMA);
        };
    }
}
