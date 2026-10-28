package br.com.varesync.service;

import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.CreditoClienteDAO;
import br.com.varesync.dao.DevolucaoDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CaixaSessao;
import br.com.varesync.model.ClienteVenda;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.ComprovanteDevolucao;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosDevolucao;
import br.com.varesync.model.FormaReembolso;
import br.com.varesync.model.ItemDevolucao;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.StatusVenda;
import br.com.varesync.model.TipoCreditoCliente;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.model.Usuario;
import br.com.varesync.model.VendaDevolucao;
import br.com.varesync.model.VendaDevolvivel;
import br.com.varesync.util.CalculoDevolucao;
import br.com.varesync.util.CpfCnpj;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Devoluções e trocas (F13.2, RF17): itens de uma venda finalizada voltam com o valor e o custo proporcionais; o
 * cliente é reembolsado em dinheiro (da gaveta do caixa aberto), em vale-troca (crédito do cliente) ou fora do sistema;
 * o estoque volta ({@code DEVOLUCAO_VENDA}) ou, no item avariado, entra e sai como perda; o cashback da venda é
 * estornado na proporção do valor devolvido. Tudo numa transação, com auditoria.
 *
 * <p>Exige DEVOLUCAO ou a liberação de quem tem; depois de {@code devolucao.prazo_dias}, só com a liberação de um
 * supervisor. Travas na ordem do cancelamento de venda: caixa (no reembolso em dinheiro) → venda → saldos de estoque na
 * ordem do produto → saldo de crédito.
 */
public class DevolucaoService {

    public static final String ENTIDADE_DEVOLUCAO = "devolucao";
    /** Até quantas vendas a busca da tela Devoluções mostra. */
    public static final int LIMITE_BUSCA = DevolucaoDAO.LIMITE_BUSCA;
    static final int TAMANHO_MAXIMO_MOTIVO = 200;
    static final int TAMANHO_MAXIMO_OBSERVACAO = 500;

    private final Transacao transacao;
    private final DevolucaoDAO dao;
    private final ClienteDAO clienteDAO;
    private final CreditoClienteDAO creditoDAO;
    private final UsuarioDAO usuarioDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final EstoqueService estoqueService;
    private final CaixaService caixaService;
    private final CreditoService creditoService;
    private final AuditoriaService auditoria;
    private final Clock clock;

    /** Devolução gravada: número, valor reembolsado, cashback estornado (zero sem estorno) e o comprovante. */
    public record DevolucaoRegistrada(long id, BigDecimal valor, BigDecimal cashbackEstornado,
                                      ComprovanteDevolucao comprovante) {
    }

