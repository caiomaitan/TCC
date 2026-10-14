package br.com.varesync.service;

import br.com.varesync.BancoTeste;
import br.com.varesync.RelogioTeste;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.CaixaDAO;
import br.com.varesync.dao.CategoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.EstoqueDAO;
import br.com.varesync.dao.MovimentoEstoqueDAO;
import br.com.varesync.dao.ProdutoDAO;
import br.com.varesync.dao.UnidadeMedidaDAO;
import br.com.varesync.dao.UsuarioDAO;
import br.com.varesync.model.AcaoAuditoria;
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

    final RelogioTeste relogio = new RelogioTeste(Instant.parse("2026-10-06T13:00:00Z"));
    final Senhas senhas = new Senhas(CUSTO_BCRYPT_TESTE);
    final AcessoService acesso;
    final UsuarioService usuarios;
    final EstoqueService estoque;
    final CategoriaService categorias;
    final ProdutoService produtos;
    final CaixaService caixa;
    private final DataSource dataSource;

    AcessoTeste(DataSource dataSource) {
        this.dataSource = dataSource;
        Transacao transacao = new Transacao(dataSource);
        UsuarioDAO usuarioDAO = new UsuarioDAO();
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), relogio, TERMINAL);
        acesso = new AcessoService(transacao, usuarioDAO, new ConfiguracaoDAO(), auditoria, senhas, relogio, TERMINAL);
        usuarios = new UsuarioService(transacao, usuarioDAO, auditoria, senhas, relogio);
        ProdutoDAO produtoDAO = new ProdutoDAO();
        CategoriaDAO categoriaDAO = new CategoriaDAO();
        estoque = new EstoqueService(transacao, produtoDAO, new EstoqueDAO(), new MovimentoEstoqueDAO(), auditoria,
                relogio);
        categorias = new CategoriaService(transacao, categoriaDAO);
        produtos = new ProdutoService(transacao, produtoDAO, categoriaDAO, new UnidadeMedidaDAO(),
                new ConfiguracaoDAO(), estoque, auditoria, relogio);
        caixa = new CaixaService(transacao, new CaixaDAO(), auditoria, relogio);
    }

    /**
     * Volta ao estado de primeiro acesso: sem usuários nem nada que dependa deles (produtos, kardex, auditoria),
     * só as categorias da V2 (todas ativas) e a configuração padrão. Usa TRUNCATE porque o kardex é imutável:
     * o trigger recusa DELETE em {@code movimento_estoque}, mas não age no TRUNCATE.
     */
    void limpar() throws SQLException {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("TRUNCATE usuario CASCADE");
            st.executeUpdate("DELETE FROM categoria WHERE nome NOT IN (" + CATEGORIAS_V2 + ")");
            st.executeUpdate("UPDATE categoria SET ativa = TRUE WHERE NOT ativa");
            st.executeUpdate("UPDATE configuracao SET valor = 'Minha Loja' WHERE chave = 'empresa.nome'");
            st.executeUpdate("UPDATE configuracao SET valor = 'false' WHERE chave = 'balanca.ativo'");
        }
    }

    /** Cria o supervisor "admin" (senha "segredo1") pelo primeiro acesso e devolve a sessão dele. */
    Sessao supervisor() {
        acesso.criarPrimeiroSupervisor(new AcessoService.PrimeiroAcesso(
                "Mercado Bom Preço", "Ana Admin", "admin", "segredo1", "segredo1"));
        return acesso.entrar("admin", "segredo1");
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
