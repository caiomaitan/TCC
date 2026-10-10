package br.com.varesync.model;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Usuário do sistema, sem o hash da senha (que só circula entre {@code UsuarioDAO} e o service de acesso).
 * {@code permissoes} são as gravadas em {@code usuario_permissao}; supervisor tem todas mesmo sem gravá-las.
 */
public final class Usuario {

    private final long id;
    private final String login;
    private final String nome;
    private final boolean supervisor;
    private final boolean ativo;
    private final boolean deveTrocarSenha;
    private final int tentativasFalhas;
    private final OffsetDateTime bloqueadoAte;
    private final OffsetDateTime ultimoLoginEm;
    private final Set<Permissao> permissoes;

    public Usuario(long id, String login, String nome, boolean supervisor, boolean ativo, boolean deveTrocarSenha,
                   int tentativasFalhas, OffsetDateTime bloqueadoAte, OffsetDateTime ultimoLoginEm,
                   Set<Permissao> permissoes) {
        this.id = id;
        this.login = login;
        this.nome = nome;
        this.supervisor = supervisor;
        this.ativo = ativo;
        this.deveTrocarSenha = deveTrocarSenha;
        this.tentativasFalhas = tentativasFalhas;
        this.bloqueadoAte = bloqueadoAte;
        this.ultimoLoginEm = ultimoLoginEm;
        EnumSet<Permissao> copia = EnumSet.noneOf(Permissao.class);
        copia.addAll(permissoes);
        this.permissoes = Collections.unmodifiableSet(copia);
    }

    public long id() {
        return id;
    }

    public String login() {
        return login;
    }

    public String nome() {
        return nome;
    }

    public boolean supervisor() {
        return supervisor;
    }

    public boolean ativo() {
        return ativo;
    }

    public boolean deveTrocarSenha() {
        return deveTrocarSenha;
    }

    public int tentativasFalhas() {
        return tentativasFalhas;
    }

    /** Fim do bloqueio por tentativas; nulo se nunca bloqueado ou já desbloqueado. */
    public OffsetDateTime bloqueadoAte() {
        return bloqueadoAte;
    }

    public OffsetDateTime ultimoLoginEm() {
        return ultimoLoginEm;
    }

    public Set<Permissao> permissoes() {
        return permissoes;
    }

    public boolean bloqueadoEm(OffsetDateTime instante) {
        return bloqueadoAte != null && bloqueadoAte.isAfter(instante);
    }

    /** "Maria Souza (maria)". */
    public String descricao() {
        return nome + " (" + login + ")";
    }

    @Override
    public String toString() {
        return "Usuario[id=" + id + ", login=" + login + "]";
    }
}
