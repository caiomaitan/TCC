package br.com.varesync.service;

import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.ContaPagarDAO;
import br.com.varesync.dao.FormaPagamentoDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoVenda;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.FiltroFinanceiro;
import br.com.varesync.report.FiltroValidade;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.Opcao;
import br.com.varesync.report.ParametrosRelatorio;
import br.com.varesync.report.Relatorio;
import br.com.varesync.report.RelSemGiro;
import br.com.varesync.report.Relatorios;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.report.TipoFiltro;
import br.com.varesync.util.Textos;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Relatórios (7.3, 7.4): todos exigem a permissão RELATORIOS (6.2: lucro e margem só para quem a tem). Confere os
 * filtros, roda a consulta do relatório numa transação somente leitura e devolve o resultado com o cabeçalho (loja,
 * filtros, quem emitiu e quando).
 */
public class RelatorioService {

    static final int TOP_N_MAXIMO = 1000;

    private final Transacao transacao;
    private final ConfiguracaoDAO configuracaoDAO;
    private final CategoriaDAO categoriaDAO;
    private final ProdutoDAO produtoDAO;
    private final UsuarioDAO usuarioDAO;
    private final FornecedorDAO fornecedorDAO;
    private final FormaPagamentoDAO formaDAO;
    private final ContaPagarDAO contaPagarDAO;
    private final Clock clock;

    /**
     * Escolhas dos filtros de lista: operadores (todos os usuários, inclusive inativos), categorias, fornecedores,
     * formas e categorias de despesa (R23; vazia sem a permissão FINANCEIRO).
     */
    public record OpcoesFiltro(List<Opcao> operadores, List<Opcao> categorias, List<Opcao> fornecedores,
                               List<Opcao> formas, List<Opcao> categoriasDespesa) {
    }

    public RelatorioService(Transacao transacao, ConfiguracaoDAO configuracaoDAO, CategoriaDAO categoriaDAO,
                            ProdutoDAO produtoDAO, UsuarioDAO usuarioDAO, FornecedorDAO fornecedorDAO,
                            FormaPagamentoDAO formaDAO, ContaPagarDAO contaPagarDAO, Clock clock) {
        this.contaPagarDAO = contaPagarDAO;
        this.formaDAO = formaDAO;
        this.transacao = transacao;
        this.configuracaoDAO = configuracaoDAO;
        this.categoriaDAO = categoriaDAO;
        this.produtoDAO = produtoDAO;
        this.usuarioDAO = usuarioDAO;
        this.fornecedorDAO = fornecedorDAO;
        this.clock = clock;
    }

    /** O catálogo, sem os relatórios que pedem uma permissão que o usuário não tem (R23 a R25: FINANCEIRO). */
    public List<Relatorio<?>> disponiveis(Sessao sessao) {
        sessao.exigir(Permissao.RELATORIOS);
        return Relatorios.todos().stream()
                .filter(r -> r.permissoesExtras().stream().allMatch(sessao::pode)).toList();
    }

