package br.com.varesync.model;

/** Categoria de produto. Em uso não é excluída: é inativada (6.3). */
public record Categoria(long id, String nome, boolean ativa) {

    @Override
    public String toString() {
        return nome; // exibido nas listas de escolha
    }
}
