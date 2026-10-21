package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** O que a tela de promoções envia para gravar; {@code PromocaoService} valida. */
public record DadosPromocao(Long produtoId, BigDecimal precoPromocional, LocalDate inicio, LocalDate fim,
                            boolean ativa) {
}
