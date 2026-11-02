package br.com.varesync.service;

import br.com.varesync.dao.ContaFinanceiraDAO;
import br.com.varesync.dao.ContaFinanceiraDAO.Origem;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.InconsistenciaConta;
import br.com.varesync.model.MovimentoConta;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoConta;
import br.com.varesync.model.TipoContaMovimento;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contas financeiras (F15.1): {@link #movimentar} é o único caminho que grava no razão (o trigger aplica o saldo, que
 * pode ficar negativo: saldo de conta nunca bloqueia uma operação de caixa). O cadastro, a transferência, o saldo
 * inicial, o ajuste e o extrato exigem FINANCEIRO; o diagnóstico é do supervisor.
 */
public class ContaFinanceiraService {

    public static final String ENTIDADE_CONTA = "conta_financeira";
    static final int TAMANHO_NOME = 60;
    static final int TAMANHO_DESCRICAO = 250;
    static final int TAMANHO_MOTIVO = 200;

    private final Transacao transacao;
    private final ContaFinanceiraDAO dao;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public ContaFinanceiraService(Transacao transacao, ContaFinanceiraDAO dao, AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    // ----- Razão -----

    /**
     * Grava um movimento no razão, na transação de quem chama. O valor vem com o sinal do tipo (≠ 0, até 2 casas). A
     * permissão é conferida por quem chama.
     */
    public void movimentar(Connection con, Sessao sessao, long contaId, TipoContaMovimento tipo, BigDecimal valor,
                           OffsetDateTime dataHora, String descricao, Origem origem) throws SQLException {
        if (valor == null || valor.signum() == 0) {
            throw new IllegalArgumentException("Movimento da conta sem valor");
        }
        if (tipo.sinal() != 0 && valor.signum() != tipo.sinal()) {
            throw new IllegalArgumentException("Sinal do valor não confere com o tipo " + tipo);
        }
        String texto = descricao.length() > TAMANHO_DESCRICAO ? descricao.substring(0, TAMANHO_DESCRICAO) : descricao;
        dao.inserirMovimento(con, contaId, tipo, Dinheiro.dinheiro(valor), dataHora, texto, sessao.usuarioId(), origem);
    }

    /** A conta informada, que precisa estar ativa, na transação de quem chama. */
    public ContaFinanceira contaAtiva(Connection con, long contaId) throws SQLException {
        ContaFinanceira conta = dao.buscar(con, contaId)
                .orElseThrow(() -> new NegocioException("Conta não encontrada."));
        if (!conta.ativa()) {
            throw new NegocioException("A conta \"" + conta.nome() + "\" está inativa. Escolha outra.");
        }
        return conta;
    }

    /** Contas ativas, para os diálogos (a permissão é conferida por quem chama). */
    public List<ContaFinanceira> ativas(Connection con) throws SQLException {
        return dao.listar(con).stream().filter(ContaFinanceira::ativa).toList();
    }

    /**
     * Momento de um lançamento com a data que a pessoa informou: a data com a hora de agora, para o extrato continuar
     * na ordem. Data futura é recusada.
     */
    OffsetDateTime momento(LocalDate data, String rotulo) {
        LocalDate hoje = LocalDate.now(clock);
        if (data == null) {
            throw new NegocioException("Informe a data " + rotulo + ".");
        }
        if (data.isAfter(hoje)) {
            throw new NegocioException("A data " + rotulo + " não pode ser depois de hoje.");
        }
        return data.equals(hoje) ? OffsetDateTime.now(clock)
                : data.atTime(LocalTime.now(clock)).atZone(clock.getZone()).toOffsetDateTime();
    }

    // ----- Cadastro -----

    /** Todas as contas com o saldo. */
    public List<ContaFinanceira> listar(Sessao sessao) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(dao::listar);
    }

    /** @return id da conta nova, ativa */
    public long criar(Sessao sessao, String nomeDigitado, TipoConta tipo, boolean padrao) {
        sessao.exigir(Permissao.FINANCEIRO);
        String nome = nome(nomeDigitado);
        if (tipo == null) {
            throw new NegocioException("Escolha o tipo da conta.");
        }
        return transacao.executar(con -> {
            long id = dao.inserir(con, nome, tipo);
            if (padrao) {
                dao.desmarcarPadrao(con);
                dao.alterar(con, id, nome, tipo, true, true);
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.CONTA_ALTERADA, ENTIDADE_CONTA, id,
                    "Conta \"" + nome + "\" (" + tipo.rotulo() + ") criada" + (padrao ? ", padrão do caixa" : ""));
            return id;
        });
    }

