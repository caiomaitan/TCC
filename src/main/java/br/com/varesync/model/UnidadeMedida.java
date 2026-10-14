package br.com.varesync.model;

/** Unidade de medida (V2, só leitura). Fracionada aceita quantidade com casas decimais (KG, L, M). */
public record UnidadeMedida(String sigla, String descricao, boolean fracionada) {

    public static final String PADRAO = "UN";

    @Override
    public String toString() {
        return sigla + " — " + descricao; // exibido nas listas de escolha
    }
}
