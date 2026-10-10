package br.com.varesync.service;

import br.com.varesync.BancoTeste;
import br.com.varesync.RelogioTeste;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
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

    final RelogioTeste relogio = new RelogioTeste(Instant.parse("2026-10-06T13:00:00Z"));
    final Senhas senhas = new Senhas(CUSTO_BCRYPT_TESTE);
    final AcessoService acesso;
    final UsuarioService usuarios;
    private final DataSource dataSource;

    AcessoTeste(DataSource dataSource) {
        this.dataSource = dataSource;
        Transacao transacao = new Transacao(dataSource);
        UsuarioDAO usuarioDAO = new UsuarioDAO();
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), relogio, TERMINAL);
        acesso = new AcessoService(transacao, usuarioDAO, new ConfiguracaoDAO(), auditoria, senhas, relogio, TERMINAL);
        usuarios = new UsuarioService(transacao, usuarioDAO, auditoria, senhas);
    }

    /** Tabelas de acesso vazias (estado de primeiro acesso) e nome da loja padrão da V2. */
    void limpar() throws SQLException {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM auditoria");
            st.executeUpdate("DELETE FROM usuario_permissao");
            st.executeUpdate("DELETE FROM usuario");
            st.executeUpdate("UPDATE configuracao SET valor = 'Minha Loja' WHERE chave = 'empresa.nome'");
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
