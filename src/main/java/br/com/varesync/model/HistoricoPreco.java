package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Linha de {@code produto_historico_preco}: cadastro e cada mudança de preço ou custo (gravada por trigger). */
public record HistoricoPreco(OffsetDateTime criadoEm, String usuarioNome, BigDecimal precoAnterior,
                             BigDecimal precoNovo, BigDecimal custoAnterior, BigDecimal custoNovo) {
}
