package br.com.varesync.service;

import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.model.AcaoAuditoria;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Registro de auditoria (4.9), sempre na mesma transação da operação auditada.
 * A descrição é legível para o lojista e nunca contém senha.
 */
public class AuditoriaService {

    public static final String ENTIDADE_USUARIO = "usuario";
    public static final String ENTIDADE_PRODUTO = "produto";
    public static final String ENTIDADE_INVENTARIO = "inventario";
    public static final String ENTIDADE_ENTRADA = "entrada_mercadoria";
    public static final String ENTIDADE_PEDIDO = "pedido_compra";

    private final AuditoriaDAO dao;
    private final Clock clock;
    private final String terminal;

    public AuditoriaService(AuditoriaDAO dao, Clock clock, String terminal) {
        this.dao = dao;
        this.clock = clock;
        this.terminal = terminal;
    }

    /** Ação feita pelo usuário da sessão. */
    public void registrar(Connection con, Sessao sessao, AcaoAuditoria acao, String entidade, Long entidadeId,
                          String descricao) throws SQLException {
        dao.inserir(con, sessao.usuarioId(), acao, entidade, entidadeId, descricao, sessao.terminal(),
                OffsetDateTime.now(clock));
    }

    /** Ação sem sessão (tentativa de login); {@code usuarioId} nulo quando o login não existe. */
    public void registrar(Connection con, Long usuarioId, AcaoAuditoria acao, String entidade, Long entidadeId,
                          String descricao) throws SQLException {
        dao.inserir(con, usuarioId, acao, entidade, entidadeId, descricao, terminal, OffsetDateTime.now(clock));
    }
}
