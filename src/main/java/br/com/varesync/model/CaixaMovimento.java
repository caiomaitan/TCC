package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Sangria ou suprimento registrado na sessão de caixa ({@code caixa_movimento}). */
public record CaixaMovimento(long id, TipoCaixaMovimento tipo, BigDecimal valor, String motivo, String usuarioNome,
                             OffsetDateTime criadoEm) {
}
