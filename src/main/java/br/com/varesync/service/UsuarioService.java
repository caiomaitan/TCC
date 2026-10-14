package br.com.varesync.service;

import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cadastro de usuários e permissões, exclusivo do supervisor (6.1, 6.2).
 * Proteções: ninguém inativa a si mesmo e sempre existe ao menos um supervisor ativo.
 */
public class UsuarioService {

    static final String SEM_SUPERVISOR = "Deve existir ao menos um supervisor ativo.";

    private final Transacao transacao;
    private final UsuarioDAO usuarioDAO;
    private final AuditoriaService auditoria;
    private final Senhas senhas;
    private final Clock clock;

    public UsuarioService(Transacao transacao, UsuarioDAO usuarioDAO, AuditoriaService auditoria, Senhas senhas,
                          Clock clock) {
        this.transacao = transacao;
        this.usuarioDAO = usuarioDAO;
        this.auditoria = auditoria;
        this.senhas = senhas;
        this.clock = clock;
    }

    public List<Usuario> listar(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(usuarioDAO::listar);
    }

    /**
     * Cria o usuário com senha provisória: ele terá de trocá-la no primeiro login.
     *
     * @return id do usuário criado
     */
    public long criar(Sessao sessao, String loginDigitado, String nomeDigitado, boolean supervisor,
                      Set<Permissao> permissoes, String senhaProvisoria, String confirmacao) {
        sessao.exigirSupervisor();
        String login = ValidacaoUsuario.login(loginDigitado);
        String nome = ValidacaoUsuario.nome(nomeDigitado);
        senhas.validarNova(login, senhaProvisoria, confirmacao);
        String hash = senhas.gerarHash(senhaProvisoria);

        return transacao.executar(con -> {
            long id = usuarioDAO.inserir(con, login, nome, hash, supervisor, true, OffsetDateTime.now(clock));
            usuarioDAO.definirPermissoes(con, id, permissoes);
            auditoria.registrar(con, sessao, AcaoAuditoria.USUARIO_CRIADO, AuditoriaService.ENTIDADE_USUARIO, id,
                    "Usuário " + nome + " (" + login + ") criado como " + perfil(supervisor)
                            + "; permissões: " + rotulos(permissoes));
            return id;
        });
    }

    /** Altera nome, supervisor e permissões. O login não muda depois de criado. */
    public void alterar(Sessao sessao, long id, String nomeDigitado, boolean supervisor, Set<Permissao> permissoes) {
        sessao.exigirSupervisor();
        String nome = ValidacaoUsuario.nome(nomeDigitado);

        transacao.executar(con -> {
            List<Long> supervisoresAtivos = usuarioDAO.travarSupervisoresAtivos(con);
            Usuario atual = buscar(con, id);
            if (atual.supervisor() && !supervisor) {
                if (id == sessao.usuarioId()) {
                    throw new NegocioException("Você não pode remover o seu próprio acesso de supervisor.");
                }
                if (atual.ativo() && supervisoresAtivos.size() <= 1) {
                    throw new NegocioException(SEM_SUPERVISOR);
                }
            }
            usuarioDAO.alterarCadastro(con, id, nome, supervisor, OffsetDateTime.now(clock));
            usuarioDAO.definirPermissoes(con, id, permissoes);

            List<String> mudancas = new ArrayList<>();
            if (!atual.nome().equals(nome)) {
                mudancas.add("nome alterado de \"" + atual.nome() + "\" para \"" + nome + "\"");
            }
            if (atual.supervisor() != supervisor) {
                mudancas.add(supervisor ? "passou a ser supervisor" : "deixou de ser supervisor");
            }
            if (!mudancas.isEmpty()) {
                auditoria.registrar(con, sessao, AcaoAuditoria.USUARIO_ALTERADO, AuditoriaService.ENTIDADE_USUARIO,
                        id, "Usuário " + atual.login() + ": " + String.join("; ", mudancas));
            }
            String diferenca = diferencaPermissoes(atual.permissoes(), permissoes);
            if (!diferenca.isEmpty()) {
                auditoria.registrar(con, sessao, AcaoAuditoria.PERMISSOES_ALTERADAS,
                        AuditoriaService.ENTIDADE_USUARIO, id, "Permissões de " + atual.login() + ": " + diferenca);
            }
            return null;
        });
    }

