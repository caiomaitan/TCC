package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.dao.UsuarioDAO.Credencial;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Usuario;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Primeiro acesso, login com bloqueio, troca de senha, logout e liberação de supervisor (6.1, 6.2).
 */
public class AcessoService {

    public static final String FALHA_LOGIN = "Usuário ou senha inválidos.";
    public static final int MAXIMO_TENTATIVAS = 5;
    public static final Duration TEMPO_BLOQUEIO = Duration.ofMinutes(5);

    static final String CHAVE_EMPRESA_NOME = "empresa.nome";
    static final int TAMANHO_MAXIMO_NOME_LOJA = 80;

    /** Dados do assistente de primeiro acesso. */
    public record PrimeiroAcesso(String nomeLoja, String nome, String login, String senha, String confirmacao) {
        @Override
        public String toString() {
            return "PrimeiroAcesso[loja=" + nomeLoja + ", login=" + login + "]"; // sem a senha
        }
    }

    /** Resultado da conferência de credenciais: o usuário ou a mensagem de recusa. */
    private record Conferencia(Usuario usuario, String recusa) {
        static Conferencia recusada(String mensagem) {
            return new Conferencia(null, mensagem);
        }
    }

    private final Transacao transacao;
    private final UsuarioDAO usuarioDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final Senhas senhas;
    private final Clock clock;
    private final String terminal;

    public AcessoService(Transacao transacao, UsuarioDAO usuarioDAO, ConfiguracaoDAO configuracaoDAO,
                         AuditoriaService auditoria, Senhas senhas, Clock clock, String terminal) {
        this.transacao = transacao;
        this.usuarioDAO = usuarioDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
        this.senhas = senhas;
        this.clock = clock;
        this.terminal = terminal;
    }

    /** Tabela {@code usuario} vazia: abrir o assistente de primeiro acesso. */
    public boolean precisaPrimeiroAcesso() {
        return transacao.executar(con -> usuarioDAO.contar(con) == 0);
    }

    public String nomeEmpresa() {
        return transacao.executar(con -> configuracaoDAO.buscar(con, CHAVE_EMPRESA_NOME).orElse(""));
    }

    /**
     * Grava o nome da loja e cria o supervisor, sem troca de senha obrigatória (não existe senha padrão).
     *
     * @return id do supervisor criado
     */
    public long criarPrimeiroSupervisor(PrimeiroAcesso dados) {
        String nomeLoja = Textos.normalizarEspacos(dados.nomeLoja());
        if (nomeLoja.isEmpty()) {
            throw new NegocioException("Informe o nome da loja.");
        }
        if (nomeLoja.length() > TAMANHO_MAXIMO_NOME_LOJA) {
            throw new NegocioException("O nome da loja pode ter no máximo " + TAMANHO_MAXIMO_NOME_LOJA + " caracteres.");
        }
        String nome = ValidacaoUsuario.nome(dados.nome());
        String login = ValidacaoUsuario.login(dados.login());
        senhas.validarNova(login, dados.senha(), dados.confirmacao());
        String hash = senhas.gerarHash(dados.senha()); // fora da transação: o BCrypt é lento de propósito

        return transacao.executar(con -> {
            usuarioDAO.travarCadastro(con);
            if (usuarioDAO.contar(con) > 0) {
                throw new NegocioException("O primeiro acesso já foi feito. Entre com o seu usuário.");
            }
            configuracaoDAO.atualizar(con, CHAVE_EMPRESA_NOME, nomeLoja);
            long id = usuarioDAO.inserir(con, login, nome, hash, true, false, OffsetDateTime.now(clock));
            auditoria.registrar(con, id, AcaoAuditoria.USUARIO_CRIADO, AuditoriaService.ENTIDADE_USUARIO, id,
                    "Primeiro acesso: supervisor " + nome + " (" + login + ") criado; loja \"" + nomeLoja + "\"");
            return id;
        });
    }

    /**
     * Login (6.1). Toda tentativa é auditada; a falha é gravada antes de a exceção sair.
     *
     * @throws NegocioException "Usuário ou senha inválidos.", usuário bloqueado (com o horário) ou inativo
     */
    public Sessao entrar(String loginDigitado, String senha) {
        String login = Textos.normalizarLogin(loginDigitado);
        if (login.isEmpty() || senha == null || senha.isEmpty()) {
            throw new NegocioException("Informe o usuário e a senha.");
        }
        Conferencia conferencia = transacao.executar(con -> {
            Conferencia c = conferir(con, login, senha, "Login");
            if (c.usuario() != null) {
                OffsetDateTime agora = OffsetDateTime.now(clock);
                usuarioDAO.registrarLogin(con, c.usuario().id(), agora);
                auditoria.registrar(con, c.usuario().id(), AcaoAuditoria.LOGIN, AuditoriaService.ENTIDADE_USUARIO,
                        c.usuario().id(), "Entrou no sistema");
                return new Conferencia(recarregar(con, c.usuario().id()), null);
            }
            return c;
        });
        if (conferencia.usuario() == null) {
            throw new NegocioException(conferencia.recusa());
        }
        return new Sessao(conferencia.usuario(), terminal);
    }

