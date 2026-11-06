package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.UnidadeMedidaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.HistoricoPreco;
import br.com.varesync.model.OrigemMovimento;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Produto;
import br.com.varesync.model.ProdutoResumo;
import br.com.varesync.model.SituacaoProduto;
import br.com.varesync.model.TipoMovimento;
import br.com.varesync.model.UnidadeMedida;
import br.com.varesync.util.Dinheiro;
import br.com.varesync.util.Formatos;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cadastro de produtos (6.3, RF01–RF03): validações, códigos de barras, estoque inicial, inativar/excluir,
 * busca e histórico de preço. Todos os métodos exigem a permissão PRODUTOS.
 */
public class ProdutoService {

    public static final String MOTIVO_ESTOQUE_INICIAL = "Estoque inicial";
    public static final String AVISO_ABAIXO_DO_CUSTO = "O preço está abaixo do custo: cada venda dará prejuízo.";
    public static final String AVISO_PRECO_ZERO = "Com preço zero, o produto não pode ser vendido no PDV.";

    private final Transacao transacao;
    private final ProdutoDAO produtoDAO;
    private final CategoriaDAO categoriaDAO;
    private final UnidadeMedidaDAO unidadeDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final EstoqueService estoqueService;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public ProdutoService(Transacao transacao, ProdutoDAO produtoDAO, CategoriaDAO categoriaDAO,
                          UnidadeMedidaDAO unidadeDAO, ConfiguracaoDAO configuracaoDAO, EstoqueService estoqueService,
                          AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.produtoDAO = produtoDAO;
        this.categoriaDAO = categoriaDAO;
        this.unidadeDAO = unidadeDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.estoqueService = estoqueService;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    public List<UnidadeMedida> unidades(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(unidadeDAO::listar);
    }

    /** O campo PLU só é liberado com a leitura de etiqueta de balança ativa. */
    public boolean balancaAtiva(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(this::balancaAtiva);
    }

    /**
     * Busca (6.3): texto → nome contém o termo, sem diferenciar acento nem maiúsculas;
     * só dígitos → código interno ou código de barras exato.
     *
     * @param categoriaId nulo = todas
     */
    public List<ProdutoResumo> pesquisar(Sessao sessao, String texto, Long categoriaId, SituacaoProduto situacao) {
        sessao.exigir(Permissao.PRODUTOS);
        String termo = Textos.normalizarEspacos(texto);
        ProdutoDAO.Filtro filtro = Textos.soDigitos(termo)
                ? new ProdutoDAO.Filtro(null, termo, categoriaId, situacao)
                : new ProdutoDAO.Filtro(Textos.normalizarBusca(termo), null, categoriaId, situacao);
        return transacao.executar(con -> produtoDAO.pesquisar(con, filtro));
    }

    public Produto buscar(Sessao sessao, long id) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> produtoDAO.buscar(con, id)
                .orElseThrow(() -> new NegocioException("Produto não encontrado.")));
    }

    /** Aba Histórico: cadastro e cada mudança de preço ou custo, mais recente primeiro. */
    public List<HistoricoPreco> historico(Sessao sessao, long id) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> produtoDAO.historico(con, id));
    }

    /**
     * Situações que pedem confirmação antes de salvar, mas não impedem (6.3): preço abaixo do custo,
     * preço zero e nome igual ao de outro produto.
     *
     * @param id nulo no cadastro novo
     * @return avisos para mostrar; vazio se nada a confirmar
     */
    public List<String> avisos(Sessao sessao, Long id, DadosProduto dados) {
        sessao.exigir(Permissao.PRODUTOS);
        List<String> avisos = new ArrayList<>();
        if (dados.precoVenda() != null && dados.custo() != null) {
            if (dados.precoVenda().signum() == 0) {
                avisos.add(AVISO_PRECO_ZERO);
            } else if (dados.precoVenda().compareTo(dados.custo()) < 0) {
                avisos.add(AVISO_ABAIXO_DO_CUSTO);
            }
        }
        String nome = Textos.normalizarEspacos(dados.nome());
        if (!nome.isEmpty()) {
            List<ProdutoDAO.Dono> mesmoNome = transacao.executar(
                    con -> produtoDAO.mesmoNome(con, Textos.normalizarBusca(nome), id));
            if (!mesmoNome.isEmpty()) {
                avisos.add("Já existe produto com este nome: " + mesmoNome.stream()
                        .map(p -> descrever(p.id(), p.nome())).collect(Collectors.joining(", ")) + ".");
            }
        }
        return avisos;
    }

    /**
     * Cadastra o produto; estoque inicial maior que zero gera um {@code AJUSTE_ENTRADA} com motivo
     * "Estoque inicial" na mesma transação.
     *
     * @return código (id) do produto
     */
    public long criar(Sessao sessao, DadosProduto dados) {
        sessao.exigir(Permissao.PRODUTOS);
        Validado v = validarCampos(dados);
        return transacao.executar(con -> inserir(con, sessao, v, dados, MOTIVO_ESTOQUE_INICIAL,
                ""));
    }

    /**
     * Cadastro na transação de quem chama (importação por planilha, 6.11), com as mesmas validações e auditoria do
     * cadastro pela tela. Quem chama confere a permissão.
     *
     * @param motivoEstoque motivo do {@code AJUSTE_ENTRADA} do estoque inicial
     * @param origem        complemento da auditoria (ex.: " pela importação de planilha")
     */
    long criar(Connection con, Sessao sessao, DadosProduto dados, String motivoEstoque, String origem)
            throws SQLException {
        return inserir(con, sessao, validarCampos(dados), dados, motivoEstoque, origem);
    }

    private long inserir(Connection con, Sessao sessao, Validado v, DadosProduto dados, String motivoEstoque,
                         String origem) throws SQLException {
        UnidadeMedida unidade = unidade(con, v.unidade);
        BigDecimal minimo = ValidacaoProduto.quantidade(v.estoqueMinimo, "O estoque mínimo", unidade.fracionada());
        BigDecimal estoqueInicial = dados.estoqueInicial();
        boolean controla = Boolean.TRUE.equals(dados.controlaValidade());
        BigDecimal inicial = estoqueInicial == null ? BigDecimal.ZERO
                : ValidacaoProduto.quantidade(estoqueInicial, "O estoque inicial", unidade.fracionada());
        if (inicial.signum() < 0) {
            throw new NegocioException("O estoque inicial não pode ser negativo.");
        }
        String categoriaNome = conferirCategoria(con, v.categoriaId, null);
        conferirPlu(con, v.plu, unidade, false);
        conferirPluLivre(con, v.plu, null);
        conferirCodigosLivres(con, v.codigos, null);

        long id = produtoDAO.inserir(con, gravacao(v, minimo), sessao.usuarioId(), OffsetDateTime.now(clock));
        produtoDAO.definirCodigos(con, id, v.codigos);
        if (controla) {
            // F17: ligado antes do estoque inicial, que vai para o lote escolhido ou para o "SEM LOTE".
            estoqueService.definirControle(con, sessao, id, true);
        }
        if (inicial.signum() > 0) {
            estoqueService.movimentar(con, sessao, id, TipoMovimento.AJUSTE_ENTRADA, inicial,
                    OrigemMovimento.NENHUMA, motivoEstoque, null,
                    controla && dados.loteInicial() != null ? List.of(dados.loteInicial().comQuantidade(inicial))
                            : null);
        }

        StringBuilder descricao = new StringBuilder("Produto ").append(descrever(id, v.nome))
                .append(" criado").append(origem).append(": preço ").append(Formatos.moeda(v.preco))
                .append(", custo ").append(custo(v.custo))
                .append(", unidade ").append(unidade.sigla());
        if (categoriaNome != null) {
            descricao.append(", categoria ").append(categoriaNome);
        }
        if (inicial.signum() > 0) {
            descricao.append(", estoque inicial ").append(Formatos.quantidade(inicial, unidade.fracionada()));
        }
        if (controla) {
            descricao.append(", com controle de validade e lotes");
        }
        auditoria.registrar(con, sessao, AcaoAuditoria.PRODUTO_CRIADO, AuditoriaService.ENTIDADE_PRODUTO, id,
                descricao.toString());
        return id;
    }

    /** Altera o cadastro. O estoque não muda aqui (só por movimento); o estoque inicial é ignorado. */
    public void alterar(Sessao sessao, long id, DadosProduto dados) {
        sessao.exigir(Permissao.PRODUTOS);
        Validado v = validarCampos(dados);
        transacao.executar(con -> {
            if (dados.controlaValidade() != null) {
                estoqueService.travarSaldo(con, id); // saldo antes do produto, na ordem da entrada de mercadoria
            }
            Produto atual = travar(con, id);
            UnidadeMedida unidade = unidade(con, v.unidade);
            BigDecimal minimo = ValidacaoProduto.quantidade(v.estoqueMinimo, "O estoque mínimo", unidade.fracionada());
            if (!unidade.fracionada() && ValidacaoProduto.casas(atual.estoqueAtual()) > 0) {
                throw new NegocioException("O produto tem saldo fracionado ("
                        + Formatos.quantidade(atual.estoqueAtual(), true) + ") e não pode passar para a unidade "
                        + unidade.sigla() + ". Ajuste o estoque antes.");
            }
            String categoriaNome = conferirCategoria(con, v.categoriaId, atual.categoriaId());
            conferirPlu(con, v.plu, unidade, Objects.equals(v.plu, atual.plu()));
            conferirPluLivre(con, v.plu, id);
            conferirCodigosLivres(con, v.codigos, id);

            produtoDAO.alterar(con, id, gravacao(v, minimo), sessao.usuarioId(), OffsetDateTime.now(clock));
            produtoDAO.definirCodigos(con, id, v.codigos);

            List<String> mudancas = mudancas(atual, v, minimo, unidade, categoriaNome);
            if (!mudancas.isEmpty()) {
                auditoria.registrar(con, sessao, AcaoAuditoria.PRODUTO_ALTERADO, AuditoriaService.ENTIDADE_PRODUTO,
                        id, "Produto " + descrever(id, atual.nome()) + ": " + String.join("; ", mudancas));
            }
            if (dados.controlaValidade() != null) {
                controle(con, sessao, id, dados.controlaValidade());
            }
            return null;
        });
    }

    /**
     * F17.2: liga ou desliga o controle de validade e lotes, com auditoria. Ao ligar, a diferença entre o saldo do
     * produto e a soma dos lotes vira saldo inicial (na primeira vez, todo o saldo vai para o "SEM LOTE"); ao
     * desligar, os lotes ficam parados.
     */
    public void definirControleValidade(Sessao sessao, long id, boolean ligar) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            controle(con, sessao, id, ligar);
            return null;
        });
    }

    private void controle(Connection con, Sessao sessao, long id, boolean ligar) throws SQLException {
        java.util.Optional<String> descricao = estoqueService.definirControle(con, sessao, id, ligar);
        if (descricao.isPresent()) {
            auditoria.registrar(con, sessao, AcaoAuditoria.CONTROLE_VALIDADE_ALTERADO,
                    AuditoriaService.ENTIDADE_PRODUTO, id, descricao.get());
        }
    }

    /** Inativo não aparece no PDV nem na busca padrão, mas continua nos relatórios. */
    public void alterarSituacao(Sessao sessao, long id, boolean ativo) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            Produto atual = travar(con, id);
            if (atual.ativo() == ativo) {
                return null;
            }
            produtoDAO.alterarSituacao(con, id, ativo, sessao.usuarioId(), OffsetDateTime.now(clock));
            if (ativo) {
                auditoria.registrar(con, sessao, AcaoAuditoria.PRODUTO_ALTERADO, AuditoriaService.ENTIDADE_PRODUTO,
                        id, "Produto " + descrever(id, atual.nome()) + " reativado");
            } else {
                auditoria.registrar(con, sessao, AcaoAuditoria.PRODUTO_INATIVADO, AuditoriaService.ENTIDADE_PRODUTO,
                        id, "Produto " + descrever(id, atual.nome()) + " inativado");
            }
            return null;
        });
    }

    /**
     * Exclui o produto só se não tiver vendas, movimentos, inventários, entradas nem promoções.
     *
     * @throws RegistroEmUsoException se houver vínculo (a tela oferece inativar)
     */
    public void excluir(Sessao sessao, long id) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            Produto atual = travar(con, id);
            ProdutoDAO.Vinculos vinculos = produtoDAO.vinculos(con, id);
            if (vinculos.algum()) {
                throw new RegistroEmUsoException("O produto \"" + atual.nome() + "\" não pode ser excluído porque já tem "
                        + descrever(vinculos) + ". Você pode inativá-lo: ele sai do PDV e da busca, "
                        + "mas continua nos relatórios.");
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.PRODUTO_EXCLUIDO, AuditoriaService.ENTIDADE_PRODUTO, id,
                    "Produto " + descrever(id, atual.nome()) + " excluído");
            produtoDAO.excluir(con, id);
            return null;
        });
    }

    /** Campos já normalizados e validados (o que não depende do banco). */
    private record Validado(String nome, Long categoriaId, String unidade, BigDecimal preco, BigDecimal custo,
                            BigDecimal estoqueMinimo, Integer plu, List<CodigoBarras> codigos) {
    }

    private static Validado validarCampos(DadosProduto dados) {
        String nome = ValidacaoProduto.nome(dados.nome());
        if (Textos.vazio(dados.unidade())) {
            throw new NegocioException("Informe a unidade.");
        }
        BigDecimal preco = ValidacaoProduto.preco(dados.precoVenda());
        BigDecimal custo = ValidacaoProduto.custo(dados.custo());
        BigDecimal minimo = dados.estoqueMinimo() == null ? BigDecimal.ZERO : dados.estoqueMinimo();
        List<CodigoBarras> codigos = ValidacaoProduto.codigos(dados.codigosBarras());
        return new Validado(nome, dados.categoriaId(), dados.unidade().strip(), preco, custo, minimo, dados.plu(),
                codigos);
    }

    private static ProdutoDAO.Gravacao gravacao(Validado v, BigDecimal minimo) {
        return new ProdutoDAO.Gravacao(v.nome, v.categoriaId, v.unidade, v.preco, v.custo, minimo, v.plu);
    }

    private Produto travar(Connection con, long id) throws SQLException {
        return produtoDAO.buscarParaAtualizar(con, id)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
    }

    private UnidadeMedida unidade(Connection con, String sigla) throws SQLException {
        return unidadeDAO.buscar(con, sigla)
                .orElseThrow(() -> new NegocioException("Unidade \"" + sigla + "\" não cadastrada."));
    }

    /**
     * Categoria existe e está ativa (uma categoria inativa só continua no produto que já a tinha).
     *
     * @return o nome da categoria, ou nulo se sem categoria
     */
    private String conferirCategoria(Connection con, Long categoriaId, Long categoriaAtual) throws SQLException {
        if (categoriaId == null) {
            return null;
        }
        Categoria categoria = categoriaDAO.buscar(con, categoriaId)
                .orElseThrow(() -> new NegocioException("Categoria não encontrada."));
        if (!categoria.ativa() && !categoriaId.equals(categoriaAtual)) {
            throw new NegocioException("A categoria \"" + categoria.nome() + "\" está inativa.");
        }
        return categoria.nome();
    }

    private void conferirPluLivre(Connection con, Integer plu, Long ignorarId) throws SQLException {
        if (plu == null) {
            return;
        }
        produtoDAO.donoDoPlu(con, plu, ignorarId).ifPresent(dono -> {
            throw new NegocioException("O PLU " + plu + " já pertence ao produto " + descrever(dono.id(), dono.nome())
                    + ".");
        });
    }

    /** Código repetido: a mensagem diz em qual produto ele está (6.3). */
    private void conferirCodigosLivres(Connection con, List<CodigoBarras> codigos, Long ignorarId)
            throws SQLException {
        for (CodigoBarras codigo : codigos) {
            var dono = produtoDAO.donoDoCodigo(con, codigo.codigo(), ignorarId);
            if (dono.isPresent()) {
                throw new NegocioException("Não foi possível salvar: o código de barras " + codigo.codigo()
                        + " já pertence ao produto " + descrever(dono.get().id(), dono.get().nome()) + ".");
            }
        }
    }

    private boolean balancaAtiva(Connection con) throws SQLException {
        return configuracaoDAO.carregar(con).balanca().ativo();
    }

    /** PLU (6.3) com o limite dos dígitos de código da etiqueta (6.9). */
    private void conferirPlu(Connection con, Integer plu, UnidadeMedida unidade, boolean jaGravado)
            throws SQLException {
        Configuracoes.Balanca balanca = configuracaoDAO.carregar(con).balanca();
        ValidacaoProduto.plu(plu, unidade.fracionada(), balanca.ativo(), jaGravado,
                balanca.ativo() && !jaGravado ? balanca.pluMaximo() : ValidacaoProduto.PLU_MAXIMO);
    }

    /** Descrição legível das mudanças para a auditoria ("Preço alterado de R$ 5,49 para R$ 5,99"). */
    private static List<String> mudancas(Produto atual, Validado v, BigDecimal minimo, UnidadeMedida unidade,
                                         String categoriaNome) {
        List<String> mudancas = new ArrayList<>();
        if (!atual.nome().equals(v.nome)) {
            mudancas.add("nome alterado de \"" + atual.nome() + "\" para \"" + v.nome + "\"");
        }
        if (!Objects.equals(atual.categoriaId(), v.categoriaId)) {
            mudancas.add("categoria alterada de " + semCategoria(atual.categoriaNome()) + " para "
                    + semCategoria(categoriaNome));
        }
        if (!atual.unidade().equals(unidade.sigla())) {
            mudancas.add("unidade alterada de " + atual.unidade() + " para " + unidade.sigla());
        }
        if (atual.precoVenda().compareTo(v.preco) != 0) {
            mudancas.add("Preço alterado de " + Formatos.moeda(atual.precoVenda()) + " para " + Formatos.moeda(v.preco));
        }
        if (atual.custo().compareTo(v.custo) != 0) {
            mudancas.add("Custo alterado de " + custo(atual.custo()) + " para " + custo(v.custo));
        }
        if (atual.estoqueMinimo().compareTo(minimo) != 0) {
            mudancas.add("estoque mínimo alterado de " + Formatos.quantidade(atual.estoqueMinimo(), atual.unidadeFracionada())
                    + " para " + Formatos.quantidade(minimo, unidade.fracionada()));
        }
        if (!Objects.equals(atual.plu(), v.plu)) {
            mudancas.add("PLU alterado de " + semValor(atual.plu()) + " para " + semValor(v.plu));
        }
        Set<String> antes = codigos(atual.codigosBarras());
        Set<String> depois = codigos(v.codigos);
        List<String> incluidos = depois.stream().filter(c -> !antes.contains(c)).toList();
        List<String> removidos = antes.stream().filter(c -> !depois.contains(c)).toList();
        if (!incluidos.isEmpty()) {
            mudancas.add("códigos de barras incluídos: " + String.join(", ", incluidos));
        }
        if (!removidos.isEmpty()) {
            mudancas.add("códigos de barras removidos: " + String.join(", ", removidos));
        }
        String principalAntes = atual.codigoPrincipal().orElse(null);
        String principalDepois = v.codigos.isEmpty() ? null : v.codigos.get(0).codigo();
        if (principalDepois != null && !principalDepois.equals(principalAntes)) {
            mudancas.add("código de barras principal: " + principalDepois);
        }
        return mudancas;
    }

    private static Set<String> codigos(List<CodigoBarras> codigos) {
        return codigos.stream().map(CodigoBarras::codigo).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String descrever(ProdutoDAO.Vinculos v) {
        List<String> partes = new ArrayList<>();
        if (v.vendas()) {
            partes.add("vendas");
        }
        if (v.movimentos()) {
            partes.add("movimentação de estoque");
        }
        if (v.inventarios()) {
            partes.add("inventário");
        }
        if (v.entradas()) {
            partes.add("entrada de mercadoria");
        }
        if (v.pedidos()) {
            partes.add("pedido de compra");
        }
        if (v.promocoes()) {
            partes.add("promoção");
        }
        if (partes.size() == 1) {
            return partes.get(0);
        }
        return String.join(", ", partes.subList(0, partes.size() - 1)) + " e " + partes.get(partes.size() - 1);
    }

    /** "Café 500 g" (código 12). */
    static String descrever(long id, String nome) {
        return "\"" + nome + "\" (código " + id + ")";
    }

    /** Custo com 4 casas: "R$ 3,2000". */
    private static String custo(BigDecimal custo) {
        return "R$ " + Formatos.numero(custo, Dinheiro.ESCALA_CUSTO);
    }

    private static String semCategoria(String nome) {
        return nome == null ? "(sem categoria)" : nome;
    }

    private static String semValor(Object valor) {
        return valor == null ? "(nenhum)" : valor.toString();
    }
}
