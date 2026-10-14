package br.com.varesync.model;

import java.util.Arrays;
import java.util.Optional;

/** Motivos de perda (6.4), gravados como "Vencimento: observação". "Outros" exige a observação. */
public enum MotivoPerda {
    VENCIMENTO("Vencimento"),
    AVARIA("Avaria"),
    FURTO_DESVIO("Furto/desvio"),
    CONSUMO_INTERNO("Consumo interno"),
    OUTROS("Outros");

    private final String rotulo;

    MotivoPerda(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    /** Pelo rótulo, sem diferenciar maiúsculas. */
    public static Optional<MotivoPerda> doRotulo(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String procurado = texto.strip();
        return Arrays.stream(values()).filter(m -> m.rotulo.equalsIgnoreCase(procurado)).findFirst();
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
