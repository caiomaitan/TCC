package br.com.varesync.ui;

import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import javafx.util.StringConverter;

/**
 * Datas dos {@code DatePicker} em dd/MM/aaaa, independentemente do idioma do computador. Data impossível (31/02)
 * é inválida, e não ajustada para o último dia do mês.
 */
public final class ConversorData extends StringConverter<LocalDate> {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/uuuu", Formatos.PT_BR)
            .withResolverStyle(ResolverStyle.STRICT);

    @Override
    public String toString(LocalDate data) {
        return data == null ? "" : DATA.format(data);
    }

    /** Texto inválido vira nulo (o service pede a data). */
    @Override
    public LocalDate fromString(String texto) {
        if (Textos.vazio(texto)) {
            return null;
        }
        try {
            return LocalDate.parse(texto.strip(), DATA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