    /** Ativa ou inativa. Usuário inativo não entra no sistema. */
    public void alterarSituacao(Sessao sessao, long id, boolean ativo) {
        sessao.exigirSupervisor();
        if (!ativo && id == sessao.usuarioId()) {
            throw new NegocioException("Você não pode inativar o seu próprio usuário.");
        }
        transacao.executar(con -> {
            List<Long> supervisoresAtivos = usuarioDAO.travarSupervisoresAtivos(con);
            Usuario atual = buscar(con, id);
            if (atual.ativo() == ativo) {
                return null;
            }
            if (!ativo && atual.supervisor() && supervisoresAtivos.size() <= 1) {
                throw new NegocioException(SEM_SUPERVISOR);
            }
            usuarioDAO.alterarSituacao(con, id, ativo, OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.USUARIO_ALTERADO, AuditoriaService.ENTIDADE_USUARIO, id,
                    "Usuário " + atual.login() + (ativo ? " reativado" : " inativado"));
            return null;
        });
    }

    /** Define uma senha provisória: o usuário terá de trocá-la ao entrar. Também desbloqueia. */
    public void redefinirSenha(Sessao sessao, long id, String senhaProvisoria, String confirmacao) {
        sessao.exigirSupervisor();
        String login = transacao.executar(con -> buscar(con, id).login());
        senhas.validarNova(login, senhaProvisoria, confirmacao);
        String hash = senhas.gerarHash(senhaProvisoria);

        transacao.executar(con -> {
            Usuario atual = buscar(con, id);
            usuarioDAO.alterarSenha(con, id, hash, true, OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.SENHA_REDEFINIDA, AuditoriaService.ENTIDADE_USUARIO, id,
                    "Senha provisória definida para " + atual.login() + " pelo supervisor");
            return null;
        });
    }

    /** Libera o login antes do fim do bloqueio por tentativas. */
    public void desbloquear(Sessao sessao, long id) {
        sessao.exigirSupervisor();
        transacao.executar(con -> {
            Usuario atual = buscar(con, id);
            usuarioDAO.desbloquear(con, id, OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.USUARIO_DESBLOQUEADO, AuditoriaService.ENTIDADE_USUARIO,
                    id, "Usuário " + atual.login() + " desbloqueado");
            return null;
        });
    }

    private Usuario buscar(Connection con, long id) throws SQLException {
        return usuarioDAO.buscarPorIdParaAtualizar(con, id)
                .orElseThrow(() -> new NegocioException("Usuário não encontrado."));
    }

    static String perfil(boolean supervisor) {
        return supervisor ? "supervisor" : "operador";
    }

    /** "PDV, Estoque" na ordem do catálogo; "nenhuma" se vazio. */
    static String rotulos(Set<Permissao> permissoes) {
        if (permissoes.isEmpty()) {
            return "nenhuma";
        }
        return EnumSet.copyOf(permissoes).stream().map(Permissao::rotulo).collect(Collectors.joining(", "));
    }

    /** "incluídas: PDV; removidas: Relatórios" ou "" se não mudou. */
    static String diferencaPermissoes(Set<Permissao> antes, Set<Permissao> depois) {
        Set<Permissao> incluidas = EnumSet.noneOf(Permissao.class);
        incluidas.addAll(depois);
        incluidas.removeAll(antes);
        Set<Permissao> removidas = EnumSet.noneOf(Permissao.class);
        removidas.addAll(antes);
        removidas.removeAll(depois);

        List<String> partes = new ArrayList<>();
        if (!incluidas.isEmpty()) {
            partes.add("incluídas: " + rotulos(incluidas));
        }
        if (!removidas.isEmpty()) {
            partes.add("removidas: " + rotulos(removidas));
        }
        return String.join("; ", partes);
    }
}
