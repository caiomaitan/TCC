package br.com.varesync.config;

import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.CaixaDAO;
import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.LoteDAO;
import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.ContaFinanceiraDAO;
import br.com.varesync.dao.ContaPagarDAO;
import br.com.varesync.dao.CreditoClienteDAO;
import br.com.varesync.dao.DemoDAO;
import br.com.varesync.dao.DevolucaoDAO;
import br.com.varesync.dao.EntradaDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.FormaPagamentoDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.InventarioDAO;
import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.dao.PainelDAO;
import br.com.varesync.dao.PedidoCompraDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.ProdutoFornecedorDAO;
import br.com.varesync.dao.PromocaoDAO;
import br.com.varesync.dao.RecebimentoDAO;
import br.com.varesync.dao.RecebivelDAO;
import br.com.varesync.dao.TituloReceberDAO;
import br.com.varesync.dao.UnidadeMedidaDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.dao.VendaDAO;
import br.com.varesync.service.AcessoService;
import br.com.varesync.service.AuditoriaService;
import br.com.varesync.service.CaixaService;
import br.com.varesync.service.CategoriaService;
import br.com.varesync.service.ClienteService;
import br.com.varesync.service.ConfiguracaoService;
import br.com.varesync.service.ConsultaAuditoriaService;
import br.com.varesync.service.ContaFinanceiraService;
import br.com.varesync.service.ContaPagarService;
import br.com.varesync.service.CreditoService;
import br.com.varesync.service.DevolucaoService;
import br.com.varesync.service.EntradaService;
import br.com.varesync.service.EstoqueService;
import br.com.varesync.service.FluxoCaixaService;
import br.com.varesync.service.FornecedorService;
import br.com.varesync.service.GeradorDemo;
import br.com.varesync.service.ImportacaoNfeService;
import br.com.varesync.service.ImportacaoProdutosService;
import br.com.varesync.service.InventarioService;
import br.com.varesync.service.PainelService;
import br.com.varesync.service.PedidoCompraService;
import br.com.varesync.service.PrecoService;
import br.com.varesync.service.ProdutoFornecedorService;
import br.com.varesync.service.ProdutoService;
import br.com.varesync.service.PromocaoService;
import br.com.varesync.service.RecebimentoService;
import br.com.varesync.service.RecebivelService;
import br.com.varesync.service.RelatorioService;
import br.com.varesync.service.Senhas;
import br.com.varesync.service.SugestaoCompraService;
import br.com.varesync.service.Transacao;
import br.com.varesync.service.UnidadeService;
import br.com.varesync.service.UsuarioService;
import br.com.varesync.service.VendaService;
import br.com.varesync.util.RelogioAjustavel;
import java.time.Clock;
import javax.sql.DataSource;

/**
 * Os services do sistema montados sobre um {@link Clock}. O {@link AppContext} monta um conjunto com o relógio do
 * computador e outro com o relógio ajustável do gerador de dados de demonstração (6.14), que grava no passado.
 */
