package br.com.varesync.service;

import br.com.varesync.model.Usuario;
import java.util.Optional;

/**
 * Situação atual do acesso no banco, para a {@link Sessao} conferir a cada operação (F10.1). Implementada pelo
 * {@link AcessoService}, que avisa a interface quando a sessão precisa ser trocada ou encerrada.
 */
public interface ConferenciaAcesso {

    /** Usuário como está no banco agora (ativo, supervisor, troca obrigatória, versão e permissões). */
    Optional<Usuario> ler(long usuarioId);

    /** A versão do acesso mudou: a interface troca a sessão por esta, com as permissões atuais, e refaz o menu. */
    void acessoAlterado(Sessao nova);

    /** Usuário inativado ou com a senha redefinida: a interface faz o log off. */
    void acessoEncerrado(Sessao sessao);
}