    /**
     * Altera o cadastro. Só inativa com saldo zero, sem ser a conta padrão do caixa e sem forma de pagamento recebendo
     * nela: o saldo e os recebíveis futuros não podem ficar numa conta que ninguém mais escolhe.
     */
    public void alterar(Sessao sessao, long id, String nomeDigitado, TipoConta tipo, boolean ativa, boolean padrao) {
        sessao.exigir(Permissao.FINANCEIRO);
        String nome = nome(nomeDigitado);
        if (tipo == null) {
            throw new NegocioException("Escolha o tipo da conta.");
        }
        transacao.executar(con -> {
            ContaFinanceira atual = dao.buscar(con, id).orElseThrow(() -> new NegocioException("Conta não encontrada."));
            if (!ativa) {
                if (padrao || atual.padraoCaixa()) {
                    throw new NegocioException("A conta \"" + atual.nome() + "\" é a padrão do caixa. Marque outra "
                            + "como padrão antes de inativar esta.");
                }
                if (atual.saldo().signum() != 0) {
                    throw new NegocioException("A conta \"" + atual.nome() + "\" tem saldo de "
                            + Formatos.moeda(atual.saldo()) + ". Transfira o saldo antes de inativar.");
                }
                List<String> formas = dao.formasDeDestino(con, id);
                if (!formas.isEmpty()) {
                    throw new NegocioException("A conta \"" + atual.nome() + "\" recebe as formas de pagamento "
                            + String.join(", ", formas) + ". Troque a conta delas em Configurações antes de inativar.");
                }
            }
            if (padrao && !atual.padraoCaixa()) {
                dao.desmarcarPadrao(con);
            }
            dao.alterar(con, id, nome, tipo, ativa, padrao);
            auditoria.registrar(con, sessao, AcaoAuditoria.CONTA_ALTERADA, ENTIDADE_CONTA, id,
                    "Conta \"" + atual.nome() + "\" alterada: nome \"" + nome + "\", tipo " + tipo.rotulo() + ", "
                            + (ativa ? "ativa" : "inativa") + (padrao ? ", padrão do caixa" : ""));
            return null;
        });
    }

    // ----- Lançamentos manuais -----

    /** Transferência entre contas: duas pernas com o mesmo número, gravadas na ordem do id da conta. */
    public void transferir(Sessao sessao, long origemId, long destinoId, BigDecimal valor, String descricao) {
        sessao.exigir(Permissao.FINANCEIRO);
        BigDecimal quantia = CaixaService.valor(valor, "o valor da transferência", false);
        if (origemId == destinoId) {
            throw new NegocioException("Escolha contas diferentes para a transferência.");
        }
        String texto = Textos.normalizarEspacos(descricao);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        transacao.executar(con -> {
            ContaFinanceira origem = contaAtiva(con, origemId);
            ContaFinanceira destino = contaAtiva(con, destinoId);
            long numero = dao.proximaTransferencia(con);
            String sai = "Transferência para " + destino.nome() + (texto.isEmpty() ? "" : ": " + texto);
            String entra = "Transferência de " + origem.nome() + (texto.isEmpty() ? "" : ": " + texto);
            Origem ligacao = Origem.transferencia(numero);
            if (origemId < destinoId) {
                movimentar(con, sessao, origemId, TipoContaMovimento.TRANSFERENCIA, quantia.negate(), agora, sai, ligacao);
                movimentar(con, sessao, destinoId, TipoContaMovimento.TRANSFERENCIA, quantia, agora, entra, ligacao);
            } else {
                movimentar(con, sessao, destinoId, TipoContaMovimento.TRANSFERENCIA, quantia, agora, entra, ligacao);
                movimentar(con, sessao, origemId, TipoContaMovimento.TRANSFERENCIA, quantia.negate(), agora, sai, ligacao);
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.CONTA_TRANSFERENCIA, ENTIDADE_CONTA, origemId,
                    "Transferência nº " + numero + " de " + Formatos.moeda(quantia) + " de \"" + origem.nome()
                            + "\" para \"" + destino.nome() + "\"" + (texto.isEmpty() ? "" : ": " + texto));
            return null;
        });
    }