public record Servicos(Clock clock, Transacao transacao, AcessoService acesso, UsuarioService usuarios,
                       CategoriaService categorias, ProdutoService produtos, EstoqueService estoque,
                       CaixaService caixa, VendaService vendas, InventarioService inventarios,
                       FornecedorService fornecedores, EntradaService compras, RelatorioService relatorios,
                       PainelService painel, ConfiguracaoService configuracoes, PromocaoService promocoes,
                       ImportacaoProdutosService importacao, ConsultaAuditoriaService auditoria,
                       ImportacaoNfeService nfe, UnidadeService unidades, ClienteService clientes,
                       CreditoService credito, DevolucaoService devolucoes, RecebimentoService recebimentos,
                       ContaFinanceiraService contas, RecebivelService recebiveis, ContaPagarService contasPagar,
                       FluxoCaixaService fluxoCaixa, ProdutoFornecedorService vinculos,
                       SugestaoCompraService sugestao, PedidoCompraService pedidos) {

    public static Servicos montar(DataSource dataSource, Clock clock, String terminalId, Senhas senhas) {
        Transacao transacao = new Transacao(dataSource);
        UsuarioDAO usuarioDAO = new UsuarioDAO();
        ConfiguracaoDAO configuracaoDAO = new ConfiguracaoDAO();
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), clock, terminalId);
        AcessoService acesso = new AcessoService(transacao, usuarioDAO, configuracaoDAO, auditoria, senhas, clock,
                terminalId);
        UsuarioService usuarios = new UsuarioService(transacao, usuarioDAO, configuracaoDAO, auditoria, senhas,
                clock);

        ProdutoDAO produtoDAO = new ProdutoDAO();
        CategoriaDAO categoriaDAO = new CategoriaDAO();
        EstoqueDAO estoqueDAO = new EstoqueDAO();
        EstoqueService estoque = new EstoqueService(transacao, produtoDAO, estoqueDAO, new MovimentoEstoqueDAO(),
                new LoteDAO(),
                auditoria, clock);
        CategoriaService categorias = new CategoriaService(transacao, categoriaDAO);
        ProdutoService produtos = new ProdutoService(transacao, produtoDAO, categoriaDAO, new UnidadeMedidaDAO(),
                configuracaoDAO, estoque, auditoria, clock);
        ContaFinanceiraDAO contaDAO = new ContaFinanceiraDAO();
        ContaFinanceiraService contas = new ContaFinanceiraService(transacao, contaDAO, auditoria, clock);
        CaixaService caixa = new CaixaService(transacao, new CaixaDAO(), new RecebimentoDAO(), configuracaoDAO, contaDAO,
                contas, auditoria, clock);
        PromocaoDAO promocaoDAO = new PromocaoDAO();
        FormaPagamentoDAO formaPagamentoDAO = new FormaPagamentoDAO();
        RecebivelService recebiveis = new RecebivelService(transacao, new RecebivelDAO(), formaPagamentoDAO, contas,
                auditoria, clock);
        CreditoClienteDAO creditoDAO = new CreditoClienteDAO();
        CreditoService credito = new CreditoService(transacao, creditoDAO, new ClienteDAO(), auditoria, clock);
        TituloReceberDAO tituloDAO = new TituloReceberDAO();
        VendaService vendas = new VendaService(transacao, produtoDAO, new VendaDAO(), formaPagamentoDAO,
                configuracaoDAO, estoqueDAO, estoque, caixa, new PrecoService(promocaoDAO), auditoria, new ClienteDAO(),
                creditoDAO, credito, tituloDAO, recebiveis, clock);
        InventarioService inventarios = new InventarioService(transacao, new InventarioDAO(), produtoDAO,
                categoriaDAO, estoqueDAO, estoque, auditoria, clock);
        FornecedorDAO fornecedorDAO = new FornecedorDAO();
        FornecedorService fornecedores = new FornecedorService(transacao, fornecedorDAO, clock);
        ContaPagarService contasPagar = new ContaPagarService(transacao, new ContaPagarDAO(), fornecedorDAO, contas,
                auditoria, clock);
        ProdutoFornecedorDAO vinculoDAO = new ProdutoFornecedorDAO();
        ProdutoFornecedorService vinculos = new ProdutoFornecedorService(transacao, vinculoDAO, produtoDAO,
                fornecedorDAO, clock);
        EntradaService compras = new EntradaService(transacao, new EntradaDAO(), fornecedorDAO, produtoDAO, estoqueDAO,
                configuracaoDAO, estoque, contasPagar, vinculoDAO, new PedidoCompraDAO(), auditoria, clock);
        RelatorioService relatorios = new RelatorioService(transacao, configuracaoDAO, categoriaDAO, produtoDAO,
                usuarioDAO, fornecedorDAO, formaPagamentoDAO, new ContaPagarDAO(), clock);
        PainelService painel = new PainelService(transacao, new PainelDAO(), estoqueDAO, caixa, new AuditoriaDAO(),
                tituloDAO, contaDAO, clock);
        ConfiguracaoService configuracoes = new ConfiguracaoService(transacao, configuracaoDAO, formaPagamentoDAO,
                produtoDAO, contaDAO, auditoria);
        PromocaoService promocoes = new PromocaoService(transacao, promocaoDAO, produtoDAO, auditoria, clock);
        ImportacaoProdutosService importacao = new ImportacaoProdutosService(transacao, produtos, produtoDAO,
                categoriaDAO, new UnidadeMedidaDAO());
        return new Servicos(clock, transacao, acesso, usuarios, categorias, produtos, estoque, caixa, vendas,
                inventarios, fornecedores, compras, relatorios, painel, configuracoes, promocoes, importacao,
                new ConsultaAuditoriaService(transacao, new AuditoriaDAO(), clock),
                new ImportacaoNfeService(transacao, fornecedorDAO, new EntradaDAO(), produtoDAO, vinculoDAO, compras,
                        vinculos, clock),
                new UnidadeService(transacao, new UnidadeMedidaDAO()),
                new ClienteService(transacao, new ClienteDAO(), creditoDAO, tituloDAO, configuracaoDAO, auditoria,
                        clock),
                credito, new DevolucaoService(transacao, new DevolucaoDAO(), new ClienteDAO(), creditoDAO, tituloDAO,
                        usuarioDAO, configuracaoDAO, estoque, caixa, credito, contas, auditoria, clock),
                new RecebimentoService(transacao, new RecebimentoDAO(), tituloDAO, new ClienteDAO(), formaPagamentoDAO,
                        configuracaoDAO, caixa, recebiveis, auditoria, clock),
                contas, recebiveis, contasPagar, new FluxoCaixaService(transacao, clock), vinculos,
                new SugestaoCompraService(transacao, configuracaoDAO, fornecedorDAO, categoriaDAO, clock),
                new PedidoCompraService(transacao, new PedidoCompraDAO(), fornecedorDAO, produtoDAO, vinculoDAO,
                        configuracaoDAO, auditoria, clock));
    }

    /**
     * Gerador de dados de demonstração (6.14) com um conjunto próprio de services sobre um relógio ajustável, que ele
     * posiciona no passado; {@code relogioReal} dá o "hoje" (os 60 dias terminam ontem).
     */
    public static GeradorDemo geradorDemo(DataSource dataSource, Clock relogioReal, String terminalId,
                                          Senhas senhas) {
        RelogioAjustavel relogio = new RelogioAjustavel(relogioReal.instant(), relogioReal.getZone());
        Servicos s = montar(dataSource, relogio, terminalId, senhas);
        return new GeradorDemo(relogio, relogioReal, s.transacao(), s.acesso(), s.usuarios(), s.categorias(),
                s.produtos(), s.estoque(), s.caixa(), s.vendas(), s.inventarios(), s.fornecedores(), s.compras(),
                s.clientes(), s.devolucoes(), s.recebimentos(), s.configuracoes(), s.contas(), s.recebiveis(),
                s.contasPagar(), s.vinculos(), s.pedidos(), new DemoDAO(), new CaixaDAO(),
                new ConfiguracaoDAO(), new AuditoriaService(new AuditoriaDAO(), relogio, terminalId));
    }
}