    /**
     * Troca da própria senha (obrigatória quando {@code deve_trocar_senha}).
     *
     * @return nova sessão, já sem a troca obrigatória
     */
    public Sessao trocarSenha(Sessao sessao, String senhaAtual, String novaSenha, String confirmacao) {
        senhas.validarNova(sessao.usuario().login(), novaSenha, confirmacao);
        if (novaSenha.equals(senhaAtual)) {
            throw new NegocioException("A nova senha deve ser diferente da atual.");
        }
        String hash = senhas.gerarHash(novaSenha);
        Usuario atualizado = transacao.executar(con -> {
            Credencial credencial = usuarioDAO.buscarCredencialPorIdParaAtualizar(con, sessao.usuarioId())
                    .orElseThrow(() -> new NegocioException("Usuário não encontrado."));
            if (!senhas.confere(senhaAtual, credencial.senhaHash())) {
                throw new NegocioException("A senha atual está incorreta.");
            }
            usuarioDAO.alterarSenha(con, sessao.usuarioId(), hash, false, OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.SENHA_REDEFINIDA, AuditoriaService.ENTIDADE_USUARIO,
                    sessao.usuarioId(), "Senha trocada pelo próprio usuário");
            return recarregar(con, sessao.usuarioId());
        });
        return new Sessao(atualizado, sessao.terminal());
    }

    /** Log off ou saída do sistema. */
    public void sair(Sessao sessao) {
        transacao.executar(con -> {
            auditoria.registrar(con, sessao, AcaoAuditoria.LOGOUT, AuditoriaService.ENTIDADE_USUARIO,
                    sessao.usuarioId(), "Saiu do sistema");
            return null;
        });
    }

    /**
     * Liberação de supervisor (6.2): confere login e senha de quem tem a permissão.
     * Senha errada conta para o bloqueio, como no login. A ação liberada vale uma vez;
     * quem chama registra na auditoria dela quem liberou.
     *
     * @throws NegocioException credencial inválida ou usuário sem a permissão
     */
    public Liberacao liberar(String loginDigitado, String senha, Permissao permissao) {
        String login = Textos.normalizarLogin(loginDigitado);
        if (login.isEmpty() || senha == null || senha.isEmpty()) {
            throw new NegocioException("Informe o usuário e a senha de quem vai liberar.");
        }
        Conferencia conferencia = transacao.executar(con -> conferir(con, login, senha, "Liberação"));
        Usuario autorizador = conferencia.usuario();
        if (autorizador == null) {
            throw new NegocioException(conferencia.recusa());
        }
        if (!new Sessao(autorizador, terminal).pode(permissao)) {
            throw new NegocioException("Este usuário não tem permissão para liberar esta ação ("
                    + permissao.rotulo() + ").");
        }
        return new Liberacao(autorizador.id(), autorizador.login(), autorizador.nome());
    }

    /**
     * Confere login e senha, contando falhas e bloqueando na quinta seguida.
     * Grava falhas e auditoria na transação recebida; não lança exceção na recusa, para que essa gravação persista.
     */
    private Conferencia conferir(Connection con, String login, String senha, String contexto) throws SQLException {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        Optional<Credencial> encontrada = usuarioDAO.buscarCredencialParaAtualizar(con, login);
        if (encontrada.isEmpty()) {
            senhas.simularConferencia(senha);
            // O texto digitado não é registrado: pode ser uma senha digitada no campo errado.
            auditoria.registrar(con, (Long) null, AcaoAuditoria.LOGIN_FALHOU, AuditoriaService.ENTIDADE_USUARIO,
                    null, contexto + ": login inexistente");
            return Conferencia.recusada(FALHA_LOGIN);
        }
        Usuario usuario = encontrada.get().usuario();
        long id = usuario.id();

        if (usuario.bloqueadoEm(agora)) {
            auditoria.registrar(con, id, AcaoAuditoria.LOGIN_FALHOU, AuditoriaService.ENTIDADE_USUARIO, id,
                    contexto + ": usuário bloqueado até " + hora(usuario.bloqueadoAte()));
            return Conferencia.recusada(mensagemBloqueio(usuario.bloqueadoAte()));
        }

        if (!senhas.confere(senha, encontrada.get().senhaHash())) {
            int tentativas = usuario.tentativasFalhas() + 1;
            if (tentativas >= MAXIMO_TENTATIVAS) {
                OffsetDateTime bloqueio = agora.plus(TEMPO_BLOQUEIO);
                usuarioDAO.registrarFalha(con, id, 0, bloqueio, agora);
                auditoria.registrar(con, id, AcaoAuditoria.LOGIN_FALHOU, AuditoriaService.ENTIDADE_USUARIO, id,
                        contexto + ": senha incorreta pela " + MAXIMO_TENTATIVAS + "ª vez seguida; bloqueado até "
                                + hora(bloqueio));
                return Conferencia.recusada(mensagemBloqueio(bloqueio));
            }
            usuarioDAO.registrarFalha(con, id, tentativas, null, agora);
            auditoria.registrar(con, id, AcaoAuditoria.LOGIN_FALHOU, AuditoriaService.ENTIDADE_USUARIO, id,
                    contexto + ": senha incorreta (tentativa " + tentativas + " de " + MAXIMO_TENTATIVAS + ")");
            return Conferencia.recusada(FALHA_LOGIN);
        }

        if (!usuario.ativo()) {
            auditoria.registrar(con, id, AcaoAuditoria.LOGIN_FALHOU, AuditoriaService.ENTIDADE_USUARIO, id,
                    contexto + ": usuário inativo");
            return Conferencia.recusada("Este usuário está inativo. Procure o supervisor.");
        }
        return new Conferencia(usuario, null);
    }

    private Usuario recarregar(Connection con, long id) throws SQLException {
        return usuarioDAO.buscarPorIdParaAtualizar(con, id)
                .orElseThrow(() -> new NegocioException("Usuário não encontrado."));
    }

    static String mensagemBloqueio(OffsetDateTime bloqueadoAte) {
        return "Usuário bloqueado por excesso de tentativas. Tente novamente às " + hora(bloqueadoAte) + ".";
    }

    /** Hora local do computador (o driver devolve TIMESTAMPTZ em UTC). */
    static String hora(OffsetDateTime instante) {
        return Formatos.hora(instante);
    }
}
