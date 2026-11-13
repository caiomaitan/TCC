package br.com.varesync.service;

import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Usuário logado neste terminal e o que ele pode fazer (4.6). Imutável: troca de senha gera uma nova sessão.
 * Os services validam com {@link #exigir(Permissao)}; a interface só esconde o que não pode.
 * A sessão de caixa não fica aqui: ela é do terminal e pode ser aberta ou fechada por outro login no mesmo
 * computador, então o {@code CaixaService} a lê do banco pelo {@link #terminal()}.
 *
 * <p>Sessão revalidada (F10.1): com uma {@link ConferenciaAcesso}, toda conferência de permissão olha também o
 * banco. Usuário inativo ou com a senha redefinida → {@link SessaoEncerradaException}; versão do acesso diferente da
 * lida no login → vale o que está no banco agora, e a interface é avisada para trocar a sessão; versão igual → vale
 * a própria sessão. Sem conferência (sessões montadas à mão nos testes), vale só o que a sessão guarda.
 */
public final class Sessao {

    private final Usuario usuario;
    private final String terminal;
    private final Set<Permissao> permissoes;
    private final ConferenciaAcesso conferencia;

    public Sessao(Usuario usuario, String terminal) {
        this(usuario, terminal, null);
    }

    /** @param conferencia nula = sem revalidação no banco */
    public Sessao(Usuario usuario, String terminal, ConferenciaAcesso conferencia) {
        this.usuario = usuario;
        this.terminal = terminal;
        this.conferencia = conferencia;
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

    /** Versão do acesso lida no login (ou na última troca de sessão). */
    public int versaoAcesso() {
        return usuario.versaoAcesso();
    }

    /** Permissões efetivas guardadas na sessão: todas para o supervisor. */
    public Set<Permissao> permissoes() {
        return permissoes;
    }

    /** Pela própria sessão, sem olhar o banco (a interface usa para esconder o que não pode). */
    public boolean pode(Permissao permissao) {
        return permissoes.contains(permissao);
    }

    /**
     * Sessão conferida com o banco (F10.1): esta mesma, se a versão do acesso não mudou; senão uma nova, com as
     * permissões atuais (a interface é avisada para trocá-la).
     *
     * @throws SessaoEncerradaException usuário inativo, inexistente ou com a senha redefinida pelo supervisor depois do
     *                                  login
     */
    public Sessao conferida() {
        if (conferencia == null) {
            return this;
        }
        Optional<Usuario> atual = conferencia.ler(usuario.id());
        // Senha redefinida pelo supervisor = a troca obrigatória apareceu depois do login. Uma sessão que já nasceu
        // com a troca pendente (só existe entre o login e a troca de senha) não é encerrada por isso.
        if (atual.isEmpty() || !atual.get().ativo() || (atual.get().deveTrocarSenha() && !usuario.deveTrocarSenha())) {
            conferencia.acessoEncerrado(this);
            throw new SessaoEncerradaException();
        }
        if (atual.get().versaoAcesso() == usuario.versaoAcesso()) {
            return this;
        }
        Sessao nova = new Sessao(atual.get(), terminal, conferencia);
        conferencia.acessoAlterado(nova);
        return nova;
    }

    /**
     * @return a sessão conferida com o banco, para as decisões seguintes da mesma operação
     * @throws PermissaoNegadaException se o usuário não tem a permissão
     */
    public Sessao exigir(Permissao permissao) {
        Sessao atual = conferida();
        atual.exigirNaSessao(permissao);
        return atual;
    }

    /**
     * Rotina que aceita qualquer uma de duas permissões (ex.: identificar o cliente na venda, PDV, ou no vale-troca da
     * devolução, DEVOLUCAO). Sem nenhuma, a mensagem cita a primeira.
     *
     * @return a sessão conferida com o banco
     */
    public Sessao exigirUma(Permissao primeira, Permissao segunda) {
        Sessao atual = conferida();
        if (!atual.pode(segunda)) {
            atual.exigirNaSessao(primeira);
        }
        return atual;
    }

    /**
     * Rotina que aceita qualquer uma das permissões (ex.: a segunda via do comprovante pela busca global, F21.3: PDV,
     * RELATORIOS ou DEVOLUCAO). Sem nenhuma, a mensagem cita a primeira.
     *
     * @return a sessão conferida com o banco
     */
    public Sessao exigirAlguma(Permissao primeira, Permissao... outras) {
        Sessao atual = conferida();
        for (Permissao outra : outras) {
            if (atual.pode(outra)) {
                return atual;
            }
        }
        atual.exigirNaSessao(primeira);
        return atual;
    }

    /**
     * Ação que exige a permissão ou a liberação de quem a tem (6.2): sangria, cancelamento, desconto acima do limite.
     * A liberação só vale se quem liberou continua ativo e com a permissão (F10.1), como {@code AcessoService.liberar}
     * conferiu ao concedê-la.
     *
     * @param liberacao nula quando o próprio usuário tem a permissão
     * @return a liberação que valeu, ou nula se o próprio usuário tem a permissão
     * @throws PermissaoNegadaException sem a permissão e sem liberação, ou com liberação dada para outra permissão
     */
    public Liberacao exigirOuLiberado(Permissao permissao, Liberacao liberacao) {
        Sessao atual = conferida();
        if (atual.pode(permissao)) {
            return null;
        }
        if (liberacao == null) {
            // Sem a permissão (conferido acima), exigirNaSessao lança PermissaoNegadaException. O throw abaixo deixa
            // isso explícito para quem lê (e para o SpotBugs, que via um null possível na linha seguinte).
            atual.exigirNaSessao(permissao);
            throw new IllegalStateException("A permissão " + permissao + " deveria ter sido recusada.");
        }
        if (liberacao.permissao() != permissao) {
            throw new PermissaoNegadaException("A liberação recebida não vale para " + permissao.rotulo() + ".");
        }
        if (conferencia != null) {
            boolean vale = conferencia.ler(liberacao.usuarioId())
                    .filter(Usuario::ativo)
                    .map(u -> new Sessao(u, terminal).pode(permissao))
                    .orElse(false);
            if (!vale) {
                throw new PermissaoNegadaException("A liberação de " + liberacao.nome() + " (" + liberacao.login()
                        + ") não vale mais: o acesso dessa pessoa foi alterado. Peça uma nova liberação.");
            }
        }
        return liberacao;
    }

    /**
     * Usuários, configurações, formas de pagamento, backup, auditoria, diagnóstico e dados de demonstração (6.2).
     *
     * @return a sessão conferida com o banco
     */
    public Sessao exigirSupervisor() {
        Sessao atual = conferida();
        if (!atual.supervisor()) {
            throw new PermissaoNegadaException("Somente o supervisor pode realizar esta ação.");
        }
        return atual;
    }

    private void exigirNaSessao(Permissao permissao) {
        if (!pode(permissao)) {
            throw new PermissaoNegadaException(
                    "Você não tem permissão para usar a rotina " + permissao.rotulo() + ". Procure o supervisor.");
        }
    }

    @Override
    public String toString() {
        return "Sessao[" + usuario + ", terminal=" + terminal + "]";
    }
}
