package br.com.varesync.service;

import br.com.varesync.dao.UnidadeMedidaDAO;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.UnidadeCadastro;
import br.com.varesync.model.UnidadeMedida;
import br.com.varesync.util.Textos;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cadastro de unidades de medida (F10.5), na rotina Produtos: sigla única de 1 a 6 letras maiúsculas ou dígitos,
 * descrição e fracionada. A sigla e "fracionada" só mudam, e a unidade só é excluída, se nenhum produto a usa; a
 * descrição muda sempre. UN, a unidade padrão do cadastro, não muda de sigla nem é excluída.
 */
public class UnidadeService {

    static final int TAMANHO_MAXIMO_DESCRICAO = 40;
    static final String SIGLA_INVALIDA = "A sigla deve ter de 1 a 6 letras (sem acento) ou números, como KG ou PCT.";
    private static final Pattern SIGLA = Pattern.compile("[A-Z0-9]{1,6}");

    private final Transacao transacao;
    private final UnidadeMedidaDAO dao;

    public UnidadeService(Transacao transacao, UnidadeMedidaDAO dao) {
        this.transacao = transacao;
        this.dao = dao;
    }

    public List<UnidadeCadastro> listar(Sessao sessao) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(dao::listarComUso);
    }

    /** @return a unidade gravada (sigla em maiúsculas, descrição sem espaços sobrando) */
    public UnidadeMedida criar(Sessao sessao, String siglaDigitada, String descricaoDigitada, boolean fracionada) {
        sessao.exigir(Permissao.PRODUTOS);
        UnidadeMedida nova = new UnidadeMedida(sigla(siglaDigitada), descricao(descricaoDigitada), fracionada);
        return transacao.executar(con -> {
            conferirSiglaLivre(con, nova.sigla());
            dao.inserir(con, nova);
            return nova;
        });
    }

    /**
     * Altera a unidade {@code siglaAtual}.
     *
     * @return a unidade gravada
     * @throws NegocioException se a sigla ou "fracionada" mudarem com a unidade em uso
     */
    public UnidadeMedida alterar(Sessao sessao, String siglaAtual, String siglaDigitada, String descricaoDigitada,
                                 boolean fracionada) {
        sessao.exigir(Permissao.PRODUTOS);
        UnidadeMedida nova = new UnidadeMedida(sigla(siglaDigitada), descricao(descricaoDigitada), fracionada);
        return transacao.executar(con -> {
            UnidadeMedida atual = travar(con, siglaAtual);
            boolean mudouSigla = !atual.sigla().equals(nova.sigla());
            if (mudouSigla && UnidadeMedida.PADRAO.equals(atual.sigla())) {
                throw new NegocioException("A sigla UN não pode mudar: é a unidade padrão do cadastro de produtos.");
            }
            if (mudouSigla || atual.fracionada() != nova.fracionada()) {
                long produtos = dao.contarProdutos(con, atual.sigla());
                if (produtos > 0) {
                    throw new NegocioException("A unidade " + atual.sigla() + " é usada por " + produtos(produtos)
                            + ": a sigla e \"fracionada\" não podem mudar. A descrição pode.");
                }
                if (mudouSigla) {
                    conferirSiglaLivre(con, nova.sigla());
                }
            }
            dao.alterar(con, atual.sigla(), nova);
            return nova;
        });
    }

    /** @throws NegocioException se algum produto usa a unidade ou se for a UN */
    public void excluir(Sessao sessao, String sigla) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            UnidadeMedida unidade = travar(con, sigla);
            if (UnidadeMedida.PADRAO.equals(unidade.sigla())) {
                throw new NegocioException("A unidade UN não pode ser excluída: é a unidade padrão do cadastro de "
                        + "produtos.");
            }
            long produtos = dao.contarProdutos(con, unidade.sigla());
            if (produtos > 0) {
                throw new NegocioException("A unidade " + unidade.sigla() + " é usada por " + produtos(produtos)
                        + " e não pode ser excluída.");
            }
            dao.excluir(con, unidade.sigla());
            return null;
        });
    }

    private UnidadeMedida travar(Connection con, String sigla) throws SQLException {
        return dao.travar(con, sigla).orElseThrow(() -> new NegocioException("Unidade não encontrada."));
    }

    private void conferirSiglaLivre(Connection con, String sigla) throws SQLException {
        var existente = dao.buscar(con, sigla);
        if (existente.isPresent()) {
            throw new NegocioException("Já existe a unidade " + sigla + " (" + existente.get().descricao() + ").");
        }
    }

    private static String produtos(long quantidade) {
        return quantidade == 1 ? "1 produto" : quantidade + " produtos";
    }

    /** Sem espaços e em maiúsculas: "kg" → "KG". */
    static String sigla(String digitada) {
        String sigla = Textos.normalizarEspacos(digitada).toUpperCase(Locale.ROOT);
        if (sigla.isEmpty()) {
            throw new NegocioException("Informe a sigla da unidade.");
        }
        if (!SIGLA.matcher(sigla).matches()) {
            throw new NegocioException(SIGLA_INVALIDA);
        }
        return sigla;
    }

    static String descricao(String digitada) {
        String descricao = Textos.normalizarEspacos(digitada);
        if (descricao.isEmpty()) {
            throw new NegocioException("Informe a descrição da unidade.");
        }
        if (descricao.length() > TAMANHO_MAXIMO_DESCRICAO) {
            throw new NegocioException("A descrição da unidade pode ter no máximo " + TAMANHO_MAXIMO_DESCRICAO
                    + " caracteres.");
        }
        return descricao;
    }
}
