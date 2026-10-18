package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.ParametrosRelatorio;
import br.com.varesync.report.Relatorio;
import br.com.varesync.report.Relatorios;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.TipoFiltro;
import br.com.varesync.util.Textos;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Relatórios (7.3, 7.4): todos exigem a permissão RELATORIOS (6.2: lucro e margem só para quem a tem). Confere os
 * filtros, roda a consulta do relatório numa transação somente leitura e devolve o resultado com o cabeçalho (loja,
 * filtros, quem emitiu e quando).
 */
public class RelatorioService {

    static final String CHAVE_MARGEM_MINIMA = "relatorio.margem_minima_pct";
    static final BigDecimal MARGEM_MINIMA_PADRAO = BigDecimal.valueOf(15);
    static final String CHAVE_EMPRESA = "empresa.nome";
    static final int TOP_N_MAXIMO = 1000;

    private final Transacao transacao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final CategoriaDAO categoriaDAO;
    private final ProdutoDAO produtoDAO;
    private final UsuarioDAO usuarioDAO;
    private final Clock clock;

    /** Escolhas dos filtros de lista: operadores (todos os usuários, inclusive inativos) e categorias. */
    public record OpcoesFiltro(List<Opcao> operadores, List<Opcao> categorias) {
    }

    public RelatorioService(Transacao transacao, ConfiguracaoDAO configuracaoDAO, CategoriaDAO categoriaDAO,
                            ProdutoDAO produtoDAO, UsuarioDAO usuarioDAO, Clock clock) {
        this.transacao = transacao;
        this.configuracaoDAO = configuracaoDAO;
        this.categoriaDAO = categoriaDAO;
        this.produtoDAO = produtoDAO;
        this.usuarioDAO = usuarioDAO;
        this.clock = clock;
    }

    public List<Relatorio<?>> disponiveis(Sessao sessao) {
        sessao.exigir(Permissao.RELATORIOS);
        return Relatorios.todos();
    }

    public OpcoesFiltro opcoes(Sessao sessao) {
        sessao.exigir(Permissao.RELATORIOS);
        return transacao.executar(con -> new OpcoesFiltro(
                usuarioDAO.listar(con).stream().map(u -> new Opcao(u.id(), u.nome())).toList(),
                categoriaDAO.listar(con, true).stream().map(c -> new Opcao(c.id(), c.nome())).toList()));
    }

    /** Período inicial da tela: o mês corrente até hoje. */
    public Periodo periodoPadrao() {
        LocalDate hoje = LocalDate.now(clock);
        return new Periodo(hoje.withDayOfMonth(1), hoje);
    }

    /**
     * Produto do filtro, pelo código de barras exato ou pelo código interno (inclusive inativo).
     *
     * @throws NegocioException se não encontrar
     */
    public Opcao produto(Sessao sessao, String digitado) {
        sessao.exigir(Permissao.RELATORIOS);
        String codigo = Textos.normalizarEspacos(digitado);
        if (!Textos.soDigitos(codigo)) {
            throw new NegocioException("Informe o código ou o código de barras do produto.");
        }
        return transacao.executar(con -> {
            Optional<ProdutoVenda> produto = produtoDAO.buscarParaVendaPorCodigo(con, codigo);
            if (produto.isEmpty()) {
                produto = produtoDAO.buscarParaVenda(con, ProdutoDAO.idOuNenhum(codigo));
            }
            return produto.map(p -> new Opcao(p.id(), p.nome()));
        }).orElseThrow(() -> new NegocioException("Produto não encontrado (código " + codigo + ")."));
    }

    /** Gera o relatório. Pode demorar com muitos dados: a tela chama em {@code Task}. */
    public <T> ResultadoRelatorio<T> gerar(Sessao sessao, Relatorio<T> relatorio, Filtros filtros) {
        sessao.exigir(Permissao.RELATORIOS);
        conferir(relatorio, filtros);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            ParametrosRelatorio parametros = new ParametrosRelatorio(clock.getZone(), margemMinima(con));
            List<T> linhas = relatorio.consultar(con, filtros, parametros);
            String loja = configuracaoDAO.buscar(con, CHAVE_EMPRESA).filter(n -> !n.isBlank()).orElse("VareSync");
            return new ResultadoRelatorio<>(relatorio, filtros, relatorio.colunas(filtros), linhas, loja,
                    sessao.usuario().nome(), agora);
        });
    }

    static void conferir(Relatorio<?> relatorio, Filtros filtros) {
        if (relatorio.filtros().contains(TipoFiltro.PERIODO)) {
            Periodo periodo = filtros.periodo();
            if (periodo == null || periodo.inicio() == null || periodo.fim() == null) {
                throw new NegocioException("Informe a data inicial e a final do período.");
            }
            if (periodo.inicio().isAfter(periodo.fim())) {
                throw new NegocioException("A data inicial é depois da final.");
            }
        }
        if (relatorio.filtros().contains(TipoFiltro.TOP_N) && filtros.topN() != null
                && (filtros.topN() < 1 || filtros.topN() > TOP_N_MAXIMO)) {
            throw new NegocioException("A quantidade de produtos do ranking deve estar entre 1 e " + TOP_N_MAXIMO
                    + ".");
        }
    }

    /** {@code relatorio.margem_minima_pct}; o valor da V2 (15) se faltar ou for inválido. */
    private BigDecimal margemMinima(Connection con) throws SQLException {
        Optional<String> valor = configuracaoDAO.buscar(con, CHAVE_MARGEM_MINIMA);
        if (valor.isEmpty()) {
            return MARGEM_MINIMA_PADRAO;
        }
        try {
            return new BigDecimal(valor.get().strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return MARGEM_MINIMA_PADRAO;
        }
    }
}
