package br.com.varesync.service;

import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.ProdutoFornecedorDAO;
import br.com.varesync.model.DadosVinculo;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoFornecedor;
import br.com.varesync.model.ProdutoVenda;
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
 * Fornecedores do produto (F16.1), na aba Fornecedores do cadastro, com a permissão PRODUTOS: código do produto no
 * fornecedor, unidade de compra, fator, prazo de entrega e o principal (no máximo um; o primeiro vínculo do produto já
 * nasce principal). O último custo vem da entrada confirmada ({@code EntradaService}). A importação da NF-e grava o
 * vínculo feito à mão por {@link #vincularPelaNota}.
 */
public class ProdutoFornecedorService {

    static final int TAMANHO_CODIGO = 60;
    static final int TAMANHO_UNIDADE = 6;
    public static final int PRAZO_MAXIMO = 365;

    private final Transacao transacao;
    private final ProdutoFornecedorDAO dao;
    private final ProdutoDAO produtoDAO;
    private final FornecedorDAO fornecedorDAO;
    private final Clock clock;

    public ProdutoFornecedorService(Transacao transacao, ProdutoFornecedorDAO dao, ProdutoDAO produtoDAO,
                                    FornecedorDAO fornecedorDAO, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.produtoDAO = produtoDAO;
        this.fornecedorDAO = fornecedorDAO;
        this.clock = clock;
    }

    /** Fornecedores do produto: o principal primeiro. */
    public List<ProdutoFornecedor> listar(Sessao sessao, long produtoId) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> dao.listar(con, produtoId));
    }

    /** Fornecedores ativos, para escolher no vínculo novo. */
    public List<Fornecedor> fornecedores(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> fornecedorDAO.listar(con, false));
    }

    /**
     * Inclui (com {@code vinculoId} nulo) ou altera um vínculo. Marcado como principal, tira a marca dos outros.
     *
     * @return id do vínculo
     * @throws NegocioException fornecedor inativo ou já vinculado, código já usado em outro produto do fornecedor,
     *                          fator ou prazo inválidos
     */
    public long salvar(Sessao sessao, long produtoId, Long vinculoId, DadosVinculo dados) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> {
            ProdutoVenda produto = produtoDAO.buscarParaVenda(con, produtoId)
                    .orElseThrow(() -> new NegocioException("Produto não encontrado."));
            dao.travarDoProduto(con, produtoId);
            OffsetDateTime agora = OffsetDateTime.now(clock);
            ProdutoFornecedor atual = null;
            if (vinculoId != null) {
                atual = dao.buscar(con, vinculoId).filter(v -> v.produtoId() == produtoId)
                        .orElseThrow(() -> new NegocioException("Fornecedor do produto não encontrado."));
            }
            long fornecedorId = atual != null ? atual.fornecedorId() : dados.fornecedorId();
            Fornecedor fornecedor = fornecedorDAO.buscar(con, fornecedorId)
                    .orElseThrow(() -> new NegocioException("Fornecedor não encontrado."));
            if (atual == null) {
                if (!fornecedor.ativo()) {
                    throw new NegocioException("O fornecedor \"" + fornecedor.razaoSocial() + "\" está inativo.");
                }
                if (dao.doProduto(con, produtoId, fornecedorId).isPresent()) {
                    throw new NegocioException("\"" + fornecedor.razaoSocial() + "\" já é fornecedor deste produto. "
                            + "Altere a linha dele.");
                }
            }
            String codigo = texto(dados.codigoNoFornecedor(), TAMANHO_CODIGO, "O código no fornecedor");
            if (codigo != null) {
                Optional<ProdutoFornecedor> dono = dao.porCodigo(con, fornecedorId, codigo);
                if (dono.isPresent() && dono.get().produtoId() != produtoId) {
                    String nome = produtoDAO.buscarParaVenda(con, dono.get().produtoId()).map(ProdutoVenda::nome)
                            .orElse("outro produto");
                    throw new NegocioException("O código " + codigo + " de " + fornecedor.razaoSocial()
                            + " já está no produto " + ProdutoService.descrever(dono.get().produtoId(), nome) + ".");
                }
            }
            String unidade = texto(dados.unidadeCompra(), TAMANHO_UNIDADE, "A unidade de compra");
            BigDecimal fator = fator(dados.fator(), produto.fracionada());
            int prazo = prazo(dados.prazoEntregaDias());
            boolean principal = dados.principal() || (atual == null && !dao.temPrincipal(con, produtoId));
            ProdutoFornecedorDAO.Gravacao g = new ProdutoFornecedorDAO.Gravacao(fornecedorId, codigo,
                    unidade == null ? null : unidade.toUpperCase(), fator, prazo, principal);
            long id;
            if (atual == null) {
                // O principal novo só entra depois de o antigo sair (ux_pf_principal).
                if (principal) {
                    dao.desmarcarPrincipal(con, produtoId, -1, sessao.usuarioId(), agora);
                }
                id = dao.inserir(con, produtoId, g, sessao.usuarioId(), agora);
            } else {
                id = atual.id();
                if (principal) {
                    dao.desmarcarPrincipal(con, produtoId, id, sessao.usuarioId(), agora);
                }
                dao.alterar(con, id, g, sessao.usuarioId(), agora);
            }
            return id;
        });
    }

    /** Remove o vínculo; se era o principal, o produto fica sem principal até outro ser marcado. */
    public void remover(Sessao sessao, long produtoId, long vinculoId) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            dao.buscar(con, vinculoId).filter(v -> v.produtoId() == produtoId)
                    .orElseThrow(() -> new NegocioException("Fornecedor do produto não encontrado."));
            dao.remover(con, vinculoId);
            return null;
        });
    }

    /**
     * Vínculo feito à mão na importação da NF-e (F16.1): o produto escolhido para o item fica com o código
     * ({@code cProd}), a unidade comercial e o fator, para as próximas notas do fornecedor. Se o código estava em outro
     * produto do fornecedor, sai de lá. O primeiro vínculo do produto vira o principal. Na transação de quem chama.
     */
    void vincularPelaNota(Connection con, Sessao sessao, long produtoId, long fornecedorId, String codigo,
                          String unidade, BigDecimal fator) throws SQLException {
        String cProd = Textos.normalizarEspacos(codigo);
        if (cProd.isEmpty() || cProd.length() > TAMANHO_CODIGO) {
            return; // sem código que caiba, não há como reconhecer o item na próxima nota
        }
        String uCom = Textos.normalizarEspacos(unidade).toUpperCase();
        uCom = uCom.isEmpty() || uCom.length() > TAMANHO_UNIDADE ? null : uCom;
        OffsetDateTime agora = OffsetDateTime.now(clock);
        dao.travarDoProduto(con, produtoId);
        dao.liberarCodigo(con, fornecedorId, cProd, produtoId, sessao.usuarioId(), agora);
        Optional<ProdutoFornecedor> atual = dao.doProduto(con, produtoId, fornecedorId);
        if (atual.isPresent()) {
            ProdutoFornecedor v = atual.get();
            dao.alterar(con, v.id(), new ProdutoFornecedorDAO.Gravacao(fornecedorId, cProd, uCom, fator,
                    v.prazoEntregaDias(), v.principal()), sessao.usuarioId(), agora);
        } else {
            dao.inserir(con, produtoId, new ProdutoFornecedorDAO.Gravacao(fornecedorId, cProd, uCom, fator, 0,
                    !dao.temPrincipal(con, produtoId)), sessao.usuarioId(), agora);
        }
    }

    /** Fator ≥ 1 com até 3 casas; inteiro quando a unidade de venda não é fracionada (senão a quantidade quebraria). */
    static BigDecimal fator(BigDecimal fator, boolean fracionada) {
        if (fator == null) {
            throw new NegocioException("Informe o fator (quantas unidades de venda vêm em 1 unidade de compra).");
        }
        if (fator.compareTo(BigDecimal.ONE) < 0) {
            throw new NegocioException("O fator deve ser 1 ou mais (quantas unidades de venda vêm em 1 unidade de "
                    + "compra).");
        }
        BigDecimal f = ValidacaoProduto.quantidade(fator, "O fator", fracionada);
        return f.setScale(Dinheiro.ESCALA_QUANTIDADE, Dinheiro.ARREDONDAMENTO);
    }

    /** Fator digitado na conferência da NF-e: ≥ 1, até 3 casas (a quantidade é conferida com o produto depois). */
    public static BigDecimal fatorDigitado(BigDecimal fator) {
        return fator(fator, true);
    }

    static int prazo(int dias) {
        if (dias < 0 || dias > PRAZO_MAXIMO) {
            throw new NegocioException("O prazo de entrega deve estar entre 0 e " + PRAZO_MAXIMO + " dias.");
        }
        return dias;
    }

    /** "12 un. por CX" para a lista. */
    public static String descreverFator(ProdutoFornecedor v, String unidadeVenda) {
        return Formatos.numero(v.fator(), v.fator().stripTrailingZeros().scale() > 0 ? Dinheiro.ESCALA_QUANTIDADE : 0)
                + " " + unidadeVenda + (v.unidadeCompra() == null ? " por unidade de compra" : " por "
                + v.unidadeCompra());
    }

    private static String texto(String valor, int tamanho, String rotulo) {
        String t = Textos.normalizarEspacos(valor);
        if (t.length() > tamanho) {
            throw new NegocioException(rotulo + " pode ter no máximo " + tamanho + " caracteres.");
        }
        return t.isEmpty() ? null : t;
    }
}
