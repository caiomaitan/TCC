package br.com.varesync.model;

/** Linha do cadastro de unidades (F10.5): a unidade e quantos produtos a usam (ativos ou não). */
public record UnidadeCadastro(UnidadeMedida unidade, long produtos) {

    public boolean emUso() {
        return produtos > 0;
    }
}
