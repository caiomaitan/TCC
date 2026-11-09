package br.com.varesync.report;

import br.com.varesync.model.Configuracoes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * O que a consulta precisa além dos filtros: o fuso do período (7.2), a margem mínima
 * ({@code relatorio.margem_minima_pct}) que separa "baixa rentabilidade" de "ok", o dia de hoje (produtos sem giro) e
 * os parâmetros da sugestão de compra ({@code compras.*}, R26 e R28) e a loja (regime e UF, R30).
 */
public record ParametrosRelatorio(ZoneId zona, BigDecimal margemMinimaPct, LocalDate hoje,
                                  Configuracoes.Compras compras, Configuracoes.Loja loja) {

    public ParametrosRelatorio {
        loja = loja == null ? new Configuracoes.Loja("", "", "", "") : loja;
    }

    /** Com a loja sem os dados fiscais (Simples, sem UF). */
    public ParametrosRelatorio(ZoneId zona, BigDecimal margemMinimaPct, LocalDate hoje,
                               Configuracoes.Compras compras) {
        this(zona, margemMinimaPct, hoje, compras, null);
    }

    /** Com os parâmetros de compras padrão. */
    public ParametrosRelatorio(ZoneId zona, BigDecimal margemMinimaPct, LocalDate hoje) {
        this(zona, margemMinimaPct, hoje, Configuracoes.Compras.PADRAO);
    }
}
