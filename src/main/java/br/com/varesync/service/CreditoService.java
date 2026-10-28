package br.com.varesync.service;

import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.dao.CreditoClienteDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Cliente;
import br.com.varesync.model.ExtratoCredito;
import br.com.varesync.model.InconsistenciaCredito;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoCreditoCliente;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Crédito do cliente (F12.3): {@link #movimentar} é o único caminho que grava no razão (o trigger aplica o saldo);
 * ajuste manual (só o supervisor, com motivo e auditoria), extrato (CLIENTES) e diagnóstico (só o supervisor). O
 * cashback, o uso em venda e os estornos são gravados pelo {@link VendaService} na transação da venda.
 */
public class CreditoService {

    public static final int TAMANHO_MAXIMO_MOTIVO = 200;

    private final Transacao transacao;
    private final CreditoClienteDAO dao;
    private final ClienteDAO clienteDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public CreditoService(Transacao transacao, CreditoClienteDAO dao, ClienteDAO clienteDAO,
                          AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.clienteDAO = clienteDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /**
     * Grava um movimento no razão, na transação de quem chama. O valor vem com o sinal do tipo (≠ 0, até 2 casas);
     * só o ajuste leva motivo e só ele não tem venda. A permissão é conferida por quem chama.
     */
    public void movimentar(Connection con, Sessao sessao, long clienteId, TipoCreditoCliente tipo, BigDecimal valor,
                           Long vendaId, String motivo) throws SQLException {
        movimentar(con, sessao, clienteId, tipo, valor, vendaId, null, motivo);
    }

    /**
     * Como {@link #movimentar(Connection, Sessao, long, TipoCreditoCliente, BigDecimal, Long, String)}, com a devolução
     * que gerou o movimento (F13): o vale-troca ({@code CREDITO_DEVOLUCAO}) exige a devolução, e o estorno parcial do
     * cashback a leva.
     */
    public void movimentar(Connection con, Sessao sessao, long clienteId, TipoCreditoCliente tipo, BigDecimal valor,
                           Long vendaId, Long devolucaoId, String motivo) throws SQLException {
        if (valor == null || valor.signum() == 0) {
            throw new IllegalArgumentException("Movimento de crédito sem valor");
        }
        if (tipo.sinal() != 0 && valor.signum() != tipo.sinal()) {
            throw new IllegalArgumentException("Sinal do valor não confere com o tipo " + tipo);
        }
        if (tipo.exigeVenda() != (vendaId != null)) {
            throw new IllegalArgumentException("Venda do movimento não confere com o tipo " + tipo);
        }
        if ((tipo == TipoCreditoCliente.CREDITO_DEVOLUCAO) != (devolucaoId != null)
                && !(tipo == TipoCreditoCliente.ESTORNO_CASHBACK && devolucaoId != null)) {
            throw new IllegalArgumentException("Devolução do movimento não confere com o tipo " + tipo);
        }
        dao.inserir(con, clienteId, tipo, Dinheiro.dinheiro(valor), vendaId, devolucaoId, motivo, sessao.usuarioId(),
                OffsetDateTime.now(clock));
    }

    /**
     * Ajuste manual (só o supervisor): valor positivo credita, negativo debita (pode deixar o saldo negativo, como os
     * estornos); motivo obrigatório; auditoria {@code CREDITO_AJUSTADO} com o saldo antes e depois.
     * No cliente anonimizado, só o ajuste que zera o saldo (F13.2).
     *
     * @return o novo saldo
     */
    public BigDecimal ajustar(Sessao sessao, long clienteId, BigDecimal valor, String motivo) {
        sessao.exigirSupervisor();
        if (valor == null || valor.signum() == 0) {
            throw new NegocioException("Informe o valor do ajuste: positivo credita, negativo debita.");
        }
        BigDecimal quantia = CaixaService.valor(valor.abs(), "o valor do ajuste", false);
        BigDecimal ajuste = valor.signum() < 0 ? quantia.negate() : quantia;
        String motivoFinal = Textos.normalizarEspacos(motivo);
        if (motivoFinal.isEmpty()) {
            throw new NegocioException("Informe o motivo do ajuste.");
        }
        if (motivoFinal.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        return transacao.executar(con -> {
            Cliente cliente = clienteDAO.buscar(con, clienteId)
                    .orElseThrow(() -> new NegocioException("Cliente não encontrado."));
            BigDecimal antes = dao.travarSaldo(con, clienteId).orElseThrow();
            // Cliente anonimizado (F13.2): o ajuste só zera o saldo que sobrou de uma venda cancelada ou devolvida.
            if (cliente.anonimizado() && antes.add(ajuste).signum() != 0) {
                throw new NegocioException("O cliente nº " + clienteId + " foi anonimizado (LGPD): o ajuste só pode "
                        + "zerar o saldo de " + Formatos.moeda(antes) + (antes.signum() == 0 ? "" : " (valor "
                        + sinal(antes.negate()) + ")") + ".");
            }
            movimentar(con, sessao, clienteId, TipoCreditoCliente.AJUSTE, ajuste, null, motivoFinal);
            BigDecimal depois = antes.add(ajuste);
            auditoria.registrar(con, sessao, AcaoAuditoria.CREDITO_AJUSTADO, ClienteService.ENTIDADE_CLIENTE,
                    clienteId, "Crédito do cliente nº " + clienteId + " ajustado em " + sinal(ajuste) + " (saldo de "
                            + Formatos.moeda(antes) + " para " + Formatos.moeda(depois) + "). Motivo: " + motivoFinal);
            return depois;
        });
    }

    /** Saldo e extrato do cliente (aba Crédito da tela Clientes). */
    public ExtratoCredito extrato(Sessao sessao, long clienteId) {
        sessao.exigir(Permissao.CLIENTES);
        return transacao.executar(con -> extrato(con, clienteId));
    }

    ExtratoCredito extrato(Connection con, long clienteId) throws SQLException {
        BigDecimal saldo = dao.saldo(con, clienteId).orElseThrow(() -> new NegocioException("Cliente não encontrado."));
        return new ExtratoCredito(saldo, dao.movimentos(con, clienteId));
    }

    /** {@code vw_cliente_credito_inconsistente} (deve vir vazia), para o Diagnóstico de Configurações. */
    public List<InconsistenciaCredito> diagnostico(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(dao::inconsistencias);
    }

    /** "+R$ 5,00" ou "-R$ 5,00". */
    static String sinal(BigDecimal valor) {
        return (valor.signum() > 0 ? "+" : "") + Formatos.moeda(valor);
    }
}
