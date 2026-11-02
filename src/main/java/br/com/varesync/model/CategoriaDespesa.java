package br.com.varesync.model;

/** Categoria do título a pagar ({@code categoria_despesa}). */
public record CategoriaDespesa(long id, String nome, GrupoDespesa grupo, boolean ativa) {

    @Override
    public String toString() {
        return nome;
    }
}
