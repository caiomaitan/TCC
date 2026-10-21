package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Promoção (6.8): preço promocional de um produto num período (início e fim inclusive). Traz o preço de venda e o
 * custo atuais do produto para a lista mostrar o desconto e o prejuízo.
 */
public record Promocao(long id, long produtoId, String produtoNome, String unidade, BigDecimal precoVenda,
                       BigDecimal custo, BigDecimal precoPromocional, LocalDate inicio, LocalDate fim, boolean ativa,
                       String criadoPor, OffsetDateTime criadoEm) {

    /** "Vigente", "Futura", "Encerrada" ou "Desativada", na data dada. */
    public String situacao(LocalDate hoje) {
        if (!ativa) {
            return "Desativada";
        }
        if (fim.isBefore(hoje)) {
            return "Encerrada";
        }
        return inicio.isAfter(hoje) ? "Futura" : "Vigente";
    }

    /** O período contém a data? (sem olhar se está ativa) */
    public boolean contem(LocalDate data) {
        return !data.isBefore(inicio) && !data.isAfter(fim);
    }
}
