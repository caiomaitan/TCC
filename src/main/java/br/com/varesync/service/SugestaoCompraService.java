package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Permissao;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.SugestaoCompra;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Aba Sugestão de compra da tela Compras (F16.2), com a permissão COMPRAS: a mesma conta do R26
 * ({@link SugestaoCompra}), filtrada pelo fornecedor principal ou pela categoria, com {@code compras.dias_media} e
 * {@code compras.cobertura_dias} lidos a cada consulta.
 */
public class SugestaoCompraService {

    private final Transacao transacao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final FornecedorDAO fornecedorDAO;
    private final CategoriaDAO categoriaDAO;
    private final Clock clock;

    /** Linhas e os parâmetros usados (para o cabeçalho da aba). */
    public record Resultado(List<SugestaoCompra.Linha> linhas, Configuracoes.Compras parametros) {
    }

    public SugestaoCompraService(Transacao transacao, ConfiguracaoDAO configuracaoDAO, FornecedorDAO fornecedorDAO,
                                 CategoriaDAO categoriaDAO, Clock clock) {
        this.transacao = transacao;
        this.configuracaoDAO = configuracaoDAO;
        this.fornecedorDAO = fornecedorDAO;
        this.categoriaDAO = categoriaDAO;
        this.clock = clock;
    }

    /**
     * Todos os produtos ativos do filtro, por nome (a tela escolhe se mostra só os que têm sugestão). Pode demorar com
     * muitos produtos: a tela chama em {@code Task}.
     *
     * @param fornecedorId fornecedor principal; nulo = todos
     * @param categoriaId  nulo = todas
     */
    public Resultado consultar(Sessao sessao, Long fornecedorId, Long categoriaId) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> {
            Configuracoes.Compras parametros = configuracaoDAO.carregar(con).compras();
            return new Resultado(SugestaoCompra.consultar(con, fornecedorId, categoriaId, LocalDate.now(clock),
                    clock.getZone(), parametros), parametros);
        });
    }

    /** Fornecedores ativos para o filtro. */
    public List<Opcao> fornecedores(Sessao sessao) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> fornecedorDAO.listar(con, false).stream()
                .map(f -> new Opcao(f.id(), f.razaoSocial())).toList());
    }

    /** Categorias ativas para o filtro. */
    public List<Opcao> categorias(Sessao sessao) {
        sessao.exigir(Permissao.COMPRAS);
        return transacao.executar(con -> categoriaDAO.listar(con, false).stream()
                .map(c -> new Opcao(c.id(), c.nome())).toList());
    }
}
