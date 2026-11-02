package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.EntradaDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CustoAtualizado;
import br.com.varesync.model.DadosEntrada;
import br.com.varesync.model.EntradaResumo;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.ItemEntrada;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.ParcelaEntrada;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.ResultadoEntrada;
import br.com.varesync.model.SituacaoProduto;
import br.com.varesync.model.StatusEntrada;
import br.com.varesync.model.SugestaoReajuste;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.util.CustoMedio;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Precificacao;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Entrada de mercadoria (6.10), toda com a permissão COMPRAS: a nota (fornecedor, número, emissão, valor total) e os
 * itens ficam {@code ABERTA} enquanto se digita; confirmar dá entrada no estoque e recalcula o custo médio (A.5).
 *
 * <p>Confirmar, numa transação, item a item (na ordem do produto, como a venda, para não travar com outro caixa):
 * trava o saldo ({@code SELECT … FROM produto_estoque … FOR UPDATE}), calcula o novo custo médio, grava custo e último
 * custo no produto (o trigger registra o histórico) e insere o movimento {@code ENTRADA_COMPRA} com o custo da nota.
 * Depois, {@code CONFIRMADA}, quem e quando, e a auditoria. Entrada confirmada não muda mais.
 */
public class EntradaService {

    static final int TAMANHO_DOCUMENTO = 20;