    public DevolucaoService(Transacao transacao, DevolucaoDAO dao, ClienteDAO clienteDAO, CreditoClienteDAO creditoDAO,
                            UsuarioDAO usuarioDAO, ConfiguracaoDAO configuracaoDAO, EstoqueService estoqueService,
                            CaixaService caixaService, CreditoService creditoService, AuditoriaService auditoria,
                            Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.clienteDAO = clienteDAO;
        this.creditoDAO = creditoDAO;
        this.usuarioDAO = usuarioDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.estoqueService = estoqueService;
        this.caixaService = caixaService;
        this.creditoService = creditoService;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /**
     * Busca da tela Devoluções (F13.4): pelo número da venda; ou pelo período e, opcional, pelo cliente (nome sem
     * acento, ou CPF/CNPJ inteiro ou parte). Só vendas finalizadas, até {@link DevolucaoDAO#LIMITE_BUSCA}.
     */
    public List<VendaDevolvivel> pesquisarVendas(Sessao sessao, Long vendaId, Periodo periodo, String cliente) {
        sessao.exigir(Permissao.DEVOLUCAO);
        String termo = Textos.normalizarEspacos(cliente);
        DevolucaoDAO.Filtro filtro;
        if (vendaId != null) {
            filtro = new DevolucaoDAO.Filtro(vendaId, null, null, null, null);
        } else {
            if (periodo == null) {
                throw new NegocioException("Informe o número da venda ou o período.");
            }
            String documento = CpfCnpj.normalizar(termo);
            boolean porDocumento = !termo.isEmpty() && documento.chars().anyMatch(Character::isDigit)
                    && documento.chars().allMatch(Character::isLetterOrDigit);
            filtro = new DevolucaoDAO.Filtro(null, periodo.inicioEm(clock.getZone()),
                    periodo.fimExclusivoEm(clock.getZone()),
                    termo.isEmpty() || porDocumento ? null : Textos.normalizarBusca(termo),
                    porDocumento ? documento : null);
        }
        return transacao.executar(con -> dao.pesquisar(con, filtro));
    }

    /** Período inicial da busca: os últimos 30 dias, contando hoje (o prazo padrão de devolução). */
    public Periodo periodoPadrao() {
        LocalDate hoje = LocalDate.now(clock);
        return new Periodo(hoje.minusDays(Configuracoes.DEVOLUCAO_PRAZO_PADRAO - 1L), hoje);
    }

    /**
     * A venda para a devolução (tela Devoluções e "Devolver itens" do F9): itens com o que já foi devolvido, prazo e as
     * devoluções anteriores. Exige DEVOLUCAO ou PDV (no PDV, quem não tem DEVOLUCAO devolve com liberação).
     */
    public VendaDevolucao venda(Sessao sessao, long vendaId) {
        sessao.exigirUma(Permissao.DEVOLUCAO, Permissao.PDV);
        return transacao.executar(con -> {
            DevolucaoDAO.Venda v = dao.venda(con, vendaId, false)
                    .orElseThrow(() -> new NegocioException("Venda nº " + vendaId + " não encontrada."));
            LocalDate prazoAte = prazoAte(v, configuracaoDAO.carregar(con));
            return new VendaDevolucao(v.id(), v.dataHora(), v.operador(), v.status(), v.valorTotal(), v.clienteId(),
                    v.clienteNome(), v.clienteId() == null ? null : documento(v.clienteCpfCnpj()),
                    v.clienteAnonimizado(), prazoAte, LocalDate.now(clock).isAfter(prazoAte), dao.itens(con, vendaId),
                    dao.daVenda(con, vendaId));
        });
    }

    /**
     * Registra a devolução (F13.2), numa transação:
     * <ol>
     * <li>reconfere a venda (travada), o prazo, as quantidades, o cliente e, no reembolso em dinheiro, o caixa aberto
     * e o dinheiro esperado;</li>
     * <li>grava {@code devolucao} e {@code devolucao_item} com o valor e o custo proporcionais;</li>
     * <li>por item, na ordem do produto: {@code DEVOLUCAO_VENDA} (+q, custo unitário da venda) e, se avariado,
     * {@code PERDA} (−q) "Devolução nº X — avariado: motivo";</li>
     * <li>vale-troca ({@code CREDITO_DEVOLUCAO}) e estorno proporcional do cashback ({@code ESTORNO_CASHBACK});</li>
     * <li>auditoria {@code DEVOLUCAO_REGISTRADA} com quem liberou.</li>
     * </ol>
     *
     * @param liberacao de quem tem DEVOLUCAO ou, fora do prazo, de um supervisor
     *                  ({@code AcessoService.liberarSupervisor}); nula se o próprio usuário pode
     * @throws PrazoDevolucaoException fora do prazo, sem supervisor que libere
     */
    public DevolucaoRegistrada registrar(Sessao sessao, DadosDevolucao dados, Liberacao liberacao) {
        Sessao conferida = sessao.conferida();
        Liberacao liberouPermissao = sessao.exigirOuLiberado(Permissao.DEVOLUCAO, liberacao);
        String motivo = Textos.normalizarEspacos(dados.motivo());
        if (motivo.isEmpty()) {
            throw new NegocioException("Informe o motivo da devolução.");
        }
        if (motivo.length() > TAMANHO_MAXIMO_MOTIVO) {
            throw new NegocioException("O motivo pode ter no máximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        String observacao = Textos.vazio(dados.observacao()) ? null : Textos.normalizarEspacos(dados.observacao());
        if (observacao != null && observacao.length() > TAMANHO_MAXIMO_OBSERVACAO) {
            throw new NegocioException("A observação pode ter no máximo " + TAMANHO_MAXIMO_OBSERVACAO
                    + " caracteres.");
        }
        FormaReembolso forma = dados.forma();
        if (forma == null) {
            throw new NegocioException("Escolha a forma de reembolso.");
        }
        Map<Long, DadosDevolucao.Item> pedidos = new LinkedHashMap<>();
        for (DadosDevolucao.Item item : dados.itens()) {
            if (item.quantidade() == null || item.quantidade().signum() <= 0) {
                continue; // item não marcado
            }
            if (pedidos.put(item.vendaItemId(), item) != null) {
                throw new IllegalArgumentException("Item repetido na devolução: " + item.vendaItemId());
            }
        }
        if (pedidos.isEmpty()) {
            throw new NegocioException("Informe a quantidade de ao menos um item a devolver.");
        }
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            CaixaSessao caixa = null;
            if (forma == FormaReembolso.DINHEIRO) {
                if (caixaService.caixaAberto(con, sessao).isEmpty()) {
                    throw new NegocioException("O reembolso em dinheiro sai da gaveta: abra o caixa deste terminal "
                            + "ou escolha outra forma de reembolso.");
                }
                caixa = caixaService.travarAberta(con, sessao);
            }
            long vendaId = dados.vendaId();
            DevolucaoDAO.Venda venda = dao.venda(con, vendaId, true)
                    .orElseThrow(() -> new NegocioException("Venda nº " + vendaId + " não encontrada."));
            if (venda.status() != StatusVenda.FINALIZADA) {
                throw new NegocioException("A venda nº " + vendaId + " está cancelada: só vendas finalizadas têm "
                        + "devolução.");
            }

            // Prazo (F13.2): passado o prazo, só com um supervisor (o próprio usuário ou quem liberou).
            Configuracoes config = configuracaoDAO.carregar(con);
            LocalDate prazoAte = prazoAte(venda, config);
            boolean foraDoPrazo = LocalDate.now(clock).isAfter(prazoAte);
            Long liberadoPor = liberouPermissao == null ? null : liberouPermissao.usuarioId();
            if (foraDoPrazo && !conferida.supervisor()) {
                if (!supervisorAtivo(con, liberacao)) {
                    LocalDate dataVenda = prazoAte.minusDays(config.devolucaoPrazoDias());
                    throw new PrazoDevolucaoException("A venda nº " + vendaId + " é de " + Formatos.data(dataVenda)
                            + ": o prazo de devolução ("
                            + config.devolucaoPrazoDias() + " dias) terminou em " + Formatos.data(prazoAte)
                            + ". A devolução precisa da liberação de um supervisor.");
                }
                liberadoPor = liberacao.usuarioId();
            }

            // Itens e quantidades, com o que já foi devolvido (a venda travada impede outra devolução em paralelo).
            Map<Long, ItemDevolucao> itensDaVenda = new LinkedHashMap<>();
            for (ItemDevolucao i : dao.itens(con, vendaId)) {
                itensDaVenda.put(i.vendaItemId(), i);
            }
            record Devolvido(ItemDevolucao item, BigDecimal quantidade, boolean avariado, BigDecimal valor,
                             BigDecimal custo) {
            }
            List<Devolvido> devolvidos = new ArrayList<>();
            for (DadosDevolucao.Item pedido : pedidos.values()) {
                ItemDevolucao item = itensDaVenda.get(pedido.vendaItemId());
                if (item == null) {
                    throw new NegocioException("O item escolhido não é da venda nº " + vendaId + ".");
                }
                if (item.cancelado()) {
                    throw new NegocioException("O item " + item.sequencia() + " (" + item.descricao() + ") foi "
                            + "cancelado durante a venda e não pode ser devolvido.");
                }
                BigDecimal q = ValidacaoProduto.quantidade(pedido.quantidade(), "A quantidade devolvida de \""
                        + item.descricao() + "\"", item.fracionada());
                if (q.compareTo(item.disponivel()) > 0) {
                    throw new NegocioException("A quantidade devolvida de \"" + item.descricao() + "\" ("
                            + quantidade(q, item) + ") passa do que pode ser devolvido: vendida "
                            + quantidade(item.quantidade(), item) + (item.quantidadeDevolvida().signum() > 0
                            ? ", já devolvida " + quantidade(item.quantidadeDevolvida(), item) : "") + ".");
                }
                devolvidos.add(new Devolvido(item, q, pedido.avariado(), item.valor(q), item.custo(q)));
            }
            BigDecimal total = devolvidos.stream().map(Devolvido::valor).reduce(Dinheiro.ZERO, BigDecimal::add);

            // Cliente: o da venda; numa venda sem cliente, o vale-troca vai para quem foi identificado agora.
            Long clienteId = venda.clienteId();
            if (forma == FormaReembolso.CREDITO_CLIENTE) {
                if (clienteId != null && venda.clienteAnonimizado()) {
                    throw new NegocioException("O cliente da venda nº " + vendaId + " foi anonimizado (LGPD): não há "
                            + "a quem dar o vale-troca. Reembolse em dinheiro ou por outra forma.");
                }
                if (clienteId == null) {
                    if (dados.clienteId() == null) {
                        throw new NegocioException("O vale-troca fica no crédito do cliente: identifique ou cadastre o "
                                + "cliente.");
                    }
                    clienteId = clienteAtivo(con, dados.clienteId()).id();
                } else if (dados.clienteId() != null && dados.clienteId().longValue() != clienteId) {
                    throw new NegocioException("O vale-troca vai para o cliente da venda (" + venda.clienteNome()
                            + ").");
                }
            } else if (clienteId == null && dados.clienteId() != null) {
                clienteId = clienteAtivo(con, dados.clienteId()).id();
            }

            // Reembolso em dinheiro: até o que deveria estar na gaveta (sem mostrar o valor: fechamento às cegas).
            if (caixa != null && total.compareTo(caixaService.dinheiroEsperado(con, caixa.id())) > 0) {
                throw new NegocioException("O valor da devolução (" + Formatos.moeda(total) + ") é maior que o "
                        + "dinheiro que deveria estar na gaveta. Escolha outra forma de reembolso.");
            }

            long devolucaoId = dao.inserir(con, new DevolucaoDAO.Nova(vendaId, sessao.usuarioId(), agora, motivo, forma,
                    total, clienteId, liberadoPor, observacao, caixa == null ? null : caixa.id()));
            record Gravado(Devolvido devolvido, long itemId) {
            }
            List<Gravado> gravados = new ArrayList<>();
            for (Devolvido d : devolvidos) {
                gravados.add(new Gravado(d, dao.inserirItem(con, devolucaoId, d.item().vendaItemId(), d.quantidade(),
                        d.valor(), d.custo(), !d.avariado())));
            }
            gravados.sort(Comparator.comparingLong((Gravado g) -> g.devolvido().item().produtoId())
                    .thenComparingInt(g -> g.devolvido().item().sequencia()));
            String perda = "Devolução nº " + devolucaoId + " — avariado: " + motivo;
            String motivoPerda = perda.length() > EstoqueService.TAMANHO_MAXIMO_MOTIVO
                    ? perda.substring(0, EstoqueService.TAMANHO_MAXIMO_MOTIVO) : perda;
            for (Gravado g : gravados) {
                ItemDevolucao item = g.devolvido().item();
                estoqueService.movimentar(con, sessao, item.produtoId(), TipoMovimento.DEVOLUCAO_VENDA,
                        g.devolvido().quantidade(), OrigemMovimento.devolucaoItem(g.itemId()), null,
                        item.custoUnitario());
                if (g.devolvido().avariado()) {
                    estoqueService.movimentar(con, sessao, item.produtoId(), TipoMovimento.PERDA,
                            g.devolvido().quantidade().negate(), OrigemMovimento.NENHUMA, motivoPerda,
                            item.custoUnitario());
                }
            }

            // Crédito do cliente: vale-troca e estorno do cashback da venda, na proporção do valor devolvido.
            List<String> credito = new ArrayList<>();
            if (forma == FormaReembolso.CREDITO_CLIENTE && total.signum() > 0) {
                creditoService.movimentar(con, sessao, clienteId, TipoCreditoCliente.CREDITO_DEVOLUCAO, total, vendaId,
                        devolucaoId, null);
                credito.add("vale-troca de " + Formatos.moeda(total));
            }
            BigDecimal estorno = Dinheiro.ZERO;
            Optional<CreditoClienteDAO.DaVenda> cashback = creditoDAO.daVenda(con, vendaId).stream()
                    .filter(m -> m.tipo() == TipoCreditoCliente.CASHBACK).findFirst();
            if (cashback.isPresent()) {
                Set<Long> devolvidosAgora = new HashSet<>();
                devolvidos.stream().filter(d -> d.quantidade().compareTo(d.item().disponivel()) == 0)
                        .forEach(d -> devolvidosAgora.add(d.item().vendaItemId()));
                boolean completa = itensDaVenda.values().stream().filter(i -> !i.cancelado())
                        .allMatch(i -> i.disponivel().signum() == 0 || devolvidosAgora.contains(i.vendaItemId()));
                estorno = CalculoDevolucao.estornoCashback(cashback.get().valor(),
                        creditoDAO.cashbackEstornadoEmDevolucoes(con, vendaId), venda.valorTotal(), total, completa);
                if (estorno.signum() > 0) {
                    creditoService.movimentar(con, sessao, cashback.get().clienteId(),
                            TipoCreditoCliente.ESTORNO_CASHBACK, estorno.negate(), vendaId, devolucaoId, null);
                    credito.add("estorno de cashback de " + Formatos.moeda(estorno));
                }
            }

            String liberou = liberadoPor == null ? "" : " (" + (liberouPermissao != null
                    ? liberouPermissao.descricao() : liberacao.descricao())
                    + (foraDoPrazo ? ", fora do prazo" : "") + ")";
            auditoria.registrar(con, sessao, AcaoAuditoria.DEVOLUCAO_REGISTRADA, ENTIDADE_DEVOLUCAO, devolucaoId,
                    "Devolução nº " + devolucaoId + " da venda nº " + vendaId + ": " + devolvidos.size()
                            + " item(ns), " + Formatos.moeda(total) + " em " + forma.rotulo().toLowerCase()
                            + (caixa == null ? "" : " (caixa nº " + caixa.id() + ")") + ". Motivo: " + motivo
                            + liberou
                            + (credito.isEmpty() ? "" : ". Crédito do cliente: " + String.join(" e ", credito)));
            return new DevolucaoRegistrada(devolucaoId, total, estorno, montarComprovante(con, devolucaoId, config));
        });
    }

    /** Comprovante de uma devolução (reimpressão na tela Devoluções). */
    public ComprovanteDevolucao comprovante(Sessao sessao, long devolucaoId) {
        sessao.exigirUma(Permissao.DEVOLUCAO, Permissao.PDV);
        return transacao.executar(con -> montarComprovante(con, devolucaoId, configuracaoDAO.carregar(con)));
    }

    // ----- Apoio -----

    /** Último dia sem liberação: a data da venda (no fuso do sistema) mais o prazo. */
    private LocalDate prazoAte(DevolucaoDAO.Venda venda, Configuracoes config) {
        return venda.dataHora().atZoneSameInstant(clock.getZone()).toLocalDate().plusDays(config.devolucaoPrazoDias());
    }

    /** A liberação é de um supervisor ativo (conferido no banco, como em {@link Sessao#exigirOuLiberado})? */
    private boolean supervisorAtivo(Connection con, Liberacao liberacao) throws SQLException {
        if (liberacao == null) {
            return false;
        }
        Optional<Usuario> quem = usuarioDAO.buscarPorId(con, liberacao.usuarioId());
        return quem.isPresent() && quem.get().ativo() && quem.get().supervisor();
    }

    private ClienteVenda clienteAtivo(Connection con, long clienteId) throws SQLException {
        ClienteVenda cliente = clienteDAO.buscarParaVenda(con, clienteId)
                .orElseThrow(() -> new NegocioException("Cliente não encontrado."));
        if (!cliente.ativo()) {
            throw new NegocioException("O cliente \"" + cliente.nome() + "\" está inativo. Escolha outro cliente.");
        }
        return cliente;
    }

    private ComprovanteDevolucao montarComprovante(Connection con, long devolucaoId, Configuracoes config)
            throws SQLException {
        DevolucaoDAO.Cabecalho c = dao.cabecalho(con, devolucaoId)
                .orElseThrow(() -> new NegocioException("Devolução nº " + devolucaoId + " não encontrada."));
        Comprovante.Loja loja = new Comprovante.Loja(config.loja().nome(), config.loja().documento(),
                config.loja().endereco(), config.loja().telefone());
        List<CreditoClienteDAO.DaVenda> credito = creditoDAO.daDevolucao(con, devolucaoId);
        BigDecimal estornado = credito.stream().filter(m -> m.tipo() == TipoCreditoCliente.ESTORNO_CASHBACK)
                .map(m -> m.valor().negate()).findFirst().orElse(Dinheiro.ZERO);
        // Saldo do cliente quando a devolução mexeu no crédito dele (vale-troca ou estorno do cashback).
        BigDecimal saldo = null;
        if (!credito.isEmpty()) {
            saldo = creditoDAO.saldo(con, credito.get(0).clienteId()).orElse(Dinheiro.ZERO);
        }
        return new ComprovanteDevolucao(loja, c.id(), c.dataHora(), c.vendaId(), c.vendaDataHora(), c.usuario(),
                c.liberadoPor(), c.clienteNome() == null ? null
                : new Comprovante.Cliente(c.clienteNome(), documento(c.clienteCpfCnpj())),
                dao.itensComprovante(con, devolucaoId), c.valorTotal(), c.forma(), c.motivo(), c.observacao(),
                estornado, saldo, config.rodapeComprovante());
    }

    private static String documento(String cpfCnpj) {
        return cpfCnpj == null ? "" : CpfCnpj.ocultar(cpfCnpj);
    }

    private static String quantidade(BigDecimal valor, ItemDevolucao item) {
        return Formatos.quantidade(valor, item.fracionada()) + " " + item.unidade();
    }
}
