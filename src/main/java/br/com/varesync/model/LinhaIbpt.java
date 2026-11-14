package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Linha de NCM (tipo 0) da tabela do IBPT, "De Olho no Imposto" (F22.3). Alíquotas em %.
 *
 * @param ex exceção da TIPI; vazio = a linha usada nos tributos aproximados
 */
public record LinhaIbpt(String codigo, String ex, String descricao, BigDecimal nacionalFederal,
                        BigDecimal importadosFederal, BigDecimal estadual, BigDecimal municipal,
                        LocalDate vigenciaInicio, LocalDate vigenciaFim, String chave, String versao, String fonte) {
}
