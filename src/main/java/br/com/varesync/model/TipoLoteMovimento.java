package br.com.varesync.model;

/** Tipos de {@code lote_movimento.tipo} (mesmos valores de {@code ck_lm_tipo}). */
public enum TipoLoteMovimento {
    /** Parte de um movimento de estoque. */
    MOVIMENTO,
    /** Diferença lançada ao ligar o controle de validade. */
    SALDO_INICIAL,
    /** Uma das duas pernas de uma transferência entre lotes. */
    TRANSFERENCIA
}