    /** Saldo de partida da conta: um por conta; depois disso, correção só por ajuste. */
    public void lancarSaldoInicial(Sessao sessao, long contaId, BigDecimal valor) {
        sessao.exigir(Permissao.FINANCEIRO);
        BigDecimal quantia = comSinal(valor, "o saldo inicial");
        OffsetDateTime agora = OffsetDateTime.now(clock);
        transacao.executar(con -> {
            ContaFinanceira conta = contaAtiva(con, contaId);
            if (dao.temSaldoInicial(con, contaId)) {
                throw new NegocioException("A conta \"" + conta.nome() + "\" já tem saldo inicial. Para corrigir, "
                        + "lance um ajuste.");
            }
            movimentar(con, sessao, contaId, TipoContaMovimento.SALDO_INICIAL, quantia, agora, "Saldo inicial",
                    Origem.NENHUMA);
            auditoria.registrar(con, sessao, AcaoAuditoria.CONTA_SALDO_INICIAL, ENTIDADE_CONTA, contaId,
                    "Saldo inicial de " + CreditoService.sinal(quantia) + " na conta \"" + conta.nome() + "\"");
            return null;
        });
    }

    /** Ajuste com motivo: positivo entra, negativo sai. */
    public void ajustar(Sessao sessao, long contaId, BigDecimal valor, String motivo) {
        sessao.exigir(Permissao.FINANCEIRO);
        BigDecimal quantia = comSinal(valor, "o valor do ajuste");
        String motivoFinal = Textos.normalizarEspacos(motivo);
        if (motivoFinal.isEmpty()) {
            throw new NegocioException("Informe o motivo do ajuste.");
        }
        if (motivoFinal.length() > TAMANHO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MOTIVO + " caracteres.");
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        transacao.executar(con -> {
            ContaFinanceira conta = contaAtiva(con, contaId);
            movimentar(con, sessao, contaId, TipoContaMovimento.AJUSTE, quantia, agora, motivoFinal, Origem.NENHUMA);
            auditoria.registrar(con, sessao, AcaoAuditoria.CONTA_AJUSTE, ENTIDADE_CONTA, contaId,
                    "Conta \"" + conta.nome() + "\" ajustada em " + CreditoService.sinal(quantia) + ". Motivo: "
                            + motivoFinal);
            return null;
        });
    }

    // ----- Consultas -----

    /** Extrato do período, do mais antigo para o mais novo. */
    public List<MovimentoConta> extrato(Sessao sessao, long contaId, Periodo periodo) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(con -> dao.extrato(con, contaId, periodo.inicioEm(clock.getZone()),
                periodo.fimExclusivoEm(clock.getZone())));
    }

    /** Período inicial do extrato: os últimos 30 dias, contando hoje. */
    public Periodo periodoPadrao() {
        LocalDate hoje = LocalDate.now(clock);
        return new Periodo(hoje.minusDays(29), hoje);
    }

    /** {@code vw_conta_inconsistente} (deve vir vazia), para o Diagnóstico de Configurações. */
    public List<InconsistenciaConta> diagnostico(Sessao sessao) {
        sessao.exigirSupervisor();
        return transacao.executar(dao::inconsistencias);
    }

    // ----- Apoio -----

    private static String nome(String digitado) {
        String nome = Textos.normalizarEspacos(digitado);
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome da conta.");
        }
        if (nome.length() > TAMANHO_NOME) {
            throw new NegocioException("O nome da conta pode ter no máximo " + TAMANHO_NOME + " caracteres.");
        }
        return nome;
    }

    /** Valor ≠ 0 com o sinal informado, até 2 casas. */
    private static BigDecimal comSinal(BigDecimal valor, String rotulo) {
        if (valor == null || valor.signum() == 0) {
            throw new NegocioException("Informe " + rotulo + ": positivo entra, negativo sai.");
        }
        BigDecimal quantia = CaixaService.valor(valor.abs(), rotulo, false);
        return valor.signum() < 0 ? quantia.negate() : quantia;
    }
}
