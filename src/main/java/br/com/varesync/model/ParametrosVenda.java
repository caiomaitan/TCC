package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Parâmetros de venda da tabela {@code configuracao} (V2): {@code venda.permitir_estoque_negativo},
 * {@code venda.desconto_maximo_operador_pct} e {@code venda.cancelar_item_exige_supervisor}.
 */
public record ParametrosVenda(boolean permitirEstoqueNegativo, BigDecimal descontoMaximoPct,
                              boolean cancelarItemExigeSupervisor) {

    /** Valores da V2, usados quando a chave falta ou tem valor inválido. */
    public static final ParametrosVenda PADRAO = new ParametrosVenda(true, new BigDecimal("5"), false);
}