    private final Transacao transacao;
    private final EntradaDAO dao;
    private final FornecedorDAO fornecedorDAO;
    private final ProdutoDAO produtoDAO;
    private final EstoqueDAO estoqueDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final EstoqueService estoqueService;
    private final ContaPagarService contasPagar;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public EntradaService(Transacao transacao, EntradaDAO dao, FornecedorDAO fornecedorDAO, ProdutoDAO produtoDAO,
                          EstoqueDAO estoqueDAO, ConfiguracaoDAO configuracaoDAO, EstoqueService estoqueService,
                          ContaPagarService contasPagar, AuditoriaService auditoria, Clock clock) {
        this.contasPagar = contasPagar;
        this.transacao = transacao;
        this.dao = dao;
        this.fornecedorDAO = fornecedorDAO;
        this.produtoDAO = produtoDAO;
        this.estoqueDAO = estoqueDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.estoqueService = estoqueService;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    // ----- Lista e nota -----

    /** Mais recentes primeiro; sem {@code incluirConfirmadas}, só as abertas. */
    public List<EntradaResumo> listar(Sessao sessao, boolean incluirConfirmadas) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> dao.listar(con, incluirConfirmadas));
    }

    public EntradaResumo buscar(Sessao sessao, long entradaId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> resumo(con, entradaId));
    }

    public List<ItemEntrada> itens(Sessao sessao, long entradaId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            resumo(con, entradaId);
            return dao.itens(con, entradaId);
        });
    }

    /**
     * Fornecedores para escolher na nota: os ativos e, se informado, o da própria entrada (mesmo que inativo).
     *
     * @param manterId fornecedor atual da entrada em edição; nulo na entrada nova
     */
    public List<Fornecedor> fornecedores(Sessao sessao, Long manterId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            List<Fornecedor> todos = fornecedorDAO.listar(con, true);
            return todos.stream().filter(f -> f.ativo() || Objects.equals(f.id(), manterId)).toList();
        });
    }

    /** @return número da entrada criada (aberta e sem itens) */
    public long criar(Sessao sessao, DadosEntrada dados) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> criar(con, sessao, dados));
    }

    /** Cria a entrada na transação de quem chama (importação do XML da NF-e), com as mesmas validações. */
    long criar(Connection con, Sessao sessao, DadosEntrada dados) throws SQLException {
        EntradaDAO.Gravacao g = validar(con, dados, null, null);
        return dao.inserir(con, g, sessao.usuarioId(), OffsetDateTime.now(clock));
    }

    /** Corrige os dados da nota de uma entrada aberta. */
    public void alterarDados(Sessao sessao, long entradaId, DadosEntrada dados) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirAberta(con, entradaId);
            EntradaResumo atual = resumo(con, entradaId);
            dao.alterar(con, entradaId, validar(con, dados, entradaId, atual.fornecedorId()));
            return null;
        });
    }

    /** Exclui a entrada aberta com os itens (6.10: "ABERTA: pode editar e excluir"). */
    public void excluir(Sessao sessao, long entradaId) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirAberta(con, entradaId);
            dao.excluir(con, entradaId);
            return null;
        });
    }

    // ----- Itens -----

    /** Busca para incluir: só ativos; texto → nome sem acento; só dígitos → código interno ou de barras exato. */
    public List<ProdutoResumo> pesquisarProdutos(Sessao sessao, String texto) {
        sessao.exigir(Permissao.COMPRAS);
        String termo = Textos.normalizarEspacos(texto);
        ProdutoDAO.Filtro filtro = Textos.soDigitos(termo)
                ? new ProdutoDAO.Filtro(null, termo, null, SituacaoProduto.ATIVOS)
                : new ProdutoDAO.Filtro(Textos.normalizarBusca(termo), null, null, SituacaoProduto.ATIVOS);
        return transacao.executar(con -> produtoDAO.pesquisar(con, filtro));
    }

    /**
     * Produto pelo código de barras exato ou, se não houver, pelo código interno.
     *
     * @throws NegocioException se não existe ou está inativo
     */
    public ProdutoVenda produtoPorCodigo(Sessao sessao, String codigo) {
        sessao.exigir(Permissao.COMPRAS);
        String digitos = Textos.normalizarEspacos(codigo);
        if (!Textos.soDigitos(digitos)) {
            throw new NegocioException("Informe o código de barras ou o código interno do produto.");
        }
        return transacao.executar(con -> {
            Optional<ProdutoVenda> produto = produtoDAO.buscarParaVendaPorCodigo(con, digitos);
            if (produto.isEmpty()) {
                produto = produtoDAO.buscarParaVenda(con, ProdutoDAO.idOuNenhum(digitos));
            }
            return ativo(produto.orElseThrow(() -> new NegocioException("Produto não encontrado.")));
        });
    }

    /**
     * Inclui um item. Informe o custo unitário <b>ou</b> o valor total da linha: com o valor total, o custo é
     * calculado com 4 casas (valor ÷ quantidade); com o custo, o valor total é round(quantidade × custo, 2).
     *
     * @param valorTotal se não nulo, prevalece sobre {@code custoUnitario}
     * @return id do item
     */
    public long adicionarItem(Sessao sessao, long entradaId, long produtoId, BigDecimal quantidade,
                              BigDecimal custoUnitario, BigDecimal valorTotal) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            exigirAberta(con, entradaId);
            return adicionarItem(con, entradaId, produtoId, quantidade, custoUnitario, valorTotal);
        });
    }

    /** Inclui o item na transação de quem chama (entrada aberta já conferida). */
    long adicionarItem(Connection con, long entradaId, long produtoId, BigDecimal quantidade,
                       BigDecimal custoUnitario, BigDecimal valorTotal) throws SQLException {
        ProdutoVenda produto = ativo(produtoDAO.buscarParaVenda(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado.")));
        if (dao.itemDoProduto(con, entradaId, produtoId).isPresent()) {
            throw new NegocioException("O produto " + produto.nome() + " já está nesta entrada. Altere a "
                    + "quantidade da linha em vez de lançá-lo de novo.");
        }
        Valores v = valores(quantidade, custoUnitario, valorTotal, produto.fracionada());
        return dao.inserirItem(con, entradaId, produtoId, v.quantidade(), v.custo(), v.total());
    }

    /** Altera quantidade e custo (ou valor total) de um item; o produto não muda (remova e inclua outro). */
    public void alterarItem(Sessao sessao, long entradaId, long itemId, BigDecimal quantidade,
                            BigDecimal custoUnitario, BigDecimal valorTotal) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirAberta(con, entradaId);
            ItemEntrada item = dao.buscarItem(con, entradaId, itemId)
                    .orElseThrow(() -> new NegocioException("Item não encontrado nesta entrada."));
            Valores v = valores(quantidade, custoUnitario, valorTotal, item.unidadeFracionada());
            dao.alterarItem(con, entradaId, itemId, v.quantidade(), v.custo(), v.total());
            return null;
        });
    }

    public void removerItem(Sessao sessao, long entradaId, long itemId) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirAberta(con, entradaId);
            if (dao.removerItem(con, entradaId, itemId) == 0) {
                throw new NegocioException("Item não encontrado nesta entrada.");
            }
            return null;
        });
    }

    // ----- Parcelas (F15.3) -----

    public List<ParcelaEntrada> parcelas(Sessao sessao, long entradaId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> dao.parcelas(con, entradaId));
    }

    /** Troca as parcelas de uma entrada aberta, numeradas na ordem informada (lista vazia: sem parcelas). */
    public void salvarParcelas(Sessao sessao, long entradaId, List<ParcelaEntrada> parcelas) {
        sessao.exigir(Permissao.COMPRAS);
        transacao.executar(con -> {
            exigirAberta(con, entradaId);
            salvarParcelas(con, entradaId, parcelas);
            return null;
        });
    }

    /** Grava as parcelas na transação de quem chama (entrada aberta já conferida; importação da NF-e). */
    void salvarParcelas(Connection con, long entradaId, List<ParcelaEntrada> parcelas) throws SQLException {
        List<ParcelaEntrada> numeradas = new ArrayList<>();
        for (ParcelaEntrada p : parcelas) {
            int numero = numeradas.size() + 1;
            if (p.vencimento() == null) {
                throw new NegocioException("Informe o vencimento da parcela " + numero + ".");
            }
            numeradas.add(new ParcelaEntrada(numero, p.vencimento(),
                    CaixaService.valor(p.valor(), "o valor da parcela " + numero, false)));
        }
        dao.substituirParcelas(con, entradaId, numeradas);
    }

    /**
     * Aviso (F15.3): a soma das parcelas é diferente do valor da nota (sem valor informado, da soma dos itens). Sem
     * parcelas, não há aviso. Não impede a confirmação.
     */
    public static Optional<String> avisoParcelas(EntradaResumo e, List<ParcelaEntrada> parcelas) {
        if (parcelas.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal soma = parcelas.stream().map(ParcelaEntrada::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nota = e.valorTotalNota() == null ? e.somaItens() : e.valorTotalNota();
        return soma.compareTo(nota) == 0 ? Optional.empty() : Optional.of("A soma das parcelas ("
                + Formatos.moeda(soma) + ") é diferente do valor " + (e.valorTotalNota() == null ? "dos itens" : "da nota")
                + " (" + Formatos.moeda(nota) + ").");
    }

    // ----- Confirmação -----

    /**
     * Confirma a entrada: estoque e custo médio de cada item, {@code CONFIRMADA} e auditoria. A diferença entre a soma
     * dos itens e o valor total da nota não impede (a tela avisa antes, {@link EntradaResumo#avisoTotal()}).
     *
     * @return os custos recalculados e os produtos que ficaram abaixo da margem mínima, com o preço sugerido
     * @throws NegocioException entrada sem itens, já confirmada ou inexistente
     */
    public ResultadoEntrada confirmar(Sessao sessao, long entradaId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            exigirAberta(con, entradaId);
            List<ItemEntrada> itens = new ArrayList<>(dao.itens(con, entradaId));
            if (itens.isEmpty()) {
                throw new NegocioException("A entrada não tem itens. Inclua os produtos da nota antes de confirmar.");
            }
            itens.sort(Comparator.comparingLong(ItemEntrada::produtoId));
            OffsetDateTime agora = OffsetDateTime.now(clock);
            BigDecimal margemMinima = configuracaoDAO.carregar(con).margemMinimaPct();
            List<CustoAtualizado> custos = new ArrayList<>();
            List<SugestaoReajuste> sugestoes = new ArrayList<>();
            for (ItemEntrada item : itens) {
                BigDecimal saldo = estoqueDAO.travarSaldo(con, item.produtoId())
                        .orElseThrow(() -> new NegocioException("Produto não encontrado."));
                ProdutoDAO.CustoTravado produto = produtoDAO.travarCusto(con, item.produtoId())
                        .orElseThrow(() -> new NegocioException("Produto não encontrado."));
                BigDecimal novo = CustoMedio.calcular(saldo, produto.custo(), item.quantidade(),
                        item.custoUnitario());
                produtoDAO.atualizarCusto(con, item.produtoId(), novo, item.custoUnitario(), sessao.usuarioId(),
                        agora);
                estoqueService.movimentar(con, sessao, item.produtoId(), TipoMovimento.ENTRADA_COMPRA,
                        item.quantidade(), OrigemMovimento.entradaItem(item.id()), null, item.custoUnitario());
                custos.add(new CustoAtualizado(item.produtoId(), produto.nome(), saldo, produto.custo(),
                        item.quantidade(), item.custoUnitario(), novo));
                sugestao(item.produtoId(), produto.nome(), novo, produto.precoVenda(), margemMinima)
                        .ifPresent(sugestoes::add);
            }
            dao.marcarConfirmada(con, entradaId, sessao.usuarioId(), agora);
            EntradaResumo confirmada = resumo(con, entradaId);
            int titulos = contasPagar.gerarDaEntrada(con, sessao, confirmada, dao.parcelas(con, entradaId));
            auditoria.registrar(con, sessao, AcaoAuditoria.ENTRADA_CONFIRMADA, AuditoriaService.ENTIDADE_ENTRADA,
                    entradaId, descreverConfirmacao(confirmada, custos)
                            + (titulos == 0 ? "" : " " + titulos + " título(s) a pagar gerado(s)."));
            return new ResultadoEntrada(confirmada, custos, sugestoes, margemMinima);
        });
    }

    // ----- Regras internas -----

    /** Quantidade, custo unitário (4 casas) e valor total (2 casas) de um item. */
    record Valores(BigDecimal quantidade, BigDecimal custo, BigDecimal total) {
    }

    static Valores valores(BigDecimal quantidade, BigDecimal custoUnitario, BigDecimal valorTotal,
                           boolean fracionada) {
        if (quantidade == null) {
            throw new NegocioException("Informe a quantidade.");
        }
        if (quantidade.signum() <= 0) {
            throw new NegocioException("A quantidade deve ser maior que zero.");
        }
        BigDecimal qtd = ValidacaoProduto.quantidade(quantidade, "A quantidade", fracionada);
        if (valorTotal != null) {
            BigDecimal total = ValidacaoProduto.valor(valorTotal, "o valor total da linha", Dinheiro.ESCALA_DINHEIRO);
            BigDecimal custo = total.divide(qtd, Dinheiro.ESCALA_CUSTO, Dinheiro.ARREDONDAMENTO);
            return new Valores(qtd, custo, total);
        }
        if (custoUnitario == null) {
            throw new NegocioException("Informe o custo unitário ou o valor total da linha.");
        }
        BigDecimal custo = ValidacaoProduto.valor(custoUnitario, "o custo unitário", Dinheiro.ESCALA_CUSTO);
        BigDecimal total = Dinheiro.multiplicar(qtd, custo);
        ValidacaoProduto.valor(total, "o valor total da linha", Dinheiro.ESCALA_DINHEIRO);
        return new Valores(qtd, custo, total);
    }

    /**
     * Margem abaixo da mínima com o novo custo (comparada sem arredondar: lucro × 100 &lt; mínima × preço) ou preço
     * zero (sem margem) → sugestão com o preço que dá a margem mínima.
     */
    static Optional<SugestaoReajuste> sugestao(long produtoId, String nome, BigDecimal custo, BigDecimal preco,
                                               BigDecimal margemMinima) {
        BigDecimal lucro = Precificacao.lucroUnitario(custo, preco);
        boolean abaixo = preco.signum() == 0
                || lucro.multiply(BigDecimal.valueOf(100)).compareTo(margemMinima.multiply(preco)) < 0;
        if (!abaixo) {
            return Optional.empty();
        }
        return Optional.of(new SugestaoReajuste(produtoId, nome, custo, preco,
                Precificacao.margem(custo, preco).orElse(null),
                Precificacao.precoSugerido(custo, margemMinima).orElse(null)));
    }

    private EntradaDAO.Gravacao validar(Connection con, DadosEntrada dados, Long entradaId, Long fornecedorAtual)
            throws SQLException {
        if (dados.fornecedorId() == null) {
            throw new NegocioException("Escolha o fornecedor.");
        }
        Fornecedor fornecedor = fornecedorDAO.buscar(con, dados.fornecedorId())
                .orElseThrow(() -> new NegocioException("Fornecedor não encontrado."));
        if (!fornecedor.ativo() && !Objects.equals(fornecedor.id(), fornecedorAtual)) {
            throw new NegocioException("O fornecedor \"" + fornecedor.razaoSocial() + "\" está inativo.");
        }
        String numero = Textos.normalizarEspacos(dados.numeroDocumento());
        if (numero.length() > TAMANHO_DOCUMENTO) {
            throw new NegocioException("O número do documento pode ter no máximo " + TAMANHO_DOCUMENTO
                    + " caracteres.");
        }
        LocalDate emissao = dados.dataEmissao();
        if (emissao != null && emissao.isAfter(LocalDate.now(clock))) {
            throw new NegocioException("A data de emissão não pode ser depois de hoje.");
        }
        BigDecimal valorNota = dados.valorTotalNota() == null ? null
                : ValidacaoProduto.valor(dados.valorTotalNota(), "o valor total da nota", Dinheiro.ESCALA_DINHEIRO);
        if (!numero.isEmpty()) {
            Optional<Long> outra = dao.mesmaNota(con, fornecedor.id(), numero, entradaId);
            if (outra.isPresent()) {
                throw new NegocioException("Esta nota já foi lançada (entrada nº " + outra.get() + "): NF "
                        + numero + " de " + fornecedor.razaoSocial() + ".");
            }
        }
        return new EntradaDAO.Gravacao(fornecedor.id(), numero.isEmpty() ? null : numero, emissao, valorNota);
    }

    private EntradaResumo resumo(Connection con, long entradaId) throws SQLException {
        return dao.buscar(con, entradaId).orElseThrow(() -> new NegocioException("Entrada não encontrada."));
    }

    /** Trava a entrada e recusa se já confirmada (entrada confirmada é imutável). */
    private void exigirAberta(Connection con, long entradaId) throws SQLException {
        StatusEntrada status = dao.travar(con, entradaId)
                .orElseThrow(() -> new NegocioException("Entrada não encontrada."));
        if (status == StatusEntrada.CONFIRMADA) {
            throw new NegocioException("A entrada nº " + entradaId + " já foi confirmada e não pode mais ser "
                    + "alterada. Para corrigir o estoque, use Estoque › Ajustar estoque.");
        }
    }

    private static ProdutoVenda ativo(ProdutoVenda produto) {
        if (!produto.ativo()) {
            throw new NegocioException("O produto " + produto.nome() + " está inativo. Reative-o no cadastro antes "
                    + "de lançar a entrada.");
        }
        return produto;
    }

    /** "Entrada nº 9 confirmada: Distribuidora X, NF 1234, 2 itens, R$ 120,00. Custo médio: …". */
    private static String descreverConfirmacao(EntradaResumo e, List<CustoAtualizado> custos) {
        StringBuilder texto = new StringBuilder("Entrada nº ").append(e.id()).append(" confirmada: ")
                .append(e.fornecedorNome());
        if (e.numeroDocumento() != null) {
            texto.append(", NF ").append(e.numeroDocumento());
        }
        texto.append(", ").append(e.totalItens() == 1 ? "1 item" : e.totalItens() + " itens").append(", ")
                .append(Formatos.moeda(e.somaItens()));
        if (e.valorTotalNota() != null && e.valorTotalNota().compareTo(e.somaItens()) != 0) {
            texto.append(" (valor da nota ").append(Formatos.moeda(e.valorTotalNota())).append(")");
        }
        texto.append(". Custo médio: ");
        for (int i = 0; i < custos.size(); i++) {
            CustoAtualizado c = custos.get(i);
            texto.append(i == 0 ? "" : "; ").append(ProdutoService.descrever(c.produtoId(), c.produtoNome()))
                    .append(" de ").append(custo(c.custoAnterior())).append(" para ").append(custo(c.custoNovo()));
        }
        return texto.append('.').toString();
    }

    private static String custo(BigDecimal valor) {
        return "R$ " + Formatos.numero(valor, Dinheiro.ESCALA_CUSTO);
    }
}
