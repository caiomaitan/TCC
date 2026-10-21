package br.com.varesync.service;

import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.PromocaoDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.FiltroPromocao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Produto;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.Promocao;
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
import java.util.Optional;

/**
 * Promoções (6.8), com a permissão PRODUTOS: preço promocional (&gt; 0) de um produto num período (início e fim
 * inclusive). Duas promoções ativas do mesmo produto não podem ter períodos sobrepostos. No PDV vale a promoção cujo
 * período contém a data da venda ({@link PrecoService}). Toda alteração é auditada ({@code PROMOCAO_ALTERADA}).
 */
public class PromocaoService {

    public static final String ENTIDADE_PROMOCAO = "promocao";

    private final Transacao transacao;
    private final PromocaoDAO promocaoDAO;
    private final ProdutoDAO produtoDAO;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public PromocaoService(Transacao transacao, PromocaoDAO promocaoDAO, ProdutoDAO produtoDAO,
                           AuditoriaService auditoria, Clock clock) {
        this.transacao = transacao;
        this.promocaoDAO = promocaoDAO;
        this.produtoDAO = produtoDAO;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    public LocalDate hoje() {
        return LocalDate.now(clock);
    }

    /** Lista pelo filtro (em relação a hoje) e, se informado, pelo nome do produto (sem acento nem maiúsculas). */
    public List<Promocao> listar(Sessao sessao, FiltroPromocao filtro, String termo) {
        sessao.exigir(Permissao.PRODUTOS);
        String busca = Textos.vazio(termo) ? null : Textos.normalizarBusca(Textos.normalizarEspacos(termo));
        return transacao.executar(con -> promocaoDAO.listar(con, filtro == null ? FiltroPromocao.TODAS : filtro,
                hoje(), busca, null));
    }

    /** Promoções de um produto (todas), para conferir o histórico ao cadastrar. */
    public List<Promocao> doProduto(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> promocaoDAO.listar(con, FiltroPromocao.TODAS, hoje(), null, produtoId));
    }

    /** Produto pelo código de barras exato ou pelo código interno (o formulário mostra preço e custo). */
    public ProdutoVenda produto(Sessao sessao, String codigoDigitado) {
        sessao.exigir(Permissao.PRODUTOS);
        String codigo = Textos.normalizarEspacos(codigoDigitado);
        if (!Textos.soDigitos(codigo)) {
            throw new NegocioException("Informe o código ou o código de barras do produto, ou use Buscar.");
        }
        return transacao.executar(con -> {
            Optional<ProdutoVenda> porCodigo = produtoDAO.buscarParaVendaPorCodigo(con, codigo);
            return porCodigo.isPresent() ? porCodigo : produtoDAO.buscarParaVenda(con, ProdutoDAO.idOuNenhum(codigo));
        }).orElseThrow(() -> new NegocioException("Produto não encontrado (código " + codigo + ")."));
    }

