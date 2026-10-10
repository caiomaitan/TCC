package br.com.varesync.service;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Usuário logado neste terminal e o que ele pode fazer (4.6). Imutável: troca de senha gera uma nova sessão.
 * Os services validam com {@link #exigir(Permissao)}; a interface só esconde o que não pode.
 * A sessão de caixa aberta entra na Fase 4.
 */
public final class Sessao {

    private final Usuario usuario;
    private final String terminal;
    private final Set<Permissao> permissoes;

    public Sessao(Usuario usuario, String terminal) {
        this.usuario = usuario;
        this.terminal = terminal;
        Set<Permissao> efetivas = EnumSet.noneOf(Permissao.class);
        efetivas.addAll(usuario.supervisor() ? EnumSet.allOf(Permissao.class) : usuario.permissoes());
        this.permissoes = Collections.unmodifiableSet(efetivas);
    }

    public Usuario usuario() {
        return usuario;
    }

    public long usuarioId() {
        return usuario.id();
    }

    public String terminal() {
        return terminal;
    }

    public boolean supervisor() {
        return usuario.supervisor();
    }

    public boolean deveTrocarSenha() {
        return usuario.deveTrocarSenha();
    }

    /** Permissões efetivas: todas para o supervisor. */
    public Set<Permissao> permissoes() {
        return permissoes;
    }

    public boolean pode(Permissao permissao) {
        return permissoes.contains(permissao);
    }

    /** @throws PermissaoNegadaException se o usuário não tem a permissão */
    public void exigir(Permissao permissao) {
        if (!pode(permissao)) {
            throw new PermissaoNegadaException(
                    "Você não tem permissão para usar a rotina " + permissao.rotulo() + ". Procure o supervisor.");
        }
    }

    /** Usuários, configurações, formas de pagamento, backup, auditoria, diagnóstico e dados de demonstração (6.2). */
    public void exigirSupervisor() {
        if (!supervisor()) {
            throw new PermissaoNegadaException("Somente o supervisor pode realizar esta ação.");
        }
    }

    @Override
    public String toString() {
        return "Sessao[" + usuario + ", terminal=" + terminal + "]";
    }
}
