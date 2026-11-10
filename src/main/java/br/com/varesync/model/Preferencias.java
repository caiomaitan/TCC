package br.com.varesync.model;

import java.util.Objects;

/** Preferências de aparência de um usuário (F21.1). A tela de login usa sempre o {@link #PADRAO}. */
public record Preferencias(Tema tema, TamanhoFonte fonte) {

    public static final Preferencias PADRAO = new Preferencias(Tema.CLARO, TamanhoFonte.NORMAL);

    public Preferencias {
        Objects.requireNonNull(tema, "tema");
        Objects.requireNonNull(fonte, "fonte");
    }
}
