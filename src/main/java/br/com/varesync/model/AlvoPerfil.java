package br.com.varesync.model;

import java.util.List;

/**
 * A quem a aplicação em lote do perfil tributário vale (F19.2): os produtos escolhidos na lista, todos os produtos
 * de uma categoria (ativos e inativos) ou todos os que têm o NCM que começa pelos dígitos informados.
 */
public record AlvoPerfil(Tipo tipo, List<Long> produtos, Long categoriaId, String ncm) {

    public enum Tipo { SELECAO, CATEGORIA, NCM }

    public AlvoPerfil {
        produtos = produtos == null ? List.of() : List.copyOf(produtos);
    }

    public static AlvoPerfil selecao(List<Long> produtos) {
        return new AlvoPerfil(Tipo.SELECAO, produtos, null, null);
    }

    public static AlvoPerfil categoria(long categoriaId) {
        return new AlvoPerfil(Tipo.CATEGORIA, List.of(), categoriaId, null);
    }

    /** @param ncm NCM inteiro (8 dígitos) ou o começo dele (2 a 8 dígitos), com ou sem pontos */
    public static AlvoPerfil ncm(String ncm) {
        return new AlvoPerfil(Tipo.NCM, List.of(), null, ncm);
    }
}
