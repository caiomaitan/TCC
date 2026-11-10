package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.InventarioDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.InclusaoInventario;
import br.com.varesync.model.InventarioResumo;
import br.com.varesync.model.ItemContagem;
import br.com.varesync.model.ItemDivergencia;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.ResultadoInventario;
import br.com.varesync.model.SituacaoProduto;
import br.com.varesync.model.StatusInventario;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Inventário (6.7, RF07 e RF08), todo com a permissão INVENTARIO: criar, incluir produtos (um, por categoria ou todos
 * os ativos), contagem cega, divergências (R13), aplicar e cancelar.
 *
 * <p>Ao aplicar, cada item contado com diferença gera um movimento {@code INVENTARIO} com a <b>diferença capturada na
 * contagem</b> (A.8): o que vendeu ou saiu entre a contagem e a aplicação continua descontado. Um produto não fica em
 * dois inventários abertos; inventário aplicado ou cancelado não muda mais.
 */
public class InventarioService {

    static final int TAMANHO_MAXIMO_DESCRICAO = 80;

    private final Transacao transacao;
    private final InventarioDAO dao;
    private final ProdutoDAO produtoDAO;
    private final CategoriaDAO categoriaDAO;
    private final EstoqueDAO estoqueDAO;
    private final EstoqueService estoqueService;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public InventarioService(Transacao transacao, InventarioDAO dao, ProdutoDAO produtoDAO, CategoriaDAO categoriaDAO,
                             EstoqueDAO estoqueDAO, EstoqueService estoqueService, AuditoriaService auditoria,
                             Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.produtoDAO = produtoDAO;
        this.categoriaDAO = categoriaDAO;
        this.estoqueDAO = estoqueDAO;
        this.estoqueService = estoqueService;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    // ----- Lista e criação -----

    /** Mais recentes primeiro; sem {@code incluirEncerrados}, só os abertos. */
    public List<InventarioResumo> listar(Sessao sessao, boolean incluirEncerrados) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> dao.listar(con, incluirEncerrados));
    }

    public InventarioResumo buscar(Sessao sessao, long inventarioId) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> resumo(con, inventarioId));
    }

    /** @return número do inventário criado (aberto e sem itens) */
    public long criar(Sessao sessao, String descricao) {
        sessao.exigir(Permissao.INVENTARIO);
        String texto = descricao(descricao);
        return transacao.executar(con -> dao.inserir(con, texto, sessao.usuarioId(), OffsetDateTime.now(clock)));
    }

    // ----- Inclusão de produtos -----

    /** Busca para incluir: só ativos; texto → nome sem acento; só dígitos → código interno ou de barras exato. */
    public List<ProdutoResumo> pesquisarProdutos(Sessao sessao, String texto) {
        sessao.exigir(Permissao.INVENTARIO);
        String termo = Textos.normalizarEspacos(texto);
        ProdutoDAO.Filtro filtro = Textos.soDigitos(termo)
                ? new ProdutoDAO.Filtro(null, termo, null, SituacaoProduto.ATIVOS)
                : new ProdutoDAO.Filtro(Textos.normalizarBusca(termo), null, null, SituacaoProduto.ATIVOS);
        return transacao.executar(con -> produtoDAO.pesquisar(con, filtro));
    }

    /** Categorias ativas, para "incluir por categoria". */
    public List<Categoria> categorias(Sessao sessao) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> categoriaDAO.listar(con, false));
    }

    /**
     * Inclui um produto pelo código de barras exato ou, se não houver, pelo código interno.
     *
     * @throws NegocioException produto não encontrado, inativo, já neste inventário ou em outro aberto
     */
    public InclusaoInventario incluirPorCodigo(Sessao sessao, long inventarioId, String codigo) {
        sessao.exigir(Permissao.INVENTARIO);
        String digitos = Textos.normalizarEspacos(codigo);
        if (!Textos.soDigitos(digitos)) {
            throw new NegocioException("Informe o código de barras ou o código interno do produto.");
        }
        return transacao.executar(con -> {
            Optional<ProdutoVenda> produto = produtoDAO.buscarParaVendaPorCodigo(con, digitos);
            if (produto.isEmpty()) {
                produto = produtoDAO.buscarParaVenda(con, ProdutoDAO.idOuNenhum(digitos));
            }
            long produtoId = produto.orElseThrow(() -> new NegocioException("Produto não encontrado.")).id();
            return incluirUm(con, inventarioId, produtoId);
        });
    }

    /** Inclui o produto escolhido na busca. */
    public InclusaoInventario incluirProduto(Sessao sessao, long inventarioId, long produtoId) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> incluirUm(con, inventarioId, produtoId));
    }

    /** Inclui os produtos ativos da categoria; os que já estão aqui ou em outro inventário aberto ficam de fora. */
    public InclusaoInventario incluirCategoria(Sessao sessao, long inventarioId, long categoriaId) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> {
            categoriaDAO.buscar(con, categoriaId).orElseThrow(() -> new NegocioException("Categoria não encontrada."));
            return incluirVarios(con, inventarioId, categoriaId);
        });
    }

    /** Inclui todos os produtos ativos; os que já estão aqui ou em outro inventário aberto ficam de fora. */
    public InclusaoInventario incluirTodosAtivos(Sessao sessao, long inventarioId) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> incluirVarios(con, inventarioId, null));
    }

    /** Tira um item do inventário aberto (com a contagem, se houver). */
    public void removerItem(Sessao sessao, long inventarioId, long itemId) {
        sessao.exigir(Permissao.INVENTARIO);
        transacao.executar(con -> {
            exigirAberto(con, inventarioId, true);
            if (dao.removerItem(con, inventarioId, itemId) == 0) {
                throw new NegocioException("Item não encontrado neste inventário.");
            }
            return null;
        });
    }

    // ----- Contagem (cega) -----

    /** Itens da aba Contagem, sem o estoque do sistema. */
    public List<ItemContagem> itensContagem(Sessao sessao, long inventarioId) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> {
            resumo(con, inventarioId);
            return dao.itensContagem(con, inventarioId);
        });
    }

    /**
     * Leitor na contagem: posiciona no item pelo código de barras exato ou pelo código interno.
     *
     * @throws NegocioException se o produto não existe ou não está neste inventário
     */
    public ItemContagem localizar(Sessao sessao, long inventarioId, String codigo) {
        sessao.exigir(Permissao.INVENTARIO);
        String digitos = Textos.normalizarEspacos(codigo);
        if (!Textos.soDigitos(digitos)) {
            throw new NegocioException("Leia o código de barras ou digite o código interno do produto.");
        }
        return transacao.executar(con -> {
            Optional<Long> itemId = dao.itemPorCodigoBarras(con, inventarioId, digitos);
            if (itemId.isEmpty()) {
                itemId = dao.itemPorProduto(con, inventarioId, ProdutoDAO.idOuNenhum(digitos));
            }
            if (itemId.isPresent()) {
                return dao.buscarItem(con, inventarioId, itemId.get()).orElseThrow();
            }
            Optional<ProdutoVenda> produto = produtoDAO.buscarParaVendaPorCodigo(con, digitos);
            if (produto.isEmpty()) {
                produto = produtoDAO.buscarParaVenda(con, ProdutoDAO.idOuNenhum(digitos));
            }
            throw new NegocioException(produto
                    .map(p -> "O produto " + p.nome() + " não está neste inventário.")
                    .orElse("Produto não encontrado."));
        });
    }

    /** Leitura na contagem (F20.3): o item e, se o código lido é de uma embalagem, o aviso; senão, aviso nulo. */
    public record LeituraContagem(ItemContagem item, String aviso) {
    }

    /**
     * Como {@link #localizar}, e o código de uma embalagem (caixa, fardo) avisa que a contagem é em unidades (F20.3):
     * a leitura só posiciona no produto.
     */
    public LeituraContagem localizarLeitura(Sessao sessao, long inventarioId, String codigo) {
        ItemContagem item = localizar(sessao, inventarioId, codigo);
        String digitos = Textos.normalizarEspacos(codigo);
        BigDecimal embalagem = transacao.executar(con -> produtoDAO.embalagemDoCodigo(con, digitos))
                .map(br.com.varesync.model.Embalagem::quantidade).orElse(BigDecimal.ONE);
        if (embalagem.compareTo(BigDecimal.ONE) == 0) {
            return new LeituraContagem(item, null);
        }
        String n = embalagem.stripTrailingZeros().toPlainString().replace('.', ',');
        return new LeituraContagem(item, "O código lido é de uma embalagem com " + n + " " + item.unidade()
                + ": a contagem é em unidades (" + item.unidade() + "). Conte as unidades soltas e as das embalagens "
                + "(a embalagem tem " + n + ").");
    }

    /**
     * Grava a contagem: quantidade contada, estoque do sistema naquele momento (lido com o saldo travado), custo
     * atual, quem e quando. A recontagem sobrescreve os cinco campos.
     *
     * @return o item atualizado
     */
    public ItemContagem registrarContagem(Sessao sessao, long inventarioId, long itemId, BigDecimal quantidade) {
        sessao.exigir(Permissao.INVENTARIO);
        if (quantidade == null) {
            throw new NegocioException("Informe a quantidade contada.");
        }
        if (quantidade.signum() < 0) {
            throw new NegocioException("A quantidade contada não pode ser negativa.");
        }
        return transacao.executar(con -> {
            exigirAberto(con, inventarioId, false);
            ItemContagem item = dao.buscarItem(con, inventarioId, itemId)
                    .orElseThrow(() -> new NegocioException("Item não encontrado neste inventário."));
            BigDecimal contada = ValidacaoProduto.quantidade(quantidade, "A quantidade contada",
                    item.unidadeFracionada());
            BigDecimal sistema = estoqueDAO.travarSaldo(con, item.produtoId())
                    .orElseThrow(() -> new NegocioException("Produto não encontrado."));
            dao.registrarContagem(con, inventarioId, itemId, contada, sistema, sessao.usuarioId(),
                    OffsetDateTime.now(clock));
            return dao.buscarItem(con, inventarioId, itemId).orElseThrow();
        });
    }

    // ----- Divergências, aplicar e cancelar -----

    /** Aba Divergências e relatório R13: sistema, contado, diferença e valor, com os totais de sobra e falta. */
    public ResultadoInventario resultado(Sessao sessao, long inventarioId) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> new ResultadoInventario(resumo(con, inventarioId),
                dao.divergencias(con, inventarioId)));
    }

    /**
     * Aplica o inventário: para cada item contado com diferença ≠ 0, um movimento {@code INVENTARIO} com quantidade =
     * diferença e custo = custo do item (na ordem do produto, como a venda, para não travar com outro caixa). Itens
     * não contados são ignorados. Depois, {@code APLICADO}, quem e quando, e a auditoria.
     *
     * @return o resultado (R13) do inventário aplicado
     * @throws NegocioException se nenhum item foi contado ou o inventário não está aberto
     */
    public ResultadoInventario aplicar(Sessao sessao, long inventarioId) {
        sessao.exigir(Permissao.INVENTARIO);
        return transacao.executar(con -> {
            exigirAberto(con, inventarioId, true);
            List<ItemDivergencia> itens = dao.divergencias(con, inventarioId);
            if (itens.stream().noneMatch(ItemDivergencia::contado)) {
                throw new NegocioException("Nenhum item foi contado. Conte ao menos um item antes de aplicar, "
                        + "ou cancele o inventário.");
            }
            List<ItemDivergencia> comDiferenca = new ArrayList<>(itens.stream()
                    .filter(ItemDivergencia::temDiferenca).toList());
            comDiferenca.sort(Comparator.comparingLong(ItemDivergencia::produtoId));
            for (ItemDivergencia item : comDiferenca) {
                estoqueService.movimentar(con, sessao, item.produtoId(), TipoMovimento.INVENTARIO, item.diferenca(),
                        OrigemMovimento.inventarioItem(item.itemId()), null, item.custoUnitario());
            }
            dao.marcarAplicado(con, inventarioId, sessao.usuarioId(), OffsetDateTime.now(clock));
            ResultadoInventario resultado = new ResultadoInventario(resumo(con, inventarioId), itens);
            auditoria.registrar(con, sessao, AcaoAuditoria.INVENTARIO_APLICADO, AuditoriaService.ENTIDADE_INVENTARIO,
                    inventarioId, descreverAplicacao(resultado));
            return resultado;
        });
    }

    /** Cancela o inventário aberto: nada muda no estoque e os produtos ficam livres para outro inventário. */
    public void cancelar(Sessao sessao, long inventarioId) {
        sessao.exigir(Permissao.INVENTARIO);
        transacao.executar(con -> {
            exigirAberto(con, inventarioId, true);
            InventarioResumo inventario = resumo(con, inventarioId);
            dao.marcarCancelado(con, inventarioId);
            auditoria.registrar(con, sessao, AcaoAuditoria.INVENTARIO_CANCELADO, AuditoriaService.ENTIDADE_INVENTARIO,
                    inventarioId, "Inventário nº " + inventarioId + " (" + inventario.descricao() + ") cancelado: "
                            + itens(inventario.totalItens()) + ", " + inventario.itensContados() + " contados. "
                            + "O estoque não foi alterado.");
            return null;
        });
    }

    // ----- Regras internas -----

    /** Descrição: trim, espaços duplos removidos, de 1 a {@value #TAMANHO_MAXIMO_DESCRICAO} caracteres. */
    static String descricao(String digitada) {
        String texto = Textos.normalizarEspacos(digitada);
        if (texto.isEmpty()) {
            throw new NegocioException("Informe a descrição do inventário.");
        }
        if (texto.length() > TAMANHO_MAXIMO_DESCRICAO) {
            throw new NegocioException("A descrição pode ter no máximo " + TAMANHO_MAXIMO_DESCRICAO + " caracteres.");
        }
        return texto;
    }

    private InventarioResumo resumo(Connection con, long inventarioId) throws SQLException {
        return dao.buscar(con, inventarioId).orElseThrow(() -> new NegocioException("Inventário não encontrado."));
    }

    /** Trava o inventário (ver {@link InventarioDAO#travar}) e recusa se não estiver aberto. */
    private void exigirAberto(Connection con, long inventarioId, boolean exclusiva) throws SQLException {
        StatusInventario status = dao.travar(con, inventarioId, exclusiva)
                .orElseThrow(() -> new NegocioException("Inventário não encontrado."));
        switch (status) {
            case APLICADO -> throw new NegocioException(
                    "O inventário nº " + inventarioId + " já foi aplicado e não pode mais ser alterado.");
            case CANCELADO -> throw new NegocioException(
                    "O inventário nº " + inventarioId + " foi cancelado e não pode mais ser alterado.");
            case ABERTO -> { }
        }
    }

    private InclusaoInventario incluirUm(Connection con, long inventarioId, long produtoId) throws SQLException {
        exigirAberto(con, inventarioId, true);
        dao.travarInclusoes(con);
        InventarioDAO.Candidato candidato = dao.candidatos(con, produtoId, null).stream().findFirst()
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
        if (!candidato.ativo()) {
            throw new NegocioException("O produto " + candidato.nome() + " está inativo e não entra no inventário. "
                    + "Para corrigir o saldo de um produto inativo, use Estoque › Ajustar saldo para….");
        }
        Long outro = candidato.inventarioAbertoId();
        if (outro != null && outro == inventarioId) {
            throw new NegocioException("O produto " + candidato.nome() + " já está neste inventário.");
        }
        if (outro != null) {
            throw new NegocioException("O produto " + candidato.nome() + " já está no inventário nº " + outro
                    + ", que está aberto. Um produto não pode estar em dois inventários abertos.");
        }
        dao.inserirItens(con, inventarioId, List.of(produtoId));
        return new InclusaoInventario(1, 0, List.of());
    }

    /** Categoria ({@code categoriaId}) ou todos os ativos (nulo). */
    private InclusaoInventario incluirVarios(Connection con, long inventarioId, Long categoriaId) throws SQLException {
        exigirAberto(con, inventarioId, true);
        dao.travarInclusoes(con);
        List<Long> incluir = new ArrayList<>();
        List<String> emOutro = new ArrayList<>();
        int jaAqui = 0;
        for (InventarioDAO.Candidato c : dao.candidatos(con, null, categoriaId)) {
            if (c.inventarioAbertoId() == null) {
                incluir.add(c.produtoId());
            } else if (c.inventarioAbertoId() == inventarioId) {
                jaAqui++;
            } else {
                emOutro.add(c.nome() + " (inventário nº " + c.inventarioAbertoId() + ")");
            }
        }
        dao.inserirItens(con, inventarioId, incluir);
        return new InclusaoInventario(incluir.size(), jaAqui, emOutro);
    }

    /** "Inventário nº 4 (Mercearia) aplicado: 10 itens, 8 contados, 3 com diferença (sobra de R$ …)…". */
    private static String descreverAplicacao(ResultadoInventario r) {
        InventarioResumo inv = r.inventario();
        StringBuilder texto = new StringBuilder("Inventário nº ").append(inv.id()).append(" (")
                .append(inv.descricao()).append(") aplicado: ").append(itens(r.itens().size())).append(", ")
                .append(r.itensContados()).append(" contados, ").append(r.itensComDiferenca())
                .append(" com diferença (sobra de ").append(Formatos.moeda(r.valorSobra()))
                .append(", falta de ").append(Formatos.moeda(r.valorFalta())).append(")");
        if (r.itensNaoContados() > 0) {
            texto.append(", ").append(r.itensNaoContados()).append(" não contados (ignorados)");
        }
        return texto.append('.').toString();
    }

    private static String itens(long quantidade) {
        return quantidade == 1 ? "1 item" : quantidade + " itens";
    }
}
