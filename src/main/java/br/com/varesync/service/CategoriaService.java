package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.model.Categoria;
import br.com.varesync.model.Permissao;
import br.com.varesync.util.Textos;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Cadastro simples de categorias (6.3): nome único sem diferenciar maiúsculas; em uso → inativar em vez de excluir.
 * Faz parte da rotina Produtos.
 */
public class CategoriaService {

    static final int TAMANHO_MAXIMO_NOME = 60;

    private final Transacao transacao;
    private final CategoriaDAO dao;

    public CategoriaService(Transacao transacao, CategoriaDAO dao) {
        this.transacao = transacao;
        this.dao = dao;
    }

    public List<Categoria> listar(Sessao sessao, boolean incluirInativas) {
        sessao.exigir(Permissao.PRODUTOS);
        return transacao.executar(con -> dao.listar(con, incluirInativas));
    }

    /** @return id da categoria criada */
    public long criar(Sessao sessao, String nomeDigitado) {
        sessao.exigir(Permissao.PRODUTOS);
        String nome = nome(nomeDigitado);
        return transacao.executar(con -> {
            conferirNomeLivre(con, nome, null);
            return dao.inserir(con, nome);
        });
    }

    public void renomear(Sessao sessao, long id, String nomeDigitado) {
        sessao.exigir(Permissao.PRODUTOS);
        String nome = nome(nomeDigitado);
        transacao.executar(con -> {
            buscar(con, id);
            conferirNomeLivre(con, nome, id);
            dao.renomear(con, id, nome);
            return null;
        });
    }

    public void alterarSituacao(Sessao sessao, long id, boolean ativa) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            buscar(con, id);
            dao.alterarSituacao(con, id, ativa);
            return null;
        });
    }

    /** @throws RegistroEmUsoException se algum produto usa a categoria (a tela oferece inativar) */
    public void excluir(Sessao sessao, long id) {
        sessao.exigir(Permissao.PRODUTOS);
        transacao.executar(con -> {
            Categoria categoria = buscar(con, id);
            long produtos = dao.contarProdutos(con, id);
            if (produtos > 0) {
                throw new RegistroEmUsoException("A categoria \"" + categoria.nome() + "\" é usada por " + produtos
                        + (produtos == 1 ? " produto" : " produtos") + " e não pode ser excluída. "
                        + "Você pode inativá-la: ela deixa de aparecer no cadastro de produtos novos.");
            }
            dao.excluir(con, id);
            return null;
        });
    }

    private Categoria buscar(Connection con, long id) throws SQLException {
        return dao.buscar(con, id).orElseThrow(() -> new NegocioException("Categoria não encontrada."));
    }

    private void conferirNomeLivre(Connection con, String nome, Long ignorarId) throws SQLException {
        if (dao.buscarPorNome(con, nome, ignorarId).isPresent()) {
            throw new NegocioException("Já existe uma categoria com o nome \"" + nome + "\".");
        }
    }

    static String nome(String digitado) {
        String nome = Textos.normalizarEspacos(digitado);
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome da categoria.");
        }
        if (nome.length() > TAMANHO_MAXIMO_NOME) {
            throw new NegocioException("O nome da categoria pode ter no máximo " + TAMANHO_MAXIMO_NOME
                    + " caracteres.");
        }
        return nome;
    }
}
