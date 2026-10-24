package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.controller.ItemMenu;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.SituacaoProduto;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sessão revalidada (F10.1) e desbloqueio da tela (F10.2): versão do acesso pelos triggers da V4, permissão retirada
 * ou incluída em outro terminal, usuário inativado ou com a senha redefinida, liberação conferida na hora de usar e
 * a senha do desbloqueio contando para o bloqueio de 5 tentativas.
 */
class SessaoRevalidadaIT extends IntegracaoBase {

    private AcessoTeste t;
    private Sessao admin;
    private final List<Sessao> alteradas = new ArrayList<>();
    private final List<Sessao> encerradas = new ArrayList<>();

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        t.acesso.observarAcesso(alteradas::add, encerradas::add);
    }

    /** Operador já com a senha trocada no primeiro login, como fica a sessão da janela principal. */
    private Sessao operador(String login, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, login, "Operador " + login, false, conjunto, "inicial1", "inicial1");
        Sessao provisoria = t.acesso.entrar(login, "inicial1");
        return t.acesso.trocarSenha(provisoria, "inicial1", "definitiva1", "definitiva1");
    }

    private int versao(long usuarioId) throws Exception {
        return (int) t.contar("SELECT versao_acesso FROM usuario WHERE id = " + usuarioId);
    }

    private OffsetDateTime atualizadoEm(long usuarioId) throws Exception {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT atualizado_em FROM usuario WHERE id = ?")) {
            ps.setLong(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, OffsetDateTime.class);
            }
        }
    }

    private void permissoes(Sessao usuario, Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.alterar(admin, usuario.usuarioId(), usuario.usuario().nome(), false, conjunto);
    }

    // ----- Versão do acesso (trigger da V4) -----

    @Test
    void versaoMudaSoQuandoOAcessoMuda() throws Exception {
        Sessao maria = operador("maria", Permissao.PDV, Permissao.PRODUTOS);
        long id = maria.usuarioId();
        int v = versao(id);
        assertThat(maria.versaoAcesso()).as("a sessão guarda a versão do login").isEqualTo(v);

        t.usuarios.alterar(admin, id, "Maria Souza", false, EnumSet.of(Permissao.PDV, Permissao.PRODUTOS));
        assertThat(versao(id)).as("só o nome mudou").isEqualTo(v);

        t.relogio.avancar(Duration.ofMinutes(5));
        permissoes(maria, Permissao.PDV);
        assertThat(versao(id)).as("permissão excluída").isEqualTo(v + 1);
        assertThat(atualizadoEm(id).toInstant()).as("o horário continua o da aplicação (V3)")
                .isEqualTo(t.relogio.instant());

        permissoes(maria, Permissao.PDV, Permissao.ESTOQUE, Permissao.RELATORIOS);
        assertThat(versao(id)).as("duas permissões incluídas").isEqualTo(v + 3);

        t.usuarios.alterar(admin, id, "Maria Souza", true, EnumSet.of(Permissao.PDV));
        assertThat(versao(id)).as("virou supervisora e perdeu 2 permissões gravadas").isEqualTo(v + 6);

        t.usuarios.alterarSituacao(admin, id, false);
        t.usuarios.alterarSituacao(admin, id, true);
        assertThat(versao(id)).as("inativada e reativada").isEqualTo(v + 8);

        t.usuarios.redefinirSenha(admin, id, "provisoria2", "provisoria2");
        assertThat(versao(id)).as("senha e troca obrigatória no mesmo UPDATE").isEqualTo(v + 9);

        t.usuarios.desbloquear(admin, id);
        t.acesso.entrar("maria", "provisoria2");
        assertThat(versao(id)).as("desbloquear e entrar não mudam o acesso").isEqualTo(v + 9);
    }

    // ----- Critérios de aceite da F10 -----

    @Test
    void permissaoRetiradaEmOutroTerminalRecusaAProximaAcaoETrocaASessaoSemLogOff() {
        Sessao maria = operador("maria", Permissao.PDV, Permissao.PRODUTOS);
        assertThat(t.produtos.pesquisar(maria, "", null, SituacaoProduto.ATIVOS)).isEmpty();
        assertThat(ItemMenu.visiveis(maria)).contains(ItemMenu.PRODUTOS);

        permissoes(maria, Permissao.PDV); // o supervisor, em outro computador

        assertThatThrownBy(() -> t.produtos.pesquisar(maria, "", null, SituacaoProduto.ATIVOS))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Você não tem permissão para usar a rotina Produtos. Procure o supervisor.");
        assertThat(alteradas).as("a interface é avisada para trocar a sessão e refazer o menu").isNotEmpty();
        Sessao nova = alteradas.get(alteradas.size() - 1);
        assertThat(nova.usuarioId()).isEqualTo(maria.usuarioId());
        assertThat(nova.versaoAcesso()).isGreaterThan(maria.versaoAcesso());
        assertThat(nova.pode(Permissao.PRODUTOS)).isFalse();
        assertThat(ItemMenu.visiveis(nova)).doesNotContain(ItemMenu.PRODUTOS).contains(ItemMenu.PDV);
        assertThat(encerradas).as("sem log off").isEmpty();

        // As outras rotinas continuam, com a sessão antiga ou com a nova.
        assertThat(t.vendas.formasAtivas(maria)).isNotEmpty();
        assertThat(t.vendas.formasAtivas(nova)).isNotEmpty();
    }

    @Test
    void permissaoIncluidaValeNaProximaOperacaoDaSessaoAntiga() {
        Sessao maria = operador("maria", Permissao.PDV);
        assertThatThrownBy(() -> t.produtos.pesquisar(maria, "", null, SituacaoProduto.ATIVOS))
                .isInstanceOf(PermissaoNegadaException.class);

        permissoes(maria, Permissao.PDV, Permissao.PRODUTOS);

        assertThat(t.produtos.pesquisar(maria, "", null, SituacaoProduto.ATIVOS)).isEmpty();
        assertThat(maria.pode(Permissao.PRODUTOS)).as("a sessão continua imutável").isFalse();
        assertThat(alteradas.get(alteradas.size() - 1).pode(Permissao.PRODUTOS)).isTrue();
    }

    @Test
    void versaoIgualNaoAvisaNinguem() {
        Sessao maria = operador("maria", Permissao.PDV);
        t.vendas.formasAtivas(maria);
        t.vendas.formasAtivas(maria);
        assertThat(alteradas).isEmpty();
        assertThat(encerradas).isEmpty();
        assertThat(maria.conferida()).isSameAs(maria);
    }

    @Test
    void usuarioInativadoVaiParaOLoginNaProximaAcao() {
        Sessao maria = operador("maria", Permissao.PDV);
        t.usuarios.alterarSituacao(admin, maria.usuarioId(), false);

        assertThatThrownBy(() -> t.vendas.formasAtivas(maria))
                .isInstanceOf(SessaoEncerradaException.class)
                .hasMessage("Seu acesso foi alterado pelo supervisor. Entre novamente.");
        assertThat(encerradas).singleElement().extracting(Sessao::usuarioId).isEqualTo(maria.usuarioId());
    }

    @Test
    void senhaRedefinidaPeloSupervisorVaiParaOLoginNaProximaAcao() {
        Sessao maria = operador("maria", Permissao.PDV);
        t.usuarios.redefinirSenha(admin, maria.usuarioId(), "provisoria2", "provisoria2");

        assertThatThrownBy(() -> t.vendas.formasAtivas(maria)).isInstanceOf(SessaoEncerradaException.class);
        assertThat(encerradas).hasSize(1);
    }

    @Test
    void trocarAPropriaSenhaNaoEncerraASessao() {
        Sessao maria = operador("maria", Permissao.PDV);
        Sessao depois = t.acesso.trocarSenha(maria, "definitiva1", "outrasenha1", "outrasenha1");

        assertThat(t.vendas.formasAtivas(maria)).isNotEmpty(); // a sessão antiga vê a versão nova e segue
        assertThat(t.vendas.formasAtivas(depois)).isNotEmpty();
        assertThat(encerradas).isEmpty();
    }

    @Test
    void supervisorRebaixadoPerdeAsRotinasDeSupervisor() {
        Sessao gerente = operador("gerente", Permissao.PDV);
        t.usuarios.alterar(admin, gerente.usuarioId(), gerente.usuario().nome(), true, EnumSet.of(Permissao.PDV));
        assertThat(t.usuarios.listar(gerente)).as("virou supervisor: vale na próxima operação").isNotEmpty();

        t.usuarios.alterar(admin, gerente.usuarioId(), gerente.usuario().nome(), false, EnumSet.of(Permissao.PDV));
        assertThatThrownBy(() -> t.usuarios.listar(gerente)).isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("Somente o supervisor pode realizar esta ação.");
    }

    @Test
    void liberacaoConfereASituacaoAtualDeQuemLibera() {
        Sessao caixa = operador("caixa", Permissao.PDV);
        operador("gerente", Permissao.PDV, Permissao.CANCELAR_VENDA);
        long gerenteId = t.usuarios.listar(admin).stream().filter(u -> u.login().equals("gerente")).findFirst()
                .orElseThrow().id();
        Liberacao liberacao = t.acesso.liberar("gerente", "definitiva1", Permissao.CANCELAR_VENDA);
        assertThat(caixa.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao)).isEqualTo(liberacao);

        t.usuarios.alterar(admin, gerenteId, "Operador gerente", false, EnumSet.of(Permissao.PDV));
        assertThatThrownBy(() -> caixa.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao))
                .isInstanceOf(PermissaoNegadaException.class)
                .hasMessage("A liberação de Operador gerente (gerente) não vale mais: o acesso dessa pessoa foi "
                        + "alterado. Peça uma nova liberação.");

        t.usuarios.alterar(admin, gerenteId, "Operador gerente", false,
                EnumSet.of(Permissao.PDV, Permissao.CANCELAR_VENDA));
        t.usuarios.alterarSituacao(admin, gerenteId, false);
        assertThatThrownBy(() -> caixa.exigirOuLiberado(Permissao.CANCELAR_VENDA, liberacao))
                .isInstanceOf(PermissaoNegadaException.class).hasMessageContaining("não vale mais");
    }

    // ----- Desbloqueio da tela (F10.2) -----

    @Test
    void desbloqueioComASenhaCertaEQuintaSenhaErradaBloqueiaOUsuario() throws Exception {
        Sessao maria = operador("maria", Permissao.PDV);
        assertThat(t.acesso.desbloquear(maria, "definitiva1")).isSameAs(maria);

        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> t.acesso.desbloquear(maria, "errada")).hasMessage(AcessoService.FALHA_LOGIN);
        }
        assertThatThrownBy(() -> t.acesso.desbloquear(maria, "errada"))
                .hasMessageStartingWith("Usuário bloqueado por excesso de tentativas. Tente novamente às");
        assertThatThrownBy(() -> t.acesso.desbloquear(maria, "definitiva1"))
                .as("bloqueada, nem a senha certa entra").hasMessageStartingWith("Usuário bloqueado");
        assertThatThrownBy(() -> t.acesso.entrar("maria", "definitiva1")).hasMessageStartingWith("Usuário bloqueado");
        assertThat(t.auditoria(AcaoAuditoria.LOGIN_FALHOU)).filteredOn(d -> d.startsWith("Desbloqueio da tela"))
                .hasSize(6);

        t.relogio.avancar(AcessoService.TEMPO_BLOQUEIO.plusSeconds(1));
        assertThat(t.acesso.desbloquear(maria, "definitiva1")).isSameAs(maria);
        assertThatThrownBy(() -> t.acesso.desbloquear(maria, "")).hasMessage("Digite a sua senha.");
    }

    @Test
    void desbloqueioDeQuemFoiInativadoEncerraASessao() {
        Sessao maria = operador("maria", Permissao.PDV);
        t.usuarios.alterarSituacao(admin, maria.usuarioId(), false);
        assertThatThrownBy(() -> t.acesso.desbloquear(maria, "definitiva1"))
                .isInstanceOf(SessaoEncerradaException.class);
    }

    @Test
    void desbloqueioDevolveASessaoNovaSeOAcessoMudouEnquantoEstavaBloqueada() {
        Sessao maria = operador("maria", Permissao.PDV);
        permissoes(maria, Permissao.PDV, Permissao.ESTOQUE);
        Sessao depois = t.acesso.desbloquear(maria, "definitiva1");
        assertThat(depois).isNotSameAs(maria);
        assertThat(depois.pode(Permissao.ESTOQUE)).isTrue();
    }
}
