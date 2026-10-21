package br.com.varesync.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Entrada confirmada: o cabeçalho já {@code CONFIRMADA}, o custo médio de cada produto (na ordem do produto) e os
 * produtos que ficaram abaixo da margem mínima, com o preço sugerido.
 */
public record ResultadoEntrada(EntradaResumo entrada, List<CustoAtualizado> custos,
                               List<SugestaoReajuste> sugestoes, BigDecimal margemMinimaPct) {
}
