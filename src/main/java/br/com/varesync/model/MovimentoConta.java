package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Linha do extrato da conta ({@code conta_movimento}). */
public record MovimentoConta(long id, OffsetDateTime dataHora, TipoContaMovimento tipo, BigDecimal valor,
                             BigDecimal saldoPosterior, String descricao, String usuario) {
}
