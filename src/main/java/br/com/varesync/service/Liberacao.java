package br.com.varesync.service;

/**
 * Autorização concedida por outro usuário para uma única ação (6.2).
 * Quem usa registra na auditoria da ação quem liberou ({@link #descricao()}).
 */
public record Liberacao(long usuarioId, String login, String nome) {

    /** "liberado por Maria Souza (maria)". */
    public String descricao() {
        return "liberado por " + nome + " (" + login + ")";
    }
}
