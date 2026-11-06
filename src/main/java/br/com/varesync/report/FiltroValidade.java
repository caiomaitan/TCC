package br.com.varesync.report;

/**
 * Filtro do R29 Validade (F17.3): lotes que vencem em até {@code dias} dias e, se marcado, os vencidos.
 *
 * @param dias nulo = o padrão ({@code validade.alerta_dias})
 */
public record FiltroValidade(Integer dias, boolean incluirVencidos) {

    public static final FiltroValidade VAZIO = new FiltroValidade(null, false);
}
