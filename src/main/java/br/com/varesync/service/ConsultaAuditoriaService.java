package br.com.varesync.service;

import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.RegistroAuditoria;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Consulta da auditoria (6.13, só supervisor): período, usuário e ação, do mais recente para o mais antigo, com no
 * máximo {@link #LIMITE} linhas. Pode demorar com muitos registros: a tela chama em {@code Task}.
 */
public class ConsultaAuditoriaService {

    public static final int LIMITE = 5000;

    private final Transacao transacao;
    private final AuditoriaDAO dao;
    private final Clock clock;

    /** @param limitado havia mais registros no filtro do que os mostrados */
    public record Consulta(List<RegistroAuditoria> registros, boolean limitado) {
    }

    public ConsultaAuditoriaService(Transacao transacao, AuditoriaDAO dao, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.clock = clock;
    }

    /** Período inicial da tela: os últimos 7 dias contando hoje. */
    public Periodo periodoPadrao() {
        LocalDate hoje = LocalDate.now(clock);
        return new Periodo(hoje.minusDays(6), hoje);
    }

    /**
     * @param usuarioId nulo = todos
     * @param acao      nula = todas
     */
    public Consulta consultar(Sessao sessao, Periodo periodo, Long usuarioId, AcaoAuditoria acao) {
        sessao.exigirSupervisor();
        if (periodo == null || periodo.inicio() == null || periodo.fim() == null) {
            throw new NegocioException("Informe a data inicial e a final do período.");
        }
        if (periodo.inicio().isAfter(periodo.fim())) {
            throw new NegocioException("A data inicial é depois da final.");
        }
        AuditoriaDAO.Filtro filtro = new AuditoriaDAO.Filtro(periodo.inicioEm(clock.getZone()),
                periodo.fimExclusivoEm(clock.getZone()), usuarioId, acao, LIMITE + 1);
        List<RegistroAuditoria> linhas = transacao.executar(con -> dao.consultar(con, filtro));
        return linhas.size() > LIMITE ? new Consulta(List.copyOf(linhas.subList(0, LIMITE)), true)
                : new Consulta(List.copyOf(linhas), false);
    }
}
