package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Venda finalizada encontrada na busca da tela Devoluções, com o que já foi devolvido dela. */
public record VendaDevolvivel(long id, OffsetDateTime dataHora, String operador, String cliente, BigDecimal total,
                              BigDecimal devolvido) {
}
