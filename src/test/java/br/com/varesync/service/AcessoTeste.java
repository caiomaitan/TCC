package br.com.varesync.service;

import br.com.varesync.BancoTeste;
import br.com.varesync.RelogioTeste;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.CaixaDAO;
import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ClienteDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.ContaFinanceiraDAO;
import br.com.varesync.dao.ContaPagarDAO;
import br.com.varesync.dao.CreditoClienteDAO;
import br.com.varesync.dao.RecebivelDAO;
import br.com.varesync.dao.DevolucaoDAO;
import br.com.varesync.dao.EntradaDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.EtiquetaDAO;
import br.com.varesync.dao.FormaPagamentoDAO;
import br.com.varesync.dao.FornecedorDAO;
import br.com.varesync.dao.InventarioDAO;
import br.com.varesync.dao.LoteDAO;
import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.dao.PainelDAO;
import br.com.varesync.dao.PedidoCompraDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.ProdutoFornecedorDAO;
import br.com.varesync.dao.PromocaoDAO;
import br.com.varesync.dao.RecebimentoDAO;
import br.com.varesync.dao.TituloReceberDAO;
import br.com.varesync.dao.UnidadeMedidaDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.dao.VendaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.FormaReembolso;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Monta os services de acesso sobre o banco de teste, com relógio ajustável e BCrypt de custo baixo
 * (o custo 12 de produção é conferido em {@code SenhasTest}).
 */
final class AcessoTeste {

    static final String TERMINAL = "CAIXA-TESTE";
    static final int CUSTO_BCRYPT_TESTE = 4;
    private static final String CATEGORIAS_V2 = "'Mercearia', 'Bebidas', 'Hortifruti', 'Frios e laticínios', "
            + "'Açougue', 'Padaria', 'Limpeza', 'Higiene e perfumaria', 'Outros'";
    private static final String FORMAS_V2 = "'Dinheiro', 'PIX', 'Cartão de débito', 'Cartão de crédito', "
            + "'Vale-alimentação', 'Crédito do cliente', 'Crediário'";

    final RelogioTeste relogio = new RelogioTeste(Instant.parse("2026-10-06T13:00:00Z"));
    final Senhas senhas = new Senhas(CUSTO_BCRYPT_TESTE);
    final AcessoService acesso;
    final UsuarioService usuarios;
    final EstoqueService estoque;
    final CategoriaService categorias;
    final ProdutoService produtos;
    final CaixaService caixa;
    final VendaService vendas;
    final InventarioService inventarios;
    final FornecedorService fornecedores;
    final EntradaService compras;
    final RelatorioService relatorios;
    final PainelService painel;
    final ConfiguracaoService configuracoes;
    final PromocaoService promocoes;
    final ImportacaoProdutosService importacao;
    final ConsultaAuditoriaService consultaAuditoria;
    final ImportacaoNfeService nfe;
    final UnidadeService unidades;
    final ClienteService clientes;
    final CreditoService credito;
    final DevolucaoService devolucoes;
    final RecebimentoService recebimentos;
    final ContaFinanceiraService contas;
    final RecebivelService recebiveis;
    final ContaPagarService contasPagar;
    final FluxoCaixaService fluxoCaixa;
    final ProdutoFornecedorService vinculos;
    final SugestaoCompraService sugestao;
    final PedidoCompraService pedidos;
    final EtiquetaService etiquetas;
    final CargaBalancaService cargaBalanca;
    final FiscalService fiscal;
    private final DataSource dataSource;
    private final Transacao transacao;
    private final AuditoriaService auditoriaService;

