package br.com.varesync.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** Formatação para exibição no padrão pt-BR. Valores ausentes (divisão por zero) aparecem como "—". */
public final class Formatos {

    public static final Locale PT_BR = Locale.of("pt", "BR");
    public static final String SEM_VALOR = "—";

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR);
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", PT_BR);
    private static final DateTimeFormatter DATA_HORA_SEGUNDOS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", PT_BR);

    private Formatos() {
    }

    /** {@code R$ 1.234,56}; negativo como {@code -R$ 1,00}. */
    public static String moeda(BigDecimal valor) {
        if (valor == null) {
            return SEM_VALOR;
        }
        BigDecimal v = Dinheiro.dinheiro(valor);
        String numero = decimal(2).format(v.abs());
        return (v.signum() < 0 ? "-R$ " : "R$ ") + numero;
    }

    public static String moeda(Optional<BigDecimal> valor) {
        return valor.map(Formatos::moeda).orElse(SEM_VALOR);
    }

    /** Número com {@code casas} decimais fixas: {@code 1.234,560}. */
    public static String numero(BigDecimal valor, int casas) {
        if (valor == null) {
            return SEM_VALOR;
        }
        return decimal(casas).format(valor.setScale(casas, Dinheiro.ARREDONDAMENTO));
    }

    /** Quantidade: sem casas para unidade inteira; 3 casas para unidade fracionada. */
    public static String quantidade(BigDecimal valor, boolean fracionada) {
        return numero(valor, fracionada ? Dinheiro.ESCALA_QUANTIDADE : 0);
    }

    /** {@code 41,71%}. */
    public static String percentual(BigDecimal valor) {
        return valor == null ? SEM_VALOR : numero(valor, Dinheiro.ESCALA_PERCENTUAL) + "%";
    }

    public static String percentual(Optional<BigDecimal> valor) {
        return valor.map(Formatos::percentual).orElse(SEM_VALOR);
    }

    public static String data(LocalDate data) {
        return data == null ? SEM_VALOR : DATA.format(data);
    }

    /** Converte para o fuso do computador antes de formatar (o driver devolve TIMESTAMPTZ em UTC). */
    public static String dataHora(OffsetDateTime dataHora) {
        return dataHora == null ? SEM_VALOR : DATA_HORA.format(dataHora.atZoneSameInstant(ZoneId.systemDefault()));
    }

    public static String dataHoraComSegundos(OffsetDateTime dataHora) {
        return dataHora == null ? SEM_VALOR : DATA_HORA_SEGUNDOS.format(dataHora.atZoneSameInstant(ZoneId.systemDefault()));
    }

    private static DecimalFormat decimal(int casas) {
        DecimalFormat formato = new DecimalFormat(casas == 0 ? "#,##0" : "#,##0." + "0".repeat(casas),
                DecimalFormatSymbols.getInstance(PT_BR));
        formato.setRoundingMode(Dinheiro.ARREDONDAMENTO);
        return formato;
    }
}
