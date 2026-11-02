package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Despesa avulsa como a tela informa (F15.3).
 *
 * @param competencia qualquer dia do mês a que a despesa pertence; nula = o mês do vencimento
 */
public record DadosTituloPagar(Long categoriaId, String descricao, LocalDate competencia, LocalDate vencimento,
                               Long fornecedorId, String documento, LocalDate emissao, BigDecimal valor) {
}
