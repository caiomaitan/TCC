package br.com.varesync.service;

import br.com.varesync.dao.PreferenciaDAO;
import br.com.varesync.model.Preferencias;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Preferências de aparência do próprio usuário (F21.1): tema e tamanho da fonte. Sem permissão (todo usuário mexe nas
 * suas), mas a sessão é conferida com o banco, como nas outras operações (F10.1).
 */
public class PreferenciaService {

    private final Transacao transacao;
    private final PreferenciaDAO preferenciaDAO;
    private final Clock clock;

    public PreferenciaService(Transacao transacao, PreferenciaDAO preferenciaDAO, Clock clock) {
        this.transacao = transacao;
        this.preferenciaDAO = preferenciaDAO;
        this.clock = clock;
    }

    /** As preferências do usuário da sessão; o padrão (claro, 100%) se ele nunca as gravou. */
    public Preferencias buscar(Sessao sessao) {
        long usuarioId = sessao.usuarioId();
        return transacao.executar(con -> preferenciaDAO.buscar(con, usuarioId).orElse(Preferencias.PADRAO));
    }

    /**
     * Grava as preferências do usuário da sessão.
     *
     * @throws SessaoEncerradaException usuário inativado ou com a senha redefinida pelo supervisor
     */
    public Preferencias salvar(Sessao sessao, Preferencias preferencias) {
        Objects.requireNonNull(preferencias, "preferencias");
        Sessao atual = sessao.conferida();
        transacao.executar(con -> {
            preferenciaDAO.gravar(con, atual.usuarioId(), preferencias, OffsetDateTime.now(clock));
            return null;
        });
        return preferencias;
    }
}
