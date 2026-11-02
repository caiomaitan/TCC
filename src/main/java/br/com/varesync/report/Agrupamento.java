package br.com.varesync.report;

import br.com.varesync.util.Formatos;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/** Visão do fluxo de caixa (F15.4): por dia, por semana (de segunda a domingo) ou por mês. */
public enum Agrupamento {
    DIA("Dia"),
    SEMANA("Semana"),
    MES("Mês");

    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter MES_ANO = DateTimeFormatter.ofPattern("MM/uuuu");

    private final String rotulo;

    Agrupamento(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    /** Primeiro dia do grupo de {@code dia}, sem passar de {@code limite} (o início do período). */
    public LocalDate inicioDoGrupo(LocalDate dia, LocalDate limite) {
        LocalDate inicio = switch (this) {
            case DIA -> dia;
            case SEMANA -> dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MES -> dia.withDayOfMonth(1);
        };
        return inicio.isBefore(limite) ? limite : inicio;
    }

    /** "02/11/2026", "Semana de 02/11" ou "11/2026". */
    public String rotuloDoGrupo(LocalDate inicio) {
        return switch (this) {
            case DIA -> Formatos.data(inicio);
            case SEMANA -> "Semana de " + DIA_MES.format(inicio);
            case MES -> MES_ANO.format(inicio);
        };
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
