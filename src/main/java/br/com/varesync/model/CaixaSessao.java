package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Sessão de caixa de um terminal ({@code caixa_sessao}). Os campos de fechamento são nulos enquanto está aberta;
 * {@code diferenca} = contado − esperado (coluna gerada). {@code contaOrigemId} preenchida = sessão integrada às contas
 * financeiras (F15.1).
 */
public record CaixaSessao(long id, String terminal, StatusCaixa status, long abertoPorId, String abertoPorNome,
                          OffsetDateTime abertoEm, BigDecimal valorAbertura, String fechadoPorNome,
                          OffsetDateTime fechadoEm, BigDecimal valorEsperado, BigDecimal valorContado,
                          BigDecimal diferenca, Long contaOrigemId, Long contaDestinoId) {

    public CaixaSessao(long id, String terminal, StatusCaixa status, long abertoPorId, String abertoPorNome,
                       OffsetDateTime abertoEm, BigDecimal valorAbertura, String fechadoPorNome,
                       OffsetDateTime fechadoEm, BigDecimal valorEsperado, BigDecimal valorContado,
                       BigDecimal diferenca) {
        this(id, terminal, status, abertoPorId, abertoPorNome, abertoEm, valorAbertura, fechadoPorNome, fechadoEm,
                valorEsperado, valorContado, diferenca, null, null);
    }

    public boolean aberta() {
        return status == StatusCaixa.ABERTO;
    }

    /** A sessão foi aberta com a integração ligada: sangria, suprimento e fechamento movimentam as contas. */
    public boolean integrada() {
        return contaOrigemId != null;
    }
}
