package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sessão de caixa de um terminal ({@code caixa_sessao}). Os campos de fechamento são nulos enquanto está aberta;
 * {@code diferenca} = contado − esperado (coluna gerada).
 */
public record CaixaSessao(long id, String terminal, StatusCaixa status, long abertoPorId, String abertoPorNome,
                          OffsetDateTime abertoEm, BigDecimal valorAbertura, String fechadoPorNome,
                          OffsetDateTime fechadoEm, BigDecimal valorEsperado, BigDecimal valorContado,
                          BigDecimal diferenca) {

    public boolean aberta() {
        return status == StatusCaixa.ABERTO;
    }
}
