package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Parte de um movimento de estoque num lote (F17.2): um lote existente ({@code loteId}) ou um lote identificado pelo
 * código e pela validade (criado se ainda não existir; código vazio e validade nula = "SEM LOTE").
 *
 * @param quantidade sempre positiva; o sinal vem do movimento
 */
public record ParteLote(Long loteId, String codigo, LocalDate validade, BigDecimal quantidade) {

    public static ParteLote existente(long loteId, BigDecimal quantidade) {
        return new ParteLote(loteId, null, null, quantidade);
    }

    /** Lote pelo código (pode ser vazio) e pela validade (obrigatória, exceto no "SEM LOTE"). */
    public static ParteLote novo(String codigo, LocalDate validade, BigDecimal quantidade) {
        return new ParteLote(null, codigo == null ? "" : codigo, validade, quantidade);
    }

    public static ParteLote semLote(BigDecimal quantidade) {
        return new ParteLote(null, "", null, quantidade);
    }

    /** Mesmo lote com outra quantidade. */
    public ParteLote comQuantidade(BigDecimal outra) {
        return new ParteLote(loteId, codigo, validade, outra);
    }

    /** Código como é gravado: sem espaços nas pontas e em maiúsculas. */
    public static String normalizarCodigo(String codigo) {
        return codigo == null ? "" : codigo.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
