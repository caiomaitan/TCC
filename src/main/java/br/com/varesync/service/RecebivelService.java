package br.com.varesync.service;

import br.com.varesync.dao.ContaFinanceiraDAO.Origem;
import br.com.varesync.dao.FormaPagamentoDAO;
import br.com.varesync.dao.RecebivelDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Recebivel;
import br.com.varesync.model.StatusRecebivel;
import br.com.varesync.model.TipoContaMovimento;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recebíveis de cartão, PIX e das outras formas com conta de destino (F15.2). Nascem na finalização da venda e no
 * recebimento do crediário, com a taxa e o prazo da forma; com prazo zero, já nascem recebidos e entram na conta. O
 * cancelamento da venda e o estorno do recebimento cancelam os previstos e estornam da conta os já recebidos. A
 * conciliação (FINANCEIRO) marca os recebidos, com o líquido que o banco pagou.
 */
public class RecebivelService {

    public static final String ENTIDADE_RECEBIVEL = "recebivel";
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private final Transacao transacao;
    private final RecebivelDAO dao;
    private final FormaPagamentoDAO formaDAO;
    private final ContaFinanceiraService contas;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public RecebivelService(Transacao transacao, RecebivelDAO dao, FormaPagamentoDAO formaDAO,
                            ContaFinanceiraService contas, AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.formaDAO = formaDAO;
        this.contas = contas;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /** Pagamento de venda, na transação da venda; forma sem conta de destino não gera nada. */
    public void gerarDaVenda(Connection con, Sessao sessao, long vendaPagamentoId, long vendaId, int formaId,
                             BigDecimal valor, OffsetDateTime dataHora) throws SQLException {
        gerar(con, sessao, vendaPagamentoId, null, "Venda nº " + vendaId, formaId, valor, dataHora);
    }

    /** Pagamento de recebimento do crediário, na transação dele; forma sem conta de destino não gera nada. */
    public void gerarDoRecebimento(Connection con, Sessao sessao, long recebimentoPagamentoId, long recebimentoId,
                                   int formaId, BigDecimal valor, OffsetDateTime dataHora) throws SQLException {
        gerar(con, sessao, null, recebimentoPagamentoId, "Recebimento nº " + recebimentoId, formaId, valor, dataHora);
    }

    private void gerar(Connection con, Sessao sessao, Long vendaPagamentoId, Long recebimentoPagamentoId, String origem,
                       int formaId, BigDecimal valor, OffsetDateTime dataHora) throws SQLException {
        FormaPagamentoCadastro forma = formaDAO.buscar(con, formaId).orElseThrow();
        if (forma.contaDestinoId() == null) {
            return;
        }
        BigDecimal taxa = taxa(valor, forma.taxaPct());
        LocalDate data = dataHora.atZoneSameInstant(clock.getZone()).toLocalDate();
        boolean recebido = forma.diasRecebimento() == 0;
        long id = dao.inserir(con, new RecebivelDAO.Novo(vendaPagamentoId, recebimentoPagamentoId, formaId,
                forma.contaDestinoId(), valor, taxa, data.plusDays(forma.diasRecebimento()),
                recebido ? StatusRecebivel.RECEBIDO : StatusRecebivel.PREVISTO, recebido ? data : null, dataHora));
        if (recebido) {
            contas.movimentar(con, sessao, forma.contaDestinoId(), TipoContaMovimento.RECEBIVEL, valor.subtract(taxa),
                    dataHora, origem + " — " + forma.descricao(), Origem.recebivel(id));
        }
    }

    /** round(bruto × taxa ÷ 100, 2). */
    static BigDecimal taxa(BigDecimal bruto, BigDecimal taxaPct) {
        return bruto.multiply(taxaPct).divide(CEM, Dinheiro.ESCALA_DINHEIRO, Dinheiro.ARREDONDAMENTO);
    }

    /** Cancelamento da venda, na transação dele. */
    public void cancelarDaVenda(Connection con, Sessao sessao, long vendaId, OffsetDateTime agora) throws SQLException {
        cancelar(con, sessao, dao.travarDaVenda(con, vendaId), "Venda nº " + vendaId + " cancelada", agora);
    }

    /** Estorno do recebimento do crediário, na transação dele. */
    public void cancelarDoRecebimento(Connection con, Sessao sessao, long recebimentoId, OffsetDateTime agora)
            throws SQLException {
        cancelar(con, sessao, dao.travarDoRecebimento(con, recebimentoId), "Recebimento nº " + recebimentoId
                + " estornado", agora);
    }

    private void cancelar(Connection con, Sessao sessao, List<Recebivel> recebiveis, String motivo, OffsetDateTime agora)
            throws SQLException {
        for (Recebivel r : recebiveis) {
            if (r.status() == StatusRecebivel.RECEBIDO && r.valorLiquido().signum() > 0) {
                contas.movimentar(con, sessao, r.contaId(), TipoContaMovimento.ESTORNO_RECEBIVEL,
                        r.valorLiquido().negate(), agora, motivo + " — " + r.forma(), Origem.recebivel(r.id()));
            }
            dao.cancelar(con, r.id());
        }
    }

    // ----- Conciliação -----

    public List<Recebivel> listar(Sessao sessao, Integer formaId, StatusRecebivel status, LocalDate de, LocalDate ate) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(con -> dao.listar(con, new RecebivelDAO.Filtro(formaId, status, de, ate)));
    }

