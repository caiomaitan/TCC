package br.com.varesync.service;

import br.com.varesync.model.Permissao;

/**
 * Autorização concedida por outro usuário para uma única ação (6.2), na permissão que foi conferida.
 * Quem usa confere a permissão e registra na auditoria da ação quem liberou ({@link #descricao()}).
 */
public record Liberacao(long usuarioId, String login, String nome, Permissao permissao) {

    /** "liberado por Maria Souza (maria)". */
    public String descricao() {
        return "liberado por " + nome + " (" + login + ")";
    }
}
