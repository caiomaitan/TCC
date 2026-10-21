package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Dados da nota de uma entrada de mercadoria (6.10): fornecedor (obrigatório), número do documento, data de emissão e
 * valor total da nota (para conferir com a soma dos itens). Os opcionais são nulos quando não informados.
 */
public record DadosEntrada(Long fornecedorId, String numeroDocumento, LocalDate dataEmissao,
                           BigDecimal valorTotalNota) {
}