    /** Formas que recebem em conta, para o filtro da conciliação. */
    public List<FormaPagamentoCadastro> formas(Sessao sessao) {
        sessao.exigir(Permissao.FINANCEIRO);
        return transacao.executar(con -> formaDAO.listar(con).stream().filter(FormaPagamentoCadastro::aceitaContaDestino)
                .toList());
    }

    /**
     * Marca recebíveis previstos como recebidos e lança RECEBIVEL na conta de cada um.
     *
     * @param liquidos         recebível → líquido que o banco pagou, de zero exclusive até o bruto; nulo = o previsto
     * @param data             data do crédito; não pode ser futura
     * @param totalConferido   total do extrato do banco; se informado, precisa bater com a soma dos líquidos
     * @return a soma dos líquidos
     */
    public BigDecimal conciliar(Sessao sessao, Map<Long, BigDecimal> liquidos, LocalDate data,
                                BigDecimal totalConferido) {
        sessao.exigir(Permissao.FINANCEIRO);
        if (liquidos.isEmpty()) {
            throw new NegocioException("Selecione os recebíveis a conciliar.");
        }
        OffsetDateTime momento = contas.momento(data, "do recebimento");
        return transacao.executar(con -> {
            List<Recebivel> recebiveis = dao.travar(con, new ArrayList<>(liquidos.keySet()));
            if (recebiveis.size() != liquidos.size()) {
                throw new NegocioException("Um dos recebíveis não existe mais. Atualize a lista.");
            }
            BigDecimal total = Dinheiro.ZERO;
            BigDecimal[] finais = new BigDecimal[recebiveis.size()];
            for (int i = 0; i < recebiveis.size(); i++) {
                Recebivel r = recebiveis.get(i);
                if (r.status() != StatusRecebivel.PREVISTO) {
                    throw new NegocioException("O recebível nº " + r.id() + " (" + r.origem() + ") não está mais "
                            + "previsto: está " + r.status().rotulo().toLowerCase() + ". Atualize a lista.");
                }
                BigDecimal informado = liquidos.get(r.id());
                BigDecimal liquido = informado == null ? r.valorLiquido()
                        : CaixaService.valor(informado, "o valor líquido", false);
                if (liquido.compareTo(r.valorBruto()) > 0) {
                    throw new NegocioException("O líquido do recebível nº " + r.id() + " ("
                            + Formatos.moeda(liquido) + ") passa do valor bruto (" + Formatos.moeda(r.valorBruto())
                            + ").");
                }
                finais[i] = liquido;
                total = total.add(liquido);
            }
            if (totalConferido != null && total.compareTo(totalConferido) != 0) {
                throw new NegocioException("O total selecionado (" + Formatos.moeda(total) + ") é diferente do total "
                        + "informado (" + Formatos.moeda(totalConferido) + "). Confira os recebíveis e os líquidos.");
            }
            for (int i = 0; i < recebiveis.size(); i++) {
                Recebivel r = recebiveis.get(i);
                dao.marcarRecebido(con, r.id(), finais[i], data);
                contas.movimentar(con, sessao, r.contaId(), TipoContaMovimento.RECEBIVEL, finais[i], momento,
                        r.origem() + " — " + r.forma(), Origem.recebivel(r.id()));
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.RECEBIVEL_CONCILIADO, ENTIDADE_RECEBIVEL,
                    recebiveis.get(0).id(), recebiveis.size() + " recebível(is) conciliado(s) em "
                            + Formatos.data(data) + ", total líquido " + Formatos.moeda(total));
            return total;
        });
    }
}
