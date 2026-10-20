package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Um ponto do gráfico do painel (7.5): faturamento e lucro bruto do dia; zero nos dias sem venda. */
public record DiaPainel(LocalDate dia, BigDecimal receita, BigDecimal lucro) {
}