    public OpcoesFiltro opcoes(Sessao sessao) {
        sessao.exigir(Permissao.RELATORIOS);
        boolean financeiro = sessao.pode(Permissao.FINANCEIRO);
        return transacao.executar(con -> new OpcoesFiltro(
                usuarioDAO.listar(con).stream().map(u -> new Opcao(u.id(), u.nome())).toList(),
                categoriaDAO.listar(con, true).stream().map(c -> new Opcao(c.id(), c.nome())).toList(),
                fornecedorDAO.listar(con, true).stream().map(f -> new Opcao(f.id(), f.razaoSocial())).toList(),
                formaDAO.listar(con).stream().filter(f -> f.tipo() != TipoFormaPagamento.CREDIARIO
                        && f.tipo() != TipoFormaPagamento.CREDITO_CLIENTE)
                        .map(f -> new Opcao(f.id(), f.descricao())).toList(),
                financeiro ? contaPagarDAO.categorias(con).stream().map(c -> new Opcao(c.id(), c.nome())).toList()
                        : List.of()));
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

    /** Dias do filtro do R12 ao abrir a tela: {@code relatorio.dias_sem_giro} (30 se faltar ou for inválido). */
    public int diasSemGiroPadrao(Sessao sessao) {
        sessao.exigir(Permissao.RELATORIOS);
        return transacao.executar(this::diasSemGiro);
    }

    /**
     * Gera o relatório. Pode demorar com muitos dados: a tela chama em {@code Task}. Sem dias informados, o R12 usa
     * {@code relatorio.dias_sem_giro}.
     */
    public <T> ResultadoRelatorio<T> gerar(Sessao sessao, Relatorio<T> relatorio, Filtros filtros) {
        sessao.exigir(Permissao.RELATORIOS);
        relatorio.permissoesExtras().forEach(sessao::exigir);
        conferir(relatorio, filtros);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        return transacao.executar(con -> {
            Configuracoes config = configuracaoDAO.carregar(con);
            ParametrosRelatorio parametros = new ParametrosRelatorio(clock.getZone(),
                    config.margemMinimaPct(),
                    agora.atZoneSameInstant(clock.getZone()).toLocalDate(), config.compras());
            Filtros usados = relatorio.filtros().contains(TipoFiltro.DIAS) && filtros.dias() == null
                    ? filtros.comDias(config.diasSemGiro()) : filtros;
            if (relatorio.filtros().contains(TipoFiltro.MES) && usados.financeiro().mes() == null) {
                FiltroFinanceiro f = usados.financeiro();
                usados = usados.comFinanceiro(new FiltroFinanceiro(f.categoriaDespesa(), f.agrupamento(),
                        YearMonth.from(parametros.hoje())));
            }
            if (relatorio.filtros().contains(TipoFiltro.VALIDADE) && usados.validade().dias() == null) {
                usados = usados.comValidade(new FiltroValidade(config.validadeAlertaDias(),
                        usados.validade().incluirVencidos()));
            }
            List<T> linhas = relatorio.consultar(con, usados, parametros);
            String loja = config.loja().nome().isBlank() ? "VareSync" : config.loja().nome();
            return new ResultadoRelatorio<>(relatorio, usados, relatorio.colunas(usados), linhas, loja,
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
        if (relatorio.filtros().contains(TipoFiltro.VENCIMENTO) && filtros.crediario().vencimentoDe() != null
                && filtros.crediario().vencimentoAte() != null
                && filtros.crediario().vencimentoDe().isAfter(filtros.crediario().vencimentoAte())) {
            throw new NegocioException("A data inicial do vencimento é depois da final.");
        }
        if (relatorio.filtros().contains(TipoFiltro.TOP_N) && filtros.topN() != null
                && (filtros.topN() < 1 || filtros.topN() > TOP_N_MAXIMO)) {
            throw new NegocioException("A quantidade de produtos do ranking deve estar entre 1 e " + TOP_N_MAXIMO
                    + ".");
        }
        if (relatorio.filtros().contains(TipoFiltro.DIAS) && filtros.dias() != null
                && (filtros.dias() < 1 || filtros.dias() > RelSemGiro.DIAS_MAXIMO)) {
            throw new NegocioException("Os dias sem venda devem estar entre 1 e " + RelSemGiro.DIAS_MAXIMO + ".");
        }
        if (relatorio.filtros().contains(TipoFiltro.VALIDADE) && filtros.validade().dias() != null
                && (filtros.validade().dias() < 0 || filtros.validade().dias() > Configuracoes.VALIDADE_ALERTA_MAXIMO)) {
            throw new NegocioException("Os dias para vencer devem estar entre 0 e "
                    + Configuracoes.VALIDADE_ALERTA_MAXIMO + ".");
        }
        if (relatorio.filtros().contains(TipoFiltro.CRITERIO) && filtros.criterio() != null
                && !relatorio.criterios().contains(filtros.criterio())) {
            throw new NegocioException("Critério " + filtros.criterio().rotulo().toLowerCase()
                    + " não se aplica a este relatório.");
        }
        if (relatorio.filtros().contains(TipoFiltro.TIPO_MOVIMENTO) && filtros.tipo() != null
                && !relatorio.tiposMovimento().contains(filtros.tipo())) {
            throw new NegocioException("O tipo " + filtros.tipo().descricao().toLowerCase()
                    + " não se aplica a este relatório.");
        }
    }

    /** Dias do filtro do R29 ao abrir a tela: {@code validade.alerta_dias} (F17.3). */
    public int validadeAlertaDias(Sessao sessao) {
        sessao.exigir(Permissao.RELATORIOS);
        return transacao.executar(con -> configuracaoDAO.carregar(con).validadeAlertaDias());
    }

    /** {@code relatorio.dias_sem_giro} ({@link Configuracoes}: o valor da V2 se faltar ou for inválido). */
    private int diasSemGiro(Connection con) throws SQLException {
        return configuracaoDAO.carregar(con).diasSemGiro();
    }
}
