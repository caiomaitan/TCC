package br.com.varesync.service;

import br.com.varesync.dao.ContaFinanceiraDAO.Origem;
import br.com.varesync.dao.ContaPagarDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CategoriaDespesa;
import br.com.varesync.model.ContaFinanceira;
import br.com.varesync.model.DadosTituloPagar;
import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.GrupoDespesa;
import br.com.varesync.model.PagamentoTitulo;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusTituloPagar;
import br.com.varesync.model.TipoContaMovimento;
import br.com.varesync.model.TituloPagar;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Contas a pagar (F15.3), tudo com a permissão FINANCEIRO: categorias, despesa avulsa com repetição mensal, pagamento
 * parcial ou total (a conta sai com valor + juros − desconto), estorno com motivo e cancelamento do título sem pagamento.
 * As parcelas da entrada de mercadoria viram títulos na confirmação dela ({@link #gerarDaEntrada}), sob a permissão
 * COMPRAS de quem confirma.
 */
public class ContaPagarService {

    public static final String ENTIDADE_TITULO = "titulo_pagar";
    public static final int REPETICOES_MAXIMAS = 12;
    static final int TAMANHO_DESCRICAO = 200;
    static final int TAMANHO_DOCUMENTO = 30;
    static final int TAMANHO_CATEGORIA = 60;
    static final int TAMANHO_MOTIVO = 200;

    private final Transacao transacao;
    private final ContaPagarDAO dao;
    private final FornecedorDAO fornecedorDAO;
    private final ContaFinanceiraService contas;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public ContaPagarService(Transacao transacao, ContaPagarDAO dao, FornecedorDAO fornecedorDAO,
                             ContaFinanceiraService contas, AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.fornecedorDAO = fornecedorDAO;
        this.contas = contas;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    // ----- Categorias -----

    public List<CategoriaDespesa> categorias(Sessao sessao) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(dao::categorias);
    }

    /** @return id da categoria nova */
    public long criarCategoria(Sessao sessao, String nomeDigitado, GrupoDespesa grupo) {
        sessao.exigir(Permissao.FINANCEIRO);
        String nome = Textos.normalizarEspacos(nomeDigitado);
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome da categoria.");
        }
        if (nome.length() > TAMANHO_CATEGORIA) {
            throw new NegocioException("O nome da categoria pode ter no máximo " + TAMANHO_CATEGORIA + " caracteres.");
        }
        if (grupo == null) {
            throw new NegocioException("Escolha o grupo da categoria.");
        }
        return transacao.executar(con -> dao.inserirCategoria(con, nome, grupo));
    }

    public void alterarSituacaoCategoria(Sessao sessao, long id, boolean ativa) {
        sessao.exigir(Permissao.FINANCEIRO);
        transacao.executar(con -> {
            dao.alterarSituacaoCategoria(con, id, ativa);
            return null;
        });
    }

    // ----- Títulos -----

    public List<TituloPagar> listar(Sessao sessao, StatusTituloPagar status, Long categoriaId, LocalDate de,
                                    LocalDate ate) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(con -> dao.listar(con, new ContaPagarDAO.Filtro(status, categoriaId, de, ate)));
    }

    /** Fornecedores ativos e contas ativas, para os diálogos. */
    public List<Fornecedor> fornecedores(Sessao sessao) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(con -> fornecedorDAO.listar(con, false));
    }

    public List<ContaFinanceira> contasAtivas(Sessao sessao) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(contas::ativas);
    }

    public List<PagamentoTitulo> pagamentos(Sessao sessao, long tituloId) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(con -> dao.pagamentos(con, tituloId));
    }

    /**
     * Despesa avulsa. Com repetição, gera na hora os títulos dos próximos N meses (até 12), com a competência e o
     * vencimento mês a mês a partir dos informados.
     *
     * @param repeticoes meses além do informado; 0 = só este
     * @return ids dos títulos, do primeiro ao último
     */
    public List<Long> criar(Sessao sessao, DadosTituloPagar dados, int repeticoes) {
        sessao.exigir(Permissao.FINANCEIRO);
        if (repeticoes < 0 || repeticoes > REPETICOES_MAXIMAS) {
            throw new NegocioException("A repetição pode ter de 0 a " + REPETICOES_MAXIMAS + " meses.");
        }
        if (dados.categoriaId() == null) {
            throw new NegocioException("Escolha a categoria.");
        }
        String descricao = Textos.normalizarEspacos(dados.descricao());
        if (descricao.isEmpty()) {
            throw new NegocioException("Informe a descrição.");
        }
        if (descricao.length() > TAMANHO_DESCRICAO) {
            throw new NegocioException("A descrição pode ter no máximo " + TAMANHO_DESCRICAO + " caracteres.");
        }
        if (dados.vencimento() == null) {
            throw new NegocioException("Informe o vencimento.");
        }
        String documento = Textos.vazio(dados.documento()) ? null : Textos.normalizarEspacos(dados.documento());
        if (documento != null && documento.length() > TAMANHO_DOCUMENTO) {
            throw new NegocioException("O documento pode ter no máximo " + TAMANHO_DOCUMENTO + " caracteres.");
        }
        BigDecimal valor = CaixaService.valor(dados.valor(), "o valor", false);
        LocalDate competencia = (dados.competencia() == null ? dados.vencimento() : dados.competencia()).withDayOfMonth(1);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            CategoriaDespesa categoria = dao.categoria(con, dados.categoriaId())
                    .orElseThrow(() -> new NegocioException("Categoria não encontrada."));
            if (!categoria.ativa()) {
                throw new NegocioException("A categoria \"" + categoria.nome() + "\" está inativa.");
            }
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i <= repeticoes; i++) {
                ids.add(dao.inserirTitulo(con, new ContaPagarDAO.Novo(categoria.id(), descricao,
                        competencia.plusMonths(i), dados.vencimento().plusMonths(i), dados.fornecedorId(), null,
                        documento, dados.emissao(), valor, agora, sessao.usuarioId())));
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.TITULO_PAGAR_CRIADO, ENTIDADE_TITULO, ids.get(0),
                    "Conta a pagar nº " + ids.get(0) + " \"" + descricao + "\" de " + Formatos.moeda(valor)
                            + ", vencimento " + Formatos.data(dados.vencimento())
                            + (repeticoes == 0 ? "" : ", repetida por mais " + repeticoes + " mês(es)"));
            return ids;
        });
    }

    /** Parcelas da entrada confirmada viram títulos na categoria do grupo MERCADORIA, na transação da confirmação. */
    int gerarDaEntrada(Connection con, Sessao sessao, EntradaResumo entrada, List<ParcelaEntrada> parcelas)
            throws SQLException {
        if (parcelas.isEmpty()) {
            return 0;
        }
        long categoria = dao.categoriaDeMercadoria(con);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        LocalDate base = entrada.dataEmissao() == null ? agora.atZoneSameInstant(clock.getZone()).toLocalDate()
                : entrada.dataEmissao();
        String nota = entrada.numeroDocumento() == null ? "entrada nº " + entrada.id()
                : "NF " + entrada.numeroDocumento();
        for (ParcelaEntrada p : parcelas) {
            String descricao = "Compra de " + entrada.fornecedorNome() + " — " + nota + " — parcela " + p.numero() + "/"
                    + parcelas.size();
            dao.inserirTitulo(con, new ContaPagarDAO.Novo(categoria,
                    descricao.length() > TAMANHO_DESCRICAO ? descricao.substring(0, TAMANHO_DESCRICAO) : descricao,
                    base.withDayOfMonth(1), p.vencimento(), entrada.fornecedorId(), entrada.id(),
                    entrada.numeroDocumento() == null ? null : entrada.numeroDocumento() + "/" + p.numero(),
                    entrada.dataEmissao(), p.valor(), agora, sessao.usuarioId()));
        }
        return parcelas.size();
    }

    /**
     * Pagamento parcial ou total. {@code valor} abate o título; a conta sai com valor + juros − desconto. A data não
     * pode ser futura; o saldo da conta não bloqueia.
     *
     * @param juros    nulo = zero
     * @param desconto nulo = zero
     * @return id do pagamento
     */
    public long pagar(Sessao sessao, long tituloId, Long contaId, LocalDate data, BigDecimal valor, BigDecimal juros,
                      BigDecimal desconto) {
        sessao.exigir(Permissao.FINANCEIRO);
        if (contaId == null) {
            throw new NegocioException("Escolha a conta de onde sai o pagamento.");
        }
        BigDecimal quantia = CaixaService.valor(valor, "o valor pago", false);
        BigDecimal jurosFinal = juros == null ? Dinheiro.ZERO : CaixaService.valor(juros, "os juros", true);
        BigDecimal descontoFinal = desconto == null ? Dinheiro.ZERO : CaixaService.valor(desconto, "o desconto", true);
        BigDecimal saida = quantia.add(jurosFinal).subtract(descontoFinal);
        if (saida.signum() < 0) {
            throw new NegocioException("O desconto passa do valor com juros.");
        }
        OffsetDateTime momento = contas.momento(data, "do pagamento");
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            TituloPagar titulo = dao.buscar(con, tituloId, true)
                    .orElseThrow(() -> new NegocioException("Conta a pagar não encontrada."));
            if (titulo.status() != StatusTituloPagar.ABERTO) {
                throw new NegocioException("A conta a pagar nº " + tituloId + " está "
                        + titulo.status().rotulo().toLowerCase() + ".");
            }
            if (quantia.compareTo(titulo.emAberto()) > 0) {
                throw new NegocioException("O valor pago (" + Formatos.moeda(quantia) + ") passa do que está em aberto ("
                        + Formatos.moeda(titulo.emAberto()) + "). Juros vão no campo próprio.");
            }
            ContaFinanceira conta = contas.contaAtiva(con, contaId);
            long id = dao.inserirPagamento(con, tituloId, contaId, data, quantia, jurosFinal, descontoFinal,
                    sessao.usuarioId(), agora);
            dao.baixar(con, tituloId, quantia);
            if (saida.signum() > 0) {
                contas.movimentar(con, sessao, contaId, TipoContaMovimento.PAGAMENTO, saida.negate(), momento,
                        "Conta a pagar nº " + tituloId + " — " + titulo.descricao(), Origem.pagamento(id));
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.TITULO_PAGAR_PAGO, ENTIDADE_TITULO, tituloId,
                    "Pagamento nº " + id + " da conta a pagar nº " + tituloId + ": " + Formatos.moeda(quantia)
                            + (jurosFinal.signum() > 0 ? ", juros " + Formatos.moeda(jurosFinal) : "")
                            + (descontoFinal.signum() > 0 ? ", desconto " + Formatos.moeda(descontoFinal) : "")
                            + ", saída de " + Formatos.moeda(saida) + " da conta \"" + conta.nome() + "\"");
            return id;
        });
    }

    /** Estorno com motivo: a conta recebe de volta o que saiu e o título reabre. */
    public void estornar(Sessao sessao, long pagamentoId, String motivo) {
        sessao.exigir(Permissao.FINANCEIRO);
        String motivoFinal = motivo(motivo, "do estorno");
        OffsetDateTime agora = OffsetDateTime.now(clock);
        transacao.executar(con -> {
            long tituloId = dao.pagamento(con, pagamentoId)
                    .orElseThrow(() -> new NegocioException("Pagamento não encontrado.")).tituloId();
            TituloPagar titulo = dao.buscar(con, tituloId, true).orElseThrow();
            PagamentoTitulo p = dao.pagamento(con, pagamentoId).orElseThrow(); // relido depois da trava
            if (p.estornado()) {
                throw new NegocioException("O pagamento nº " + pagamentoId + " já foi estornado.");
            }
            dao.estornarPagamento(con, pagamentoId, sessao.usuarioId(), agora, motivoFinal);
            dao.baixar(con, tituloId, p.valor().negate());
            if (p.saida().signum() > 0) {
                contas.movimentar(con, sessao, p.contaId(), TipoContaMovimento.ESTORNO_PAGAMENTO, p.saida(), agora,
                        "Estorno da conta a pagar nº " + tituloId + " — " + titulo.descricao(),
                        Origem.pagamento(pagamentoId));
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.PAGAMENTO_ESTORNADO, ENTIDADE_TITULO, tituloId,
                    "Pagamento nº " + pagamentoId + " da conta a pagar nº " + tituloId + " estornado: "
                            + Formatos.moeda(p.saida()) + " de volta à conta \"" + p.conta() + "\". Motivo: "
                            + motivoFinal);
            return null;
        });
    }

    /** Cancela o título sem pagamento; com pagamento, é preciso estornar antes. */
    public void cancelar(Sessao sessao, long tituloId, String motivo) {
        sessao.exigir(Permissao.FINANCEIRO);
        String motivoFinal = motivo(motivo, "do cancelamento");
        transacao.executar(con -> {
            TituloPagar titulo = dao.buscar(con, tituloId, true)
                    .orElseThrow(() -> new NegocioException("Conta a pagar não encontrada."));
            if (titulo.status() == StatusTituloPagar.CANCELADO) {
                throw new NegocioException("A conta a pagar nº " + tituloId + " já está cancelada.");
            }
            if (titulo.valorPago().signum() > 0) {
                throw new NegocioException("A conta a pagar nº " + tituloId + " tem pagamento. Estorne o pagamento "
                        + "antes de cancelar.");
            }
            dao.cancelar(con, tituloId, motivoFinal);
            auditoria.registrar(con, sessao, AcaoAuditoria.TITULO_PAGAR_CANCELADO, ENTIDADE_TITULO, tituloId,
                    "Conta a pagar nº " + tituloId + " \"" + titulo.descricao() + "\" cancelada. Motivo: " + motivoFinal);
            return null;
        });
    }

    private static String motivo(String digitado, String rotulo) {
        String motivo = Textos.normalizarEspacos(digitado);
        if (motivo.isEmpty()) {
            throw new NegocioException("Informe o motivo " + rotulo + ".");
        }
        if (motivo.length() > TAMANHO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MOTIVO + " caracteres.");
        }
        return motivo;
    }
}
