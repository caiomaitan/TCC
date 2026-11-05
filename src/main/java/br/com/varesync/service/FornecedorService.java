package br.com.varesync.service;

import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.model.DadosFornecedor;
import br.com.varesync.model.Fornecedor;
import br.com.varesync.model.Permissao;
import br.com.varesync.util.CpfCnpj;
import br.com.varesync.util.Textos;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cadastro de fornecedores (6.10), todo com a permissão FORNECEDORES: busca, criar, alterar, inativar/reativar e
 * excluir (só sem entradas de mercadoria; senão a tela oferece inativar). CPF/CNPJ único, gravado sem máscara.
 */
public class FornecedorService {

    private final Transacao transacao;
    private final FornecedorDAO dao;
    private final Clock clock;

    public FornecedorService(Transacao transacao, FornecedorDAO dao, Clock clock) {
        this.transacao = transacao;
        this.dao = dao;
        this.clock = clock;
    }

    /**
     * Busca por razão social, nome fantasia ou cidade (sem diferenciar acento nem maiúsculas) ou pelo CPF/CNPJ
     * (com ou sem máscara, parte dele). Vazio = todos.
     */
    public List<Fornecedor> pesquisar(Sessao sessao, String texto, boolean incluirInativos) {
        sessao.exigir(Permissao.FORNECEDORES);
        List<Fornecedor> todos = transacao.executar(con -> dao.listar(con, incluirInativos));
        String termo = Textos.normalizarBusca(Textos.normalizarEspacos(texto));
        if (termo.isEmpty()) {
            return todos;
        }
        String documento = CpfCnpj.normalizar(texto);
        return todos.stream().filter(f -> contem(f.razaoSocial(), termo) || contem(f.nomeFantasia(), termo)
                || contem(f.cidade(), termo)
                || (f.cpfCnpj() != null && !documento.isEmpty() && f.cpfCnpj().contains(documento))).toList();
    }

    public Fornecedor buscar(Sessao sessao, long id) {
        sessao.exigir(Permissao.FORNECEDORES);
        return transacao.executar(con -> buscar(con, id));
    }

    /** @return código (id) do fornecedor */
    public long criar(Sessao sessao, DadosFornecedor dados) {
        sessao.exigir(Permissao.FORNECEDORES);
        FornecedorDAO.Gravacao g = ValidacaoFornecedor.validar(dados);
        return transacao.executar(con -> {
            conferirDocumentoLivre(con, g.cpfCnpj(), null);
            return dao.inserir(con, g, OffsetDateTime.now(clock));
        });
    }

    public void alterar(Sessao sessao, long id, DadosFornecedor dados) {
        sessao.exigir(Permissao.FORNECEDORES);
        FornecedorDAO.Gravacao g = ValidacaoFornecedor.validar(dados);
        transacao.executar(con -> {
            travar(con, id);
            conferirDocumentoLivre(con, g.cpfCnpj(), id);
            dao.alterar(con, id, g, OffsetDateTime.now(clock));
            return null;
        });
    }

    /** Inativo não aparece na escolha do fornecedor de uma entrada nova, mas continua nas entradas e no R17. */
    public void alterarSituacao(Sessao sessao, long id, boolean ativo) {
        sessao.exigir(Permissao.FORNECEDORES);
        transacao.executar(con -> {
            Fornecedor atual = travar(con, id);
            if (atual.ativo() != ativo) {
                dao.alterarSituacao(con, id, ativo, OffsetDateTime.now(clock));
            }
            return null;
        });
    }

    /**
     * Exclui o fornecedor só se nenhuma entrada de mercadoria o usa.
     *
     * @throws RegistroEmUsoException se houver entrada (a tela oferece inativar)
     */
    public void excluir(Sessao sessao, long id) {
        sessao.exigir(Permissao.FORNECEDORES);
        transacao.executar(con -> {
            Fornecedor atual = travar(con, id);
            int entradas = dao.contarEntradas(con, id);
            int pedidos = dao.contarPedidos(con, id); // F16.3
            if (entradas > 0 || pedidos > 0) {
                List<String> partes = new ArrayList<>();
                if (entradas > 0) {
                    partes.add((entradas == 1 ? "1 entrada" : entradas + " entradas") + " de mercadoria");
                }
                if (pedidos > 0) {
                    partes.add(pedidos == 1 ? "1 pedido de compra" : pedidos + " pedidos de compra");
                }
                throw new RegistroEmUsoException("O fornecedor \"" + atual.razaoSocial() + "\" não pode ser excluído "
                        + "porque tem " + String.join(" e ", partes) + ". "
                        + "Você pode inativá-lo: ele sai da escolha de novas entradas, mas continua nas entradas "
                        + "lançadas e nos relatórios.");
            }
            dao.excluir(con, id);
            return null;
        });
    }

    private Fornecedor buscar(Connection con, long id) throws SQLException {
        return dao.buscar(con, id).orElseThrow(() -> new NegocioException("Fornecedor não encontrado."));
    }

    private Fornecedor travar(Connection con, long id) throws SQLException {
        return dao.buscarParaAtualizar(con, id).orElseThrow(() -> new NegocioException("Fornecedor não encontrado."));
    }

    /** CPF/CNPJ repetido: a mensagem diz qual fornecedor já o tem. */
    private void conferirDocumentoLivre(Connection con, String cpfCnpj, Long ignorarId) throws SQLException {
        if (cpfCnpj == null) {
            return;
        }
        dao.donoDoDocumento(con, cpfCnpj, ignorarId).ifPresent(dono -> {
            throw new NegocioException("Não foi possível salvar: o " + (cpfCnpj.length() == 11 ? "CPF " : "CNPJ ")
                    + CpfCnpj.formatar(cpfCnpj) + " já pertence ao fornecedor \"" + dono.razaoSocial()
                    + "\" (código " + dono.id() + ").");
        });
    }

    private static boolean contem(String campo, String termo) {
        return campo != null && Textos.normalizarBusca(campo).contains(termo);
    }
}