    AcessoTeste(DataSource dataSource) {
        this.dataSource = dataSource;
        Transacao transacao = new Transacao(dataSource);
        UsuarioDAO usuarioDAO = new UsuarioDAO();
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), relogio, TERMINAL);
        acesso = new AcessoService(transacao, usuarioDAO, new ConfiguracaoDAO(), auditoria, senhas, relogio, TERMINAL);
        usuarios = new UsuarioService(transacao, usuarioDAO, new ConfiguracaoDAO(), auditoria, senhas, relogio);
        ProdutoDAO produtoDAO = new ProdutoDAO();
        CategoriaDAO categoriaDAO = new CategoriaDAO();
        estoque = new EstoqueService(transacao, produtoDAO, new EstoqueDAO(), new MovimentoEstoqueDAO(), new LoteDAO(),
                new ConfiguracaoDAO(), auditoria,
                relogio);
        categorias = new CategoriaService(transacao, categoriaDAO);
        produtos = new ProdutoService(transacao, produtoDAO, categoriaDAO, new UnidadeMedidaDAO(),
                new ConfiguracaoDAO(), estoque, auditoria, relogio);
        contas = new ContaFinanceiraService(transacao, new ContaFinanceiraDAO(), auditoria, relogio);
        recebiveis = new RecebivelService(transacao, new RecebivelDAO(), new FormaPagamentoDAO(), contas, auditoria,
                relogio);
        contasPagar = new ContaPagarService(transacao, new ContaPagarDAO(), new FornecedorDAO(), contas, auditoria,
                relogio);
        caixa = new CaixaService(transacao, new CaixaDAO(), new RecebimentoDAO(), new ConfiguracaoDAO(),
                new ContaFinanceiraDAO(), contas, auditoria, relogio);
        credito = new CreditoService(transacao, new CreditoClienteDAO(), new ClienteDAO(), auditoria, relogio);
        vendas = new VendaService(transacao, produtoDAO, new VendaDAO(), new FormaPagamentoDAO(),
                new ConfiguracaoDAO(), new EstoqueDAO(), estoque, caixa, new PrecoService(new PromocaoDAO(), produtoDAO), auditoria,
                new ClienteDAO(), new CreditoClienteDAO(), credito, new TituloReceberDAO(), recebiveis, relogio);
        inventarios = new InventarioService(transacao, new InventarioDAO(), produtoDAO, categoriaDAO, new EstoqueDAO(),
                estoque, auditoria, relogio);
        fornecedores = new FornecedorService(transacao, new FornecedorDAO(), relogio);
        vinculos = new ProdutoFornecedorService(transacao, new ProdutoFornecedorDAO(), produtoDAO, new FornecedorDAO(),
                relogio);
        compras = new EntradaService(transacao, new EntradaDAO(), new FornecedorDAO(), produtoDAO, new EstoqueDAO(),
                new ConfiguracaoDAO(), estoque, contasPagar, new ProdutoFornecedorDAO(), new PedidoCompraDAO(), auditoria,
                relogio);
        relatorios = new RelatorioService(transacao, new ConfiguracaoDAO(), categoriaDAO, produtoDAO, usuarioDAO,
                new FornecedorDAO(), new FormaPagamentoDAO(), new ContaPagarDAO(), relogio);
        cargaBalanca = new CargaBalancaService(transacao, new br.com.varesync.dao.CargaBalancaDAO(),
                new ConfiguracaoDAO(), auditoria, relogio);
        painel = new PainelService(transacao, new PainelDAO(), new EstoqueDAO(), caixa, new AuditoriaDAO(),
                new TituloReceberDAO(), new ContaFinanceiraDAO(), new LoteDAO(), new ConfiguracaoDAO(), new EtiquetaDAO(),
                cargaBalanca, relogio);
        configuracoes = new ConfiguracaoService(transacao, new ConfiguracaoDAO(), new FormaPagamentoDAO(), produtoDAO,
                new ContaFinanceiraDAO(), auditoria);
        promocoes = new PromocaoService(transacao, new PromocaoDAO(), produtoDAO, auditoria, relogio);
        importacao = new ImportacaoProdutosService(transacao, produtos, produtoDAO, categoriaDAO,
                new UnidadeMedidaDAO());
        consultaAuditoria = new ConsultaAuditoriaService(transacao, new AuditoriaDAO(), relogio);
        nfe = new ImportacaoNfeService(transacao, new FornecedorDAO(), new EntradaDAO(), produtoDAO,
                new ProdutoFornecedorDAO(), compras, vinculos, relogio);
        unidades = new UnidadeService(transacao, new UnidadeMedidaDAO());
        clientes = new ClienteService(transacao, new ClienteDAO(), new CreditoClienteDAO(), new TituloReceberDAO(),
                new ConfiguracaoDAO(), auditoria, relogio);
        devolucoes = new DevolucaoService(transacao, new DevolucaoDAO(), new ClienteDAO(), new CreditoClienteDAO(),
                new TituloReceberDAO(), usuarioDAO, new ConfiguracaoDAO(), estoque, caixa, credito, contas, auditoria,
                relogio);
        recebimentos = new RecebimentoService(transacao, new RecebimentoDAO(), new TituloReceberDAO(), new ClienteDAO(),
                new FormaPagamentoDAO(), new ConfiguracaoDAO(), caixa, recebiveis, auditoria, relogio);
        fluxoCaixa = new FluxoCaixaService(transacao, relogio);
        sugestao = new SugestaoCompraService(transacao, new ConfiguracaoDAO(), new FornecedorDAO(), categoriaDAO, relogio);
        pedidos = new PedidoCompraService(transacao, new PedidoCompraDAO(), new FornecedorDAO(), produtoDAO,
                new ProdutoFornecedorDAO(), new ConfiguracaoDAO(), auditoria, relogio);
        etiquetas = new EtiquetaService(transacao, new EtiquetaDAO(), new ConfiguracaoDAO(), auditoria, relogio);
        fiscal = new FiscalService(transacao, new br.com.varesync.dao.PerfilTributarioDAO(),
                new br.com.varesync.dao.FiscalDAO(), categoriaDAO, nfe, new ConfiguracaoDAO(), auditoria, relogio);
        this.transacao = transacao;
        this.auditoriaService = auditoria;
    }

    /** Backup sobre o banco de teste, com o executor, a pasta padrão e os lugares de busca do pg_dump dados. */
    BackupService backup(ExecutorProcesso executor, BackupService.Conexao conexao, java.nio.file.Path pastaPadrao,
                         List<java.nio.file.Path> instalacoes, String path) {
        return new BackupService(transacao, new ConfiguracaoDAO(), new AuditoriaDAO(), auditoriaService, executor,
                conexao, pastaPadrao, instalacoes, path, relogio);
    }

    /**
     * Volta ao estado de primeiro acesso: sem usuários nem nada que dependa deles (produtos, kardex, auditoria, entradas,
     * clientes), sem fornecedores, só as categorias da V2 (todas ativas) e a configuração padrão. Usa TRUNCATE porque o kardex é
     * imutável: o trigger recusa DELETE em {@code movimento_estoque}, mas não age no TRUNCATE.
     */
    void limpar() throws SQLException {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            // TRUNCATE não aciona triggers de linha: razão e saldo das contas saem juntos e o saldo zero volta abaixo.
            st.executeUpdate("TRUNCATE usuario, fornecedor, conta_saldo CASCADE");
            // F19.3: as tabelas de apoio não dependem de usuário.
            st.executeUpdate("TRUNCATE ncm, cest");
            st.executeUpdate("UPDATE forma_pagamento SET taxa_pct = 0, dias_recebimento = 0, conta_destino_id = "
                    + "CASE WHEN tipo IN ('DINHEIRO', 'CREDITO_CLIENTE', 'CREDIARIO') THEN NULL ELSE "
                    + "(SELECT id FROM conta_financeira WHERE nome = 'Banco') END");
            st.executeUpdate("DELETE FROM categoria_despesa WHERE id > 12");
            st.executeUpdate("UPDATE categoria_despesa SET ativa = TRUE WHERE NOT ativa");
            st.execute("""
                    DO $$ BEGIN
                        PERFORM set_config('varesync.via_conta', 'on', true);
                        DELETE FROM conta_financeira WHERE nome NOT IN ('Cofre', 'Banco');
                        UPDATE conta_financeira SET ativa = TRUE, padrao_caixa = FALSE;
                        UPDATE conta_financeira SET padrao_caixa = TRUE WHERE nome = 'Cofre';
                        INSERT INTO conta_saldo (conta_id, saldo) SELECT id, 0 FROM conta_financeira;
                        PERFORM set_config('varesync.via_conta', 'off', true);
                    END $$
                    """);
            st.executeUpdate("DELETE FROM categoria WHERE nome NOT IN (" + CATEGORIAS_V2 + ")");
            st.executeUpdate("UPDATE categoria SET ativa = TRUE WHERE NOT ativa");
            st.executeUpdate("DELETE FROM forma_pagamento WHERE descricao NOT IN (" + FORMAS_V2 + ")");
            st.executeUpdate("UPDATE forma_pagamento SET ativa = TRUE WHERE NOT ativa");
            st.executeUpdate("DELETE FROM unidade_medida WHERE sigla NOT IN ('UN', 'KG', 'L', 'M', 'CX', 'PCT', 'FD', "
                    + "'DZ')");
            st.executeUpdate("""
                    INSERT INTO unidade_medida (sigla, descricao, fracionada) VALUES
                        ('UN', 'Unidade', FALSE), ('KG', 'Quilograma', TRUE), ('L', 'Litro', TRUE),
                        ('M', 'Metro', TRUE), ('CX', 'Caixa', FALSE), ('PCT', 'Pacote', FALSE),
                        ('FD', 'Fardo', FALSE), ('DZ', 'Dúzia', FALSE)
                    ON CONFLICT (sigla) DO UPDATE SET descricao = EXCLUDED.descricao, fracionada = EXCLUDED.fracionada
                    """);
            try (PreparedStatement ps = con.prepareStatement("UPDATE configuracao SET valor = ? WHERE chave = ?")) {
                for (var valor : Configuracoes.PADRAO.paraMapa().entrySet()) {
                    ps.setString(1, valor.getValue());
                    ps.setString(2, valor.getKey());
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Cria o supervisor "admin" (senha "segredo1") pelo primeiro acesso e devolve a sessão dele. */
    Sessao supervisor() {
        acesso.criarPrimeiroSupervisor(new AcessoService.PrimeiroAcesso(
                "Mercado Bom Preço", "Ana Admin", "admin", "segredo1", "segredo1"));
        return acesso.entrar("admin", "segredo1");
    }

    /** Conta do reembolso OUTRO (F15.1): o Banco; nula nas demais formas. */
    Long contaDoReembolso(FormaReembolso forma) {
        return forma == FormaReembolso.OUTRO ? contaPorNome("Banco") : null;
    }

    long contaPorNome(String nome) {
        try {
            return contar("SELECT id FROM conta_financeira WHERE nome = '" + nome + "'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Descrições da auditoria para uma ação, na ordem de gravação. */
    List<String> auditoria(AcaoAuditoria acao) throws SQLException {
        List<String> descricoes = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT descricao FROM auditoria WHERE acao = ? ORDER BY id")) {
            ps.setString(1, acao.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    descricoes.add(rs.getString(1));
                }
            }
        }
        return descricoes;
    }

    long contar(String sql) throws SQLException {
        try (Connection con = dataSource.getConnection()) {
            return BancoTeste.contar(con, sql);
        }
    }
}
