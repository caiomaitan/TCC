package br.com.varesync.service;

import br.com.varesync.dao.CaixaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.ContaFinanceiraDAO;
import br.com.varesync.dao.ContaFinanceiraDAO.Origem;
import br.com.varesync.dao.RecebimentoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CaixaMovimento;
import br.com.varesync.model.CaixaResumo;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RelatorioFechamento;
import br.com.varesync.model.TipoCaixaMovimento;
import br.com.varesync.model.TipoContaMovimento;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Caixa do terminal (6.5): abertura com fundo de troco, sangria e suprimento, fechamento às cegas e o relatório de
 * fechamento (R11). A sessão de caixa é do terminal, não do usuário: quem entrar no mesmo computador continua nela.
 * A situação é sempre lida do banco pelo terminal da {@link Sessao}.
 *
 * <p>O PDV usa {@link #travarAberta(Connection, Sessao)} dentro da transação da venda: garante a sessão aberta e
 * trava a linha, para venda, sangria e fechamento não se cruzarem.
 */
public class CaixaService {

    public static final String ENTIDADE_CAIXA = "caixa_sessao";
    static final int TAMANHO_MAXIMO_MOTIVO = 200;
    static final String SEM_CAIXA_ABERTO = "Não há caixa aberto neste terminal. Abra o caixa para continuar.";
    private static final BigDecimal LIMITE_VALOR = new BigDecimal("10000000000"); // NUMERIC(12,2)

    private final Transacao transacao;
    private final CaixaDAO caixaDAO;
    private final RecebimentoDAO recebimentoDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final ContaFinanceiraDAO contaDAO;
    private final ContaFinanceiraService contas;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public CaixaService(Transacao transacao, CaixaDAO caixaDAO, RecebimentoDAO recebimentoDAO,
                        ConfiguracaoDAO configuracaoDAO, ContaFinanceiraDAO contaDAO, ContaFinanceiraService contas,
                        AuditoriaService auditoria, Clock clock) {
        this.configuracaoDAO = configuracaoDAO;
        this.contaDAO = contaDAO;
        this.contas = contas;
        this.transacao = transacao;
        this.caixaDAO = caixaDAO;
        this.recebimentoDAO = recebimentoDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /**
     * Sessão aberta neste terminal, se houver. Só informa a situação (barra de status, aviso ao sair), por isso
     * não exige permissão; as operações do caixa exigem PDV.
     */
    public Optional<CaixaSessao> caixaAberto(Sessao sessao) {
        return transacao.executar(con -> caixaDAO.buscarAberta(con, sessao.terminal()));
    }

    /** Sessão aberta neste terminal, na transação de quem chama (vendas da sessão, no PDV). */
    public Optional<CaixaSessao> caixaAberto(Connection con, Sessao sessao) throws SQLException {
        return caixaDAO.buscarAberta(con, sessao.terminal());
    }

    /**
     * Abre o caixa do terminal com o fundo de troco (≥ 0).
     *
     * @return a sessão aberta
     * @throws NegocioException se já houver caixa aberto neste terminal
     */
    public CaixaSessao abrir(Sessao sessao, BigDecimal fundoDeTroco) {
        return abrir(sessao, fundoDeTroco, null);
    }

    /**
     * Como {@link #abrir(Sessao, BigDecimal)}; com {@code financeiro.integrar_caixa} ligada, retira o fundo de troco da
     * conta e a sessão fica integrada até o fechamento (F15.1).
     *
     * @param contaId conta de origem; nula = a padrão do caixa
     */
    public CaixaSessao abrir(Sessao sessao, BigDecimal fundoDeTroco, Long contaId) {
        sessao.exigir(Permissao.PDV);
        BigDecimal valor = valor(fundoDeTroco, "o fundo de troco", true);
        return transacao.executar(con -> {
            Optional<CaixaSessao> aberta = caixaDAO.buscarAberta(con, sessao.terminal());
            if (aberta.isPresent()) {
                throw new NegocioException("O caixa deste terminal já está aberto (nº " + aberta.get().id()
                        + ", aberto por " + aberta.get().abertoPorNome() + " em "
                        + Formatos.dataHora(aberta.get().abertoEm()) + ").");
            }
            // Outro login no mesmo terminal pode abrir ao mesmo tempo: ux_caixa_aberto_por_terminal recusa o segundo.
            OffsetDateTime agora = OffsetDateTime.now(clock);
            ContaFinanceira conta = configuracaoDAO.carregar(con).integrarCaixa() ? conta(con, contaId) : null;
            long id = caixaDAO.abrir(con, sessao.terminal(), sessao.usuarioId(), valor, agora,
                    conta == null ? null : conta.id());
            if (conta != null && valor.signum() > 0) {
                contas.movimentar(con, sessao, conta.id(), TipoContaMovimento.ABERTURA_CAIXA, valor.negate(), agora,
                        "Fundo de troco do caixa nº " + id, Origem.caixaSessao(id));
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.CAIXA_ABERTO, ENTIDADE_CAIXA, id,
                    "Caixa nº " + id + " aberto no terminal " + sessao.terminal() + " com fundo de troco de "
                            + Formatos.moeda(valor) + (conta == null ? "" : ", da conta \"" + conta.nome() + "\""));
            return caixaDAO.buscar(con, id).orElseThrow();
        });
    }

    /**
     * Sangria ou suprimento (6.5): valor &gt; 0 e motivo. Exige SANGRIA_SUPRIMENTO ou a liberação de quem tem;
     * a sangria não pode passar do dinheiro esperado na gaveta.
     *
     * @param liberacao nula quando o próprio usuário tem a permissão
     * @return id do movimento
     */
    public long registrarMovimento(Sessao sessao, TipoCaixaMovimento tipo, BigDecimal valor, String motivo,
                                   Liberacao liberacao) {
        return registrarMovimento(sessao, tipo, valor, motivo, liberacao, null);
    }

    /**
     * Como {@link #registrarMovimento(Sessao, TipoCaixaMovimento, BigDecimal, String, Liberacao)}; na sessão integrada, a
     * sangria entra na conta e o suprimento sai dela (F15.1).
     *
     * @param contaId conta de destino da sangria ou de origem do suprimento; nula = a padrão do caixa
     */
    public long registrarMovimento(Sessao sessao, TipoCaixaMovimento tipo, BigDecimal valor, String motivo,
                                   Liberacao liberacao, Long contaId) {
        sessao.exigir(Permissao.PDV);
        Liberacao liberou = sessao.exigirOuLiberado(Permissao.SANGRIA_SUPRIMENTO, liberacao);
        if (tipo == null) {
            throw new NegocioException("Escolha o tipo: sangria ou suprimento.");
        }
        BigDecimal quantia = valor(valor, "o valor", false);
        String motivoFinal = Textos.normalizarEspacos(motivo);
        if (motivoFinal.isEmpty()) {
            throw new NegocioException(tipo == TipoCaixaMovimento.SANGRIA ? "Informe o motivo da sangria."
                    : "Informe o motivo do suprimento.");
        }
        if (motivoFinal.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        return transacao.executar(con -> {
            CaixaSessao caixa = travar(con, sessao);
            if (tipo == TipoCaixaMovimento.SANGRIA) {
                BigDecimal esperado = caixaDAO.resumo(con, caixa.id()).dinheiroEsperado();
                if (quantia.compareTo(esperado) > 0) {
                    // Sem mostrar o esperado: o fechamento é às cegas.
                    throw new NegocioException("O valor da sangria é maior que o dinheiro que deveria estar na "
                            + "gaveta. Confira o valor.");
                }
            }
            OffsetDateTime agora = OffsetDateTime.now(clock);
            ContaFinanceira conta = caixa.integrada() ? conta(con, contaId) : null;
            long id = caixaDAO.inserirMovimento(con, caixa.id(), tipo, quantia, motivoFinal, sessao.usuarioId(), agora,
                    conta == null ? null : conta.id());
            if (conta != null) {
                boolean sangria = tipo == TipoCaixaMovimento.SANGRIA;
                contas.movimentar(con, sessao, conta.id(), sangria ? TipoContaMovimento.SANGRIA
                        : TipoContaMovimento.SUPRIMENTO, sangria ? quantia : quantia.negate(), agora,
                        tipo.descricao() + " do caixa nº " + caixa.id() + ": " + motivoFinal,
                        Origem.caixaMovimento(id));
            }
            auditoria.registrar(con, sessao, tipo.acaoAuditoria(), ENTIDADE_CAIXA, caixa.id(),
                    tipo.descricao() + " de " + Formatos.moeda(quantia) + " no caixa nº " + caixa.id()
                            + ". Motivo: " + motivoFinal
                            + (liberou == null ? "" : " (" + liberou.descricao() + ")"));
            return id;
        });
    }

    /**
     * Fechamento às cegas (6.5): grava o esperado de {@code vw_caixa_resumo}, o contado, quem e quando, e só então
     * devolve o relatório com a sobra ou a falta.
     */
    public RelatorioFechamento fechar(Sessao sessao, BigDecimal valorContado) {
        return fechar(sessao, valorContado, null);
    }

    /**
     * Como {@link #fechar(Sessao, BigDecimal)}; na sessão integrada, o valor contado entra na conta (F15.1).
     *
     * @param contaId conta de destino; nula = a padrão do caixa
     */
    public RelatorioFechamento fechar(Sessao sessao, BigDecimal valorContado, Long contaId) {
        sessao.exigir(Permissao.PDV);
        BigDecimal contado = valor(valorContado, "o dinheiro contado", true);
        return transacao.executar(con -> {
            CaixaSessao caixa = travar(con, sessao);
            BigDecimal esperado = caixaDAO.resumo(con, caixa.id()).dinheiroEsperado();
            OffsetDateTime agora = OffsetDateTime.now(clock);
            ContaFinanceira conta = caixa.integrada() ? conta(con, contaId) : null;
            if (!caixaDAO.fechar(con, caixa.id(), sessao.usuarioId(), agora, esperado, contado,
                    conta == null ? null : conta.id())) {
                throw new NegocioException(SEM_CAIXA_ABERTO);
            }
            if (conta != null && contado.signum() > 0) {
                contas.movimentar(con, sessao, conta.id(), TipoContaMovimento.FECHAMENTO_CAIXA, contado, agora,
                        "Dinheiro contado no fechamento do caixa nº " + caixa.id(), Origem.caixaSessao(caixa.id()));
            }
            RelatorioFechamento relatorio = montarRelatorio(con, caixa.id());
            auditoria.registrar(con, sessao, AcaoAuditoria.CAIXA_FECHADO, ENTIDADE_CAIXA, caixa.id(),
                    "Caixa nº " + caixa.id() + " fechado: esperado " + Formatos.moeda(esperado) + ", contado "
                            + Formatos.moeda(contado) + " (" + relatorio.situacaoNaFrase()
                            + ")");
            return relatorio;
        });
    }

    /** Contas para o diálogo de abertura; vazio com a integração desligada (o diálogo não mostra o campo). */
    public List<ContaFinanceira> contasParaAbrir(Sessao sessao) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> configuracaoDAO.carregar(con).integrarCaixa() ? contas.ativas(con)
                : List.<ContaFinanceira>of());
    }

    /** Contas para a sangria, o suprimento e o fechamento; vazio se o caixa aberto não é integrado. */
    public List<ContaFinanceira> contasDoCaixaAberto(Sessao sessao) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> caixaDAO.buscarAberta(con, sessao.terminal()).filter(CaixaSessao::integrada)
                .isPresent() ? contas.ativas(con) : List.<ContaFinanceira>of());
    }

    /** Sangrias e suprimentos do caixa aberto neste terminal (vazio se não houver caixa aberto). */
    public List<CaixaMovimento> movimentosDoCaixaAberto(Sessao sessao) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> {
            Optional<CaixaSessao> aberta = caixaDAO.buscarAberta(con, sessao.terminal());
            return aberta.isEmpty() ? List.<CaixaMovimento>of() : caixaDAO.listarMovimentos(con, aberta.get().id());
        });
    }

    /** Relatório de fechamento (R11) de uma sessão já fechada. */
    public RelatorioFechamento relatorioFechamento(Sessao sessao, long caixaSessaoId) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> {
            CaixaSessao caixa = caixaDAO.buscar(con, caixaSessaoId)
                    .orElseThrow(() -> new NegocioException("Sessão de caixa não encontrada."));
            if (caixa.aberta()) {
                // O relatório mostra o esperado: com o caixa aberto, isso desfaria o fechamento às cegas.
                throw new NegocioException("O relatório de fechamento só fica disponível depois que o caixa é fechado.");
            }
            return montarRelatorio(con, caixaSessaoId);
        });
    }

    /** Relatório do último caixa fechado neste terminal, se houver. */
    public Optional<RelatorioFechamento> ultimoFechamento(Sessao sessao) {
        sessao.exigir(Permissao.PDV);
        return transacao.executar(con -> {
            Optional<CaixaSessao> ultima = caixaDAO.buscarUltimaFechada(con, sessao.terminal());
            return ultima.isEmpty() ? Optional.<RelatorioFechamento>empty()
                    : Optional.of(montarRelatorio(con, ultima.get().id()));
        });
    }

    /** Dinheiro que deveria estar na gaveta agora ({@code vw_caixa_resumo}), na transação de quem chama. */
    public BigDecimal dinheiroEsperado(Connection con, long caixaSessaoId) throws SQLException {
        return caixaDAO.resumo(con, caixaSessaoId).dinheiroEsperado();
    }

    /**
     * Para o PDV: na transação de quem chama, devolve a sessão aberta neste terminal com a linha travada.
     *
     * @throws NegocioException se não houver caixa aberto (nenhuma venda com caixa fechado)
     */
    public CaixaSessao travarAberta(Connection con, Sessao sessao) throws SQLException {
        return travar(con, sessao);
    }

    /** Dinheiro em reais: obrigatório, até 2 casas, dentro do que a coluna comporta; zero só quando permitido. */
    static BigDecimal valor(BigDecimal valor, String rotulo, boolean permiteZero) {
        if (valor == null) {
            throw new NegocioException("Informe " + rotulo + ".");
        }
        String nome = Character.toUpperCase(rotulo.charAt(0)) + rotulo.substring(1);
        if (valor.signum() < 0) {
            throw new NegocioException(nome + " não pode ser negativo.");
        }
        if (valor.signum() == 0 && !permiteZero) {
            throw new NegocioException(nome + " deve ser maior que zero.");
        }
        if (ValidacaoProduto.casas(valor) > Dinheiro.ESCALA_DINHEIRO) {
            throw new NegocioException(nome + " pode ter no máximo " + Dinheiro.ESCALA_DINHEIRO + " casas decimais.");
        }
        if (valor.compareTo(LIMITE_VALOR) >= 0) {
            throw new NegocioException(nome + " está acima do limite permitido.");
        }
        return Dinheiro.dinheiro(valor);
    }

    /** A conta escolhida ou, sem escolha, a padrão do caixa. */
    private ContaFinanceira conta(Connection con, Long contaId) throws SQLException {
        if (contaId != null) {
            return contas.contaAtiva(con, contaId);
        }
        return contaDAO.padraoDoCaixa(con).orElseThrow(() -> new NegocioException("Escolha a conta: não há conta "
                + "padrão do caixa."));
    }

    private CaixaSessao travar(Connection con, Sessao sessao) throws SQLException {
        return caixaDAO.travarAberta(con, sessao.terminal()).orElseThrow(() -> new NegocioException(SEM_CAIXA_ABERTO));
    }

    private RelatorioFechamento montarRelatorio(Connection con, long caixaSessaoId) throws SQLException {
        CaixaSessao caixa = caixaDAO.buscar(con, caixaSessaoId).orElseThrow();
        CaixaResumo resumo = caixaDAO.resumo(con, caixaSessaoId);
        return new RelatorioFechamento(caixa, resumo, caixaDAO.vendasPorForma(con, caixaSessaoId),
                caixaDAO.listarMovimentos(con, caixaSessaoId), caixaDAO.descontos(con, caixaSessaoId),
                recebimentoDAO.porForma(con, caixaSessaoId));
    }
}
