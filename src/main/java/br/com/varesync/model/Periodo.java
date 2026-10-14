package br.com.varesync.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Período de datas, com as duas pontas incluídas. Na consulta vira
 * {@code data_hora >= início 00:00 AND data_hora < (fim + 1 dia) 00:00} (7.2), no fuso informado.
 */
public record Periodo(LocalDate inicio, LocalDate fim) {

    public OffsetDateTime inicioEm(ZoneId zona) {
        return inicio.atStartOfDay(zona).toOffsetDateTime();
    }

    /** Primeiro instante depois do período (exclusivo). */
    public OffsetDateTime fimExclusivoEm(ZoneId zona) {
        return fim.plusDays(1).atStartOfDay(zona).toOffsetDateTime();
    }
}
