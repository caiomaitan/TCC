package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.PedidoCompraDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.ProdutoFornecedorDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.DadosPedido;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.ItemPedido;
import br.com.varesync.model.PedidoCompra;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoFornecedor;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.StatusPedido;
import br.com.varesync.report.Emissao;
import br.com.varesync.report.RelPedidoCompra;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.SugestaoCompra;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pedido de compra (F16.3), todo com a permissão COMPRAS.
 *
 * <ul>
 *   <li>Nasce RASCUNHO (à mão ou pelo "Gerar pedido" da sugestão); só o rascunho muda (fornecedor, previsão,
 *       observação e itens: um produto por pedido, quantidade na unidade de venda e custo previsto).</li>
 *   <li>Enviar: RASCUNHO com itens e fornecedor ativo → ENVIADO. A partir daí conta como pendente na sugestão e aceita
 *       entrada vinculada, que na confirmação soma a recebida e muda o pedido para PARCIAL ou RECEBIDO
 *       ({@link EntradaService#confirmar}).</li>
 *   <li>Cancelar: só RASCUNHO, ENVIADO ou PARCIAL, sem entrada aberta vinculada. O que faltava receber deixa de
 *       contar como pendente.</li>
 *   <li>Fornecedor inativo não recebe pedido novo (nem envio).</li>
 * </ul>
 */
public class PedidoCompraService {

    private final Transacao transacao;
    private final PedidoCompraDAO dao;
    private final FornecedorDAO fornecedorDAO;
    private final ProdutoDAO produtoDAO;
    private final ProdutoFornecedorDAO vinculos;
    private final ConfiguracaoDAO configuracaoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    /**
     * Resultado do "Gerar pedido" da sugestão.
     *
     * @param pedidos os rascunhos criados, um por fornecedor principal
     * @param avisos  linhas que ficaram de fora e o motivo
     */
    public record Geracao(List<PedidoCompra> pedidos, List<String> avisos) {
    }

    public PedidoCompraService(Transacao transacao, PedidoCompraDAO dao, FornecedorDAO fornecedorDAO,
                               ProdutoDAO produtoDAO, ProdutoFornecedorDAO vinculos, ConfiguracaoDAO configuracaoDAO,
                               AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.fornecedorDAO = fornecedorDAO;
        this.produtoDAO = produtoDAO;
        this.vinculos = vinculos;
        this.configuracaoDAO = configuracaoDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    // ----- Consulta -----

    /**
     * Mais recentes primeiro.
     *
     * @param situacoes só estas; vazia = todas
     * @param fornecedorId nulo = todos
     */
    public List<PedidoCompra> listar(Sessao sessao, Collection<StatusPedido> situacoes, Long fornecedorId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> dao.listar(con, situacoes, fornecedorId));
    }

    public PedidoCompra buscar(Sessao sessao, long pedidoId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> pedido(con, pedidoId));
    }

    public List<ItemPedido> itens(Sessao sessao, long pedidoId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            pedido(con, pedidoId);
            return dao.itens(con, pedidoId);
        });
    }

    /** Entradas (abertas ou confirmadas) vinculadas ao pedido. */
    public List<Long> entradas(Sessao sessao, long pedidoId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> dao.entradas(con, pedidoId, false));
    }

    /** Fornecedores ativos e, se informado, o do próprio pedido (mesmo que inativo). */
    public List<Fornecedor> fornecedores(Sessao sessao, Long manterId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> fornecedorDAO.listar(con, true).stream()
                .filter(f -> f.ativo() || Objects.equals(f.id(), manterId)).toList());
    }

    /**
     * Custo previsto proposto para o produto no pedido: o último custo do vínculo com o fornecedor do pedido; sem ele,
     * o custo do produto.
     */
    public BigDecimal custoProposto(Sessao sessao, long pedidoId, long produtoId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> custoProposto(con, pedido(con, pedidoId).fornecedorId(),
                produto(con, produtoId)));
    }

    // ----- Rascunho -----

    /** @return número do pedido criado (RASCUNHO, sem itens) */
    public long criar(Sessao sessao, DadosPedido dados) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            PedidoCompraDAO.Gravacao g = validar(con, dados, null);
            long id = dao.inserir(con, g, sessao.usuarioId(), OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.PEDIDO_CRIADO, AuditoriaService.ENTIDADE_PEDIDO, id,
                    "Pedido de compra nº " + id + " criado: " + pedido(con, id).fornecedorNome() + ".");
            return id;
        });
    }

    /** Corrige fornecedor, previsão e observação de um rascunho. */
    public void alterarDados(Sessao sessao, long pedidoId, DadosPedido dados) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirRascunho(con, pedidoId);
            dao.alterar(con, pedidoId, validar(con, dados, pedido(con, pedidoId).fornecedorId()));
            return null;
        });
    }

    /**
     * Inclui um item no rascunho.
     *
     * @param custoPrevisto nulo = {@link #custoProposto}
     * @return id do item
     */
    public long adicionarItem(Sessao sessao, long pedidoId, long produtoId, BigDecimal quantidade,
                              BigDecimal custoPrevisto) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            exigirRascunho(con, pedidoId);
            return adicionarItem(con, pedido(con, pedidoId), produtoId, quantidade, custoPrevisto);
        });
    }

    private long adicionarItem(Connection con, PedidoCompra pedido, long produtoId, BigDecimal quantidade,
                               BigDecimal custoPrevisto) throws SQLException {
        ProdutoVenda produto = produto(con, produtoId);
        if (!produto.ativo()) {
            throw new NegocioException("O produto " + produto.nome() + " está inativo. Reative-o no cadastro antes "
                    + "de pedi-lo.");
        }
        if (dao.itemDoProduto(con, pedido.id(), produtoId).isPresent()) {
            throw new NegocioException("O produto " + produto.nome() + " já está neste pedido. Altere a quantidade "
                    + "da linha em vez de lançá-lo de novo.");
        }
        BigDecimal custo = custoPrevisto == null ? custoProposto(con, pedido.fornecedorId(), produto) : custoPrevisto;
        return dao.inserirItem(con, pedido.id(), produtoId, quantidade(quantidade, produto.fracionada()),
                custo(custo));
    }

    /** Altera quantidade e custo previsto de um item do rascunho (o produto não muda). */
    public void alterarItem(Sessao sessao, long pedidoId, long itemId, BigDecimal quantidade,
                            BigDecimal custoPrevisto) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirRascunho(con, pedidoId);
            ItemPedido item = dao.buscarItem(con, pedidoId, itemId)
                    .orElseThrow(() -> new NegocioException("Item não encontrado neste pedido."));
            if (custoPrevisto == null) {
                throw new NegocioException("Informe o custo previsto.");
            }
            dao.alterarItem(con, pedidoId, itemId, quantidade(quantidade, item.fracionada()), custo(custoPrevisto));
            return null;
        });
    }

    public void removerItem(Sessao sessao, long pedidoId, long itemId) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirRascunho(con, pedidoId);
            if (dao.removerItem(con, pedidoId, itemId) == 0) {
                throw new NegocioException("Item não encontrado neste pedido.");
            }
            return null;
        });
    }

    // ----- Envio e cancelamento -----

    /** RASCUNHO com itens e fornecedor ativo → ENVIADO, com quem e quando, e auditoria. */
    public void enviar(Sessao sessao, long pedidoId) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirRascunho(con, pedidoId);
            PedidoCompra p = pedido(con, pedidoId);
            if (p.totalItens() == 0) {
                throw new NegocioException("O pedido não tem itens. Inclua os produtos antes de enviá-lo.");
            }
            exigirFornecedorAtivo(con, p.fornecedorId());
            dao.marcarEnviado(con, pedidoId, sessao.usuarioId(), OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.PEDIDO_ENVIADO, AuditoriaService.ENTIDADE_PEDIDO, pedidoId,
                    "Pedido de compra nº " + pedidoId + " enviado: " + p.fornecedorNome() + ", "
                            + itens(p.totalItens()) + ", " + Formatos.moeda(p.valorPrevisto())
                            + (p.previsaoEntrega() == null ? "" : ", previsão " + Formatos.data(p.previsaoEntrega()))
                            + ".");
            return null;
        });
    }

    /**
     * Cancela um pedido RASCUNHO, ENVIADO ou PARCIAL; o que faltava receber deixa de ser pendente.
     *
     * @throws NegocioException em outra situação ou com entrada aberta vinculada
     */
    public void cancelar(Sessao sessao, long pedidoId) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            StatusPedido status = travar(con, pedidoId);
            if (!StatusPedido.CANCELAVEIS.contains(status)) {
                throw new NegocioException("O pedido nº " + pedidoId + " está " + status.rotulo().toLowerCase(
                        Formatos.PT_BR) + " e não pode ser cancelado.");
            }
            List<Long> abertas = dao.entradas(con, pedidoId, true);
            if (!abertas.isEmpty()) {
                throw new NegocioException("A entrada nº " + abertas.get(0) + ", ainda aberta, está vinculada a este "
                        + "pedido. Desvincule-a (botão Pedido… da entrada) ou exclua-a antes de cancelar.");
            }
            PedidoCompra p = pedido(con, pedidoId);
            dao.marcarCancelado(con, pedidoId, sessao.usuarioId(), OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.PEDIDO_CANCELADO, AuditoriaService.ENTIDADE_PEDIDO,
                    pedidoId, "Pedido de compra nº " + pedidoId + " cancelado (" + status.rotulo().toLowerCase(
                            Formatos.PT_BR) + "): " + p.fornecedorNome()
                            + (status == StatusPedido.PARCIAL ? ", deixam de ser pendentes "
                            + Formatos.numero(p.quantidadePendente(), Dinheiro.ESCALA_QUANTIDADE) + " unidades" : "")
                            + ".");
            return null;
        });
    }

    // ----- Gerar pela sugestão -----

    /**
     * "Gerar pedido" da sugestão de compra (F16.3): refaz a conta da sugestão nesta transação e cria um RASCUNHO para
     * cada fornecedor principal ativo das linhas marcadas, com a quantidade da sugestão e o custo previsto do último
     * custo do vínculo (sem ele, o custo do produto). Linha sem fornecedor principal ativo ou sem sugestão fica de fora,
     * com aviso.
     *
     * @throws NegocioException se nenhuma linha marcada gera pedido
     */
    public Geracao gerarDaSugestao(Sessao sessao, Collection<Long> produtoIds) {
        sessao.exigir(Permissao.COMPRAS);
        if (produtoIds.isEmpty()) {
            throw new NegocioException("Marque os produtos da sugestão que vão para o pedido.");
        }
        Set<Long> marcados = Set.copyOf(produtoIds);
        return transacao.executar(con -> {
            List<SugestaoCompra.Linha> linhas = SugestaoCompra.consultar(con, null, null, LocalDate.now(clock),
                    clock.getZone(), configuracaoDAO.carregar(con).compras()).stream()
                    .filter(l -> marcados.contains(l.produtoId())).toList();
            List<String> avisos = new ArrayList<>();
            Map<Long, List<SugestaoCompra.Linha>> porFornecedor = new LinkedHashMap<>();
            for (SugestaoCompra.Linha l : linhas) {
                if (l.fornecedorId() == null) {
                    avisos.add(l.produto() + ": " + (l.aviso() != null ? minusculaInicial(l.aviso())
                            : "sem fornecedor principal") + " — fica de fora.");
                } else if (l.sugestao().signum() <= 0) {
                    avisos.add(l.produto() + ": sem sugestão de compra — fica de fora.");
                } else {
                    porFornecedor.computeIfAbsent(l.fornecedorId(), k -> new ArrayList<>()).add(l);
                }
            }
            if (linhas.size() < marcados.size()) {
                avisos.add((marcados.size() - linhas.size()) + " produto(s) marcado(s) não estão mais ativos — "
                        + "ficam de fora.");
            }
            if (porFornecedor.isEmpty()) {
                throw new NegocioException("Nenhum dos produtos marcados gera pedido. " + String.join(" ", avisos));
            }
            OffsetDateTime agora = OffsetDateTime.now(clock);
            List<PedidoCompra> pedidos = new ArrayList<>();
            List<Map.Entry<Long, List<SugestaoCompra.Linha>>> grupos = new ArrayList<>(porFornecedor.entrySet());
            grupos.sort(Comparator.comparing(e -> e.getValue().get(0).fornecedor(), String.CASE_INSENSITIVE_ORDER));
            for (Map.Entry<Long, List<SugestaoCompra.Linha>> grupo : grupos) {
                long fornecedorId = grupo.getKey();
                exigirFornecedorAtivo(con, fornecedorId);
                long id = dao.inserir(con, new PedidoCompraDAO.Gravacao(fornecedorId, null, null), sessao.usuarioId(),
                        agora);
                PedidoCompra rascunho = pedido(con, id);
                for (SugestaoCompra.Linha l : grupo.getValue()) {
                    adicionarItem(con, rascunho, l.produtoId(), l.sugestao(), null);
                }
                PedidoCompra criado = pedido(con, id);
                auditoria.registrar(con, sessao, AcaoAuditoria.PEDIDO_CRIADO, AuditoriaService.ENTIDADE_PEDIDO, id,
                        "Pedido de compra nº " + id + " criado pela sugestão de compra: " + criado.fornecedorNome()
                                + ", " + itens(criado.totalItens()) + ", " + Formatos.moeda(criado.valorPrevisto())
                                + ".");
                pedidos.add(criado);
            }
            return new Geracao(pedidos, avisos);
        });
    }

    // ----- PDF -----

    /** O pedido pronto para o PDF (F16.3), com a loja e quem emitiu. */
    public ResultadoRelatorio<ItemPedido> exportar(Sessao sessao, long pedidoId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            PedidoCompra p = pedido(con, pedidoId);
            String documento = fornecedorDAO.buscar(con, p.fornecedorId()).map(Fornecedor::documentoFormatado)
                    .orElse("");
            String loja = configuracaoDAO.carregar(con).loja().nome();
            return RelPedidoCompra.exportacao(p, documento, dao.itens(con, pedidoId),
                    new Emissao(loja.isBlank() ? "VareSync" : loja, sessao.usuario().nome(),
                            OffsetDateTime.now(clock)));
        });
    }

    // ----- Regras internas -----

    private PedidoCompraDAO.Gravacao validar(Connection con, DadosPedido dados, Long fornecedorAtual)
            throws SQLException {
        if (dados.fornecedorId() == null) {
            throw new NegocioException("Escolha o fornecedor.");
        }
        Fornecedor fornecedor = fornecedorDAO.buscar(con, dados.fornecedorId())
                .orElseThrow(() -> new NegocioException("Fornecedor não encontrado."));
        if (!fornecedor.ativo() && !Objects.equals(fornecedor.id(), fornecedorAtual)) {
            throw inativo(fornecedor);
        }
        if (dados.previsaoEntrega() != null && fornecedorAtual == null
                && dados.previsaoEntrega().isBefore(LocalDate.now(clock))) {
            throw new NegocioException("A previsão de entrega não pode ser antes de hoje.");
        }
        String observacao = dados.observacao() == null ? "" : dados.observacao().strip();
        return new PedidoCompraDAO.Gravacao(fornecedor.id(), dados.previsaoEntrega(),
                observacao.isEmpty() ? null : observacao);
    }

    private void exigirFornecedorAtivo(Connection con, long fornecedorId) throws SQLException {
        Fornecedor f = fornecedorDAO.buscar(con, fornecedorId)
                .orElseThrow(() -> new NegocioException("Fornecedor não encontrado."));
        if (!f.ativo()) {
            throw inativo(f);
        }
    }

    private static NegocioException inativo(Fornecedor f) {
        return new NegocioException("O fornecedor \"" + f.razaoSocial() + "\" está inativo e não recebe pedido novo. "
                + "Reative-o em Fornecedores ou escolha outro.");
    }

    private BigDecimal custoProposto(Connection con, long fornecedorId, ProdutoVenda produto) throws SQLException {
        return vinculos.doProduto(con, produto.id(), fornecedorId).map(ProdutoFornecedor::ultimoCusto)
                .filter(Objects::nonNull).orElse(produto.custo());
    }

    private ProdutoVenda produto(Connection con, long produtoId) throws SQLException {
        return produtoDAO.buscarParaVenda(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
    }

    private static BigDecimal quantidade(BigDecimal quantidade, boolean fracionada) {
        if (quantidade == null) {
            throw new NegocioException("Informe a quantidade.");
        }
        if (quantidade.signum() <= 0) {
            throw new NegocioException("A quantidade deve ser maior que zero.");
        }
        return ValidacaoProduto.quantidade(quantidade, "A quantidade", fracionada);
    }

    private static BigDecimal custo(BigDecimal custo) {
        return ValidacaoProduto.valor(custo, "o custo previsto", Dinheiro.ESCALA_CUSTO);
    }

    private PedidoCompra pedido(Connection con, long pedidoId) throws SQLException {
        return dao.buscar(con, pedidoId).orElseThrow(() -> new NegocioException("Pedido não encontrado."));
    }

    private StatusPedido travar(Connection con, long pedidoId) throws SQLException {
        return dao.travar(con, pedidoId).orElseThrow(() -> new NegocioException("Pedido não encontrado."));
    }

    /** Trava o pedido e recusa se não é rascunho (só o rascunho é editado). */
    private void exigirRascunho(Connection con, long pedidoId) throws SQLException {
        StatusPedido status = travar(con, pedidoId);
        if (status != StatusPedido.RASCUNHO) {
            throw new NegocioException("O pedido nº " + pedidoId + " está " + status.rotulo().toLowerCase(
                    Formatos.PT_BR) + ": só o rascunho pode ser alterado.");
        }
    }

    /** "Fornecedor principal inativo (Nome Ltda)…" → "fornecedor principal inativo (Nome Ltda)…" (só a primeira letra). */
    static String minusculaInicial(String texto) {
        return texto.isEmpty() ? texto : texto.substring(0, 1).toLowerCase(Formatos.PT_BR) + texto.substring(1);
    }

    private static String itens(int quantidade) {
        return quantidade == 1 ? "1 item" : quantidade + " itens";
    }
}