    public ProdutoVenda produto(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> produtoDAO.buscarParaVenda(con, produtoId))
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
    }

    /**
     * Situações que pedem confirmação, sem impedir: preço promocional abaixo do custo (6.8) e preço que não é menor
     * que o preço de venda.
     */
    public List<String> avisos(Sessao sessao, DadosPromocao dados) {
        sessao.exigir(Permissao.PRODUTOS);
        List<String> avisos = new ArrayList<>();
        if (dados.produtoId() == null || dados.precoPromocional() == null) {
            return avisos;
        }
        ProdutoVenda produto = produto(sessao, dados.produtoId());
        if (dados.precoPromocional().compareTo(produto.custo()) < 0) {
            avisos.add("O preço promocional (" + Formatos.moeda(dados.precoPromocional()) + ") está abaixo do custo ("
                    + "R$ " + Formatos.numero(produto.custo(), Dinheiro.ESCALA_CUSTO)
                    + "): cada venda na promoção dará prejuízo.");
        }
        if (dados.precoPromocional().compareTo(produto.precoVenda()) >= 0) {
            avisos.add("O preço promocional não é menor que o preço de venda ("
                    + Formatos.moeda(produto.precoVenda()) + ").");
        }
        return avisos;
    }

    /** @return número da promoção */
    public long criar(Sessao sessao, DadosPromocao dados) {
        sessao.exigir(Permissao.PRODUTOS);
        DadosPromocao v = validar(dados);
        if (v.fim().isBefore(hoje())) {
            throw new NegocioException("A data final já passou: a promoção não valeria em nenhuma venda.");
        }
        return transacao.executar(con -> {
            Produto produto = travarProduto(con, v.produtoId());
            if (v.ativa()) {
                if (!produto.ativo()) {
                    throw new NegocioException("O produto \"" + produto.nome() + "\" está inativo. Reative-o antes de "
                            + "criar a promoção.");
                }
                conferirSobreposicao(con, produto.nome(), v, null);
            }
            long id = promocaoDAO.inserir(con, v, sessao.usuarioId(), OffsetDateTime.now(clock));
            auditoria.registrar(con, sessao, AcaoAuditoria.PROMOCAO_ALTERADA, ENTIDADE_PROMOCAO, id,
                    "Promoção nº " + id + " criada: " + descrever(produto.id(), produto.nome(), v)
                            + " (preço de venda " + Formatos.moeda(produto.precoVenda()) + ")");
            return id;
        });
    }

    /** Altera preço, período e situação (o produto não muda; para outro produto, crie outra promoção). */
    public void alterar(Sessao sessao, long id, DadosPromocao dados) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            Promocao atual = promocaoDAO.buscar(con, id, false)
                    .orElseThrow(() -> new NegocioException("Promoção nº " + id + " não encontrada."));
            DadosPromocao v = validar(new DadosPromocao(atual.produtoId(), dados.precoPromocional(), dados.inicio(),
                    dados.fim(), dados.ativa()));
            Produto produto = travarProduto(con, atual.produtoId());
            promocaoDAO.buscar(con, id, true);
            if (v.ativa()) {
                if (!produto.ativo() && !atual.ativa()) {
                    throw new NegocioException("O produto \"" + produto.nome() + "\" está inativo. Reative-o antes de "
                            + "ativar a promoção.");
                }
                conferirSobreposicao(con, produto.nome(), v, id);
            }
            List<String> mudancas = new ArrayList<>();
            if (atual.precoPromocional().compareTo(v.precoPromocional()) != 0) {
                mudancas.add("preço de " + Formatos.moeda(atual.precoPromocional()) + " para "
                        + Formatos.moeda(v.precoPromocional()));
            }
            if (!atual.inicio().equals(v.inicio()) || !atual.fim().equals(v.fim())) {
                mudancas.add("período de " + periodo(atual.inicio(), atual.fim()) + " para "
                        + periodo(v.inicio(), v.fim()));
            }
            if (atual.ativa() != v.ativa()) {
                mudancas.add(v.ativa() ? "reativada" : "desativada");
            }
            if (mudancas.isEmpty()) {
                return null;
            }
            promocaoDAO.alterar(con, id, v);
            auditoria.registrar(con, sessao, AcaoAuditoria.PROMOCAO_ALTERADA, ENTIDADE_PROMOCAO, id,
                    "Promoção nº " + id + " de " + ProdutoService.descrever(produto.id(), produto.nome()) + ": "
                            + String.join("; ", mudancas));
            return null;
        });
    }

    /** Ativa ou desativa (com a mesma regra de sobreposição ao ativar). */
    public void alterarSituacao(Sessao sessao, long id, boolean ativa) {
        sessao.exigir(Permissao.PRODUTOS);
        Promocao atual = transacao.executar(con -> promocaoDAO.buscar(con, id, false))
                .orElseThrow(() -> new NegocioException("Promoção nº " + id + " não encontrada."));
        alterar(sessao, id, new DadosPromocao(atual.produtoId(), atual.precoPromocional(), atual.inicio(), atual.fim(),
                ativa));
    }

    /** Exclui a promoção que nunca foi usada numa venda; usada, só pode ser desativada. */
    public void excluir(Sessao sessao, long id) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            Promocao atual = promocaoDAO.buscar(con, id, true)
                    .orElseThrow(() -> new NegocioException("Promoção nº " + id + " não encontrada."));
            if (promocaoDAO.usadaEmVenda(con, id)) {
                throw new RegistroEmUsoException("A promoção nº " + id + " já foi aplicada em vendas e não pode ser "
                        + "excluída. Você pode desativá-la.");
            }
            auditoria.registrar(con, sessao, AcaoAuditoria.PROMOCAO_ALTERADA, ENTIDADE_PROMOCAO, id,
                    "Promoção nº " + id + " excluída: " + descrever(atual.produtoId(), atual.produtoNome(),
                            new DadosPromocao(atual.produtoId(), atual.precoPromocional(), atual.inicio(),
                                    atual.fim(), atual.ativa())));
            promocaoDAO.excluir(con, id);
            return null;
        });
    }

    // ----- Apoio -----

    /** Campos obrigatórios, preço &gt; 0 com até 2 casas e fim ≥ início. */
    static DadosPromocao validar(DadosPromocao dados) {
        if (dados.produtoId() == null) {
            throw new NegocioException("Escolha o produto da promoção.");
        }
        if (dados.precoPromocional() == null) {
            throw new NegocioException("Informe o preço promocional.");
        }
        BigDecimal preco = ValidacaoProduto.valor(dados.precoPromocional(), "o preço promocional",
                Dinheiro.ESCALA_DINHEIRO);
        if (preco.signum() <= 0) {
            throw new NegocioException("O preço promocional deve ser maior que zero.");
        }
        if (dados.inicio() == null || dados.fim() == null) {
            throw new NegocioException("Informe a data de início e a de fim da promoção.");
        }
        if (dados.fim().isBefore(dados.inicio())) {
            throw new NegocioException("A data de fim é antes da data de início.");
        }
        return new DadosPromocao(dados.produtoId(), preco, dados.inicio(), dados.fim(), dados.ativa());
    }

    private Produto travarProduto(Connection con, long produtoId) throws SQLException {
        // A trava do produto serializa as promoções dele: duas telas não gravam períodos sobrepostos ao mesmo tempo.
        return produtoDAO.buscarParaAtualizar(con, produtoId)
                .orElseThrow(() -> new NegocioException("Produto não encontrado."));
    }

    private void conferirSobreposicao(Connection con, String produtoNome, DadosPromocao v, Long ignorarId)
            throws SQLException {
        List<Promocao> outras = promocaoDAO.sobrepostas(con, v.produtoId(), v.inicio(), v.fim(), ignorarId);
        if (!outras.isEmpty()) {
            Promocao outra = outras.get(0);
            throw new NegocioException("Já existe uma promoção ativa de \"" + produtoNome + "\" de "
                    + periodo(outra.inicio(), outra.fim()) + " (nº " + outra.id() + ", "
                    + Formatos.moeda(outra.precoPromocional()) + ") que se sobrepõe a este período. Ajuste as datas ou "
                    + "desative a outra promoção.");
        }
    }

    private static String descrever(long produtoId, String nome, DadosPromocao v) {
        return ProdutoService.descrever(produtoId, nome) + " a " + Formatos.moeda(v.precoPromocional()) + " de "
                + periodo(v.inicio(), v.fim()) + (v.ativa() ? "" : ", desativada");
    }

    static String periodo(LocalDate inicio, LocalDate fim) {
        return Formatos.data(inicio) + " a " + Formatos.data(fim);
    }
}
