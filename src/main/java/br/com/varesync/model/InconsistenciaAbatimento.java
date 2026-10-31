package br.com.varesync.model;

import java.math.BigDecimal;

/** Linha de {@code vw_abatimento_inconsistente} (F14.4): título ou devolução cujo abatido difere da soma das linhas. */
public record InconsistenciaAbatimento(String origem, long id, BigDecimal valorAbatido, BigDecimal somaLinhas) {

    public BigDecimal diferenca() {
        return valorAbatido.subtract(somaLinhas);
    }

    public String descricao() {
        return ("TITULO".equals(origem) ? "Título nº " : "Devolução nº ") + id;
    }
}
