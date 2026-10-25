package br.com.varesync.model;

/**
 * Unidade de medida (as da V2 e as cadastradas, F10.5). Fracionada aceita quantidade com casas decimais (KG, L, M).
 */
public record UnidadeMedida(String sigla, String descricao, boolean fracionada) {

    public static final String PADRAO = "UN";

    @Override
    public String toString() {
        return sigla + " — " + descricao; // exibido nas listas de escolha
    }
}
