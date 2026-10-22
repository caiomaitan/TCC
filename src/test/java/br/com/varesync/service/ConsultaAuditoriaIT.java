package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RegistroAuditoria;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Consulta da auditoria (6.13): período, usuário e ação, mais recentes primeiro, limite e só para supervisor. */
class ConsultaAuditoriaIT extends IntegracaoBase {

    private AcessoTeste t;
    private ConsultaAuditoriaService consulta;
    private Sessao admin;
    private long operadorId;
    private LocalDate hoje;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        consulta = t.consultaAuditoria;
        admin = t.supervisor(); // USUARIO_CRIADO e LOGIN de hoje
        hoje = LocalDate.now(t.relogio);
        operadorId = t.usuarios.criar(admin, "caixa1", "Operador Um", false, EnumSet.of(Permissao.PDV), "inicial1",
                "inicial1");
        t.relogio.avancar(Duration.ofDays(1));
        assertThatThrownBy(() -> t.acesso.entrar("caixa1", "errada1")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> t.acesso.entrar("ninguem", "errada1")).isInstanceOf(NegocioException.class);
        t.acesso.entrar("caixa1", "inicial1");
    }

    private List<RegistroAuditoria> consultar(LocalDate inicio, LocalDate fim, Long usuarioId, AcaoAuditoria acao) {
        return consulta.consultar(admin, new Periodo(inicio, fim), usuarioId, acao).registros();
    }

    @Test
    void filtraPorPeriodoUsuarioEAcaoDoMaisRecenteParaOMaisAntigo() {
        List<RegistroAuditoria> ontem = consultar(hoje, hoje, null, null);
        assertThat(ontem).extracting(RegistroAuditoria::acao)
                .containsExactly(AcaoAuditoria.USUARIO_CRIADO, AcaoAuditoria.LOGIN, AcaoAuditoria.USUARIO_CRIADO);
        assertThat(ontem.get(0).usuario()).isEqualTo("Ana Admin (admin)");
        assertThat(ontem.get(0).terminal()).isEqualTo(AcessoTeste.TERMINAL);

        List<RegistroAuditoria> doOperador = consultar(hoje, hoje.plusDays(1), operadorId, null);
        assertThat(doOperador).extracting(RegistroAuditoria::acao)
                .containsExactly(AcaoAuditoria.LOGIN, AcaoAuditoria.LOGIN_FALHOU);

        List<RegistroAuditoria> falhas = consultar(hoje, hoje.plusDays(1), null, AcaoAuditoria.LOGIN_FALHOU);
        assertThat(falhas).hasSize(2);
        assertThat(falhas).extracting(RegistroAuditoria::usuario).containsExactly(null, "Operador Um (caixa1)");
        assertThat(falhas.get(1).criadoEm()).isBefore(falhas.get(0).criadoEm().plusSeconds(1));

        assertThat(consultar(hoje.plusDays(2), hoje.plusDays(3), null, null)).isEmpty();
    }

    @Test
    void periodoObrigatorioEInicioAntesDoFim() {
        assertThatThrownBy(() -> consulta.consultar(admin, new Periodo(null, hoje), null, null))
                .hasMessage("Informe a data inicial e a final do período.");
        assertThatThrownBy(() -> consulta.consultar(admin, new Periodo(hoje, hoje.minusDays(1)), null, null))
                .hasMessage("A data inicial é depois da final.");
        assertThat(consulta.periodoPadrao()).isEqualTo(new Periodo(hoje.minusDays(5), hoje.plusDays(1)));
    }

    @Test
    void soSupervisorConsulta() {
        t.usuarios.criar(admin, "gerente", "Gerente", false, EnumSet.allOf(Permissao.class), "inicial1", "inicial1");
        Sessao gerente = t.acesso.entrar("gerente", "inicial1");
        assertThatThrownBy(() -> consulta.consultar(gerente, new Periodo(hoje, hoje), null, null))
                .isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void limitaAsLinhasEAvisa() throws Exception {
        AuditoriaDAO dao = new AuditoriaDAO();
        OffsetDateTime quando = OffsetDateTime.now(t.relogio).minusDays(3);
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            for (int i = 0; i < ConsultaAuditoriaService.LIMITE + 1; i++) {
                dao.inserir(con, admin.usuarioId(), AcaoAuditoria.PRODUTO_ALTERADO, "produto", (long) i,
                        "Alteração " + i, "CAIXA-TESTE", quando.plusSeconds(i));
            }
            con.commit();
        }
        ConsultaAuditoriaService.Consulta c = consulta.consultar(admin, new Periodo(hoje.minusDays(3), hoje),
                null, AcaoAuditoria.PRODUTO_ALTERADO);
        assertThat(c.limitado()).isTrue();
        assertThat(c.registros()).hasSize(ConsultaAuditoriaService.LIMITE);
        assertThat(c.registros().get(0).descricao()).isEqualTo("Alteração " + ConsultaAuditoriaService.LIMITE);
        assertThat(consulta.consultar(admin, new Periodo(hoje, hoje), null, null).limitado()).isFalse();
    }
}
