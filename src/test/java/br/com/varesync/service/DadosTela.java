package br.com.varesync.service;

import br.com.varesync.config.Servicos;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Permissao;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import javax.sql.DataSource;

/**
 * Dados de partida dos testes de interface (F11.3, pacote {@code tela}), montados pelos services do
 * {@link AcessoTeste}, que é deste pacote. Só abre o banco de teste que recebe.
 */
public final class DadosTela {

    /** Terminal da configuração temporária: o PDV aberto pela tela acha o caixa aberto aqui. */
    public static final String TERMINAL = AcessoTeste.TERMINAL;
    public static final String LOGIN_SUPERVISOR = "admin";
    public static final String SENHA_SUPERVISOR = "segredo1";

    private final DataSource dataSource;
    private final AcessoTeste t;
    private Sessao supervisor;

    public DadosTela(DataSource dataSource) {
        this.dataSource = dataSource;
        this.t = new AcessoTeste(dataSource);
    }

    /** Volta ao primeiro acesso: sem usuários nem movimento. */
    public void limpar() throws SQLException {
        t.limpar();
        supervisor = null;
    }

    /** Cria o supervisor admin/segredo1 (loja "Mercado Bom Preço"). */
    public void criarSupervisor() {
        supervisor = t.supervisor();
    }

    /** Dados de demonstração de 60 dias até hoje, pelo gerador do sistema (relógio de verdade). */
    public void gerarDemonstracao() {
        Servicos.geradorDemo(dataSource, Clock.systemDefaultZone(), "CAIXA-01", new Senhas(AcessoTeste.CUSTO_BCRYPT_TESTE))
                .gerar(sessao(), (a, b, c) -> { });
    }

    /** Abre o caixa do {@link #TERMINAL} com o fundo de troco informado. */
    public long abrirCaixa(String fundo) {
        return t.caixa.abrir(sessao(), new BigDecimal(fundo)).id();
    }

    /** Produto com um código de barras principal (ou nenhum) e estoque inicial. */
    public long produto(String nome, String unidade, String preco, String custo, String inicial, String codigo) {
        List<CodigoBarras> codigos = codigo == null ? List.of() : List.of(new CodigoBarras(codigo, true));
        return t.produtos.criar(sessao(), new DadosProduto(nome, null, unidade, new BigDecimal(preco),
                new BigDecimal(custo), null, null, codigos, inicial == null ? null : new BigDecimal(inicial)));
    }

    /** Usuário comum, já com a senha definitiva (sem a troca obrigatória). */
    public void usuario(String login, String senha, Permissao... permissoes) throws SQLException {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(sessao(), login, "Usuário " + login, false, conjunto, senha, senha);
        executar("UPDATE usuario SET deve_trocar_senha = FALSE WHERE login = ?", login);
    }

    /** Tema e fonte do usuário (F21.1), gravados como a tela "Minhas preferências" grava. */
    public void preferencias(String login, String tema, int fonte) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement("""
                INSERT INTO usuario_preferencia (usuario_id, tema, fonte, atualizado_em)
                SELECT id, ?, ?, now() FROM usuario WHERE login = ?
                ON CONFLICT (usuario_id) DO UPDATE SET tema = EXCLUDED.tema, fonte = EXCLUDED.fonte,
                    atualizado_em = EXCLUDED.atualizado_em
                """)) {
            ps.setString(1, tema);
            ps.setInt(2, fonte);
            ps.setString(3, login);
            ps.executeUpdate();
        }
    }

    /** NCM, origem e o perfil tributário (pelo nome) do produto, pelo cadastro fiscal (F19.2). */
    public void dadosFiscais(long produtoId, String ncm, int origem, String perfil) {
        long perfilId = t.fiscal.perfis(sessao(), true).stream().filter(p -> p.nome().equals(perfil)).findFirst()
                .orElseThrow().id();
        t.fiscal.salvarDadosFiscais(sessao(), produtoId, new br.com.varesync.model.DadosFiscais(ncm, null, origem,
                perfilId));
    }

    /** Perfil do Simples (PIS e COFINS 49), pelo cadastro fiscal (F19.2). */
    public void perfilSimples(String nome, String cfop, String csosn) {
        t.fiscal.salvarPerfil(sessao(), null, new br.com.varesync.model.DadosPerfil(nome, cfop, true, csosn, null, null,
                "49", null, "49", null, null, null, null, true));
    }

    /** Tabela IBPT pelo cadastro fiscal (F22.3). */
    public void importarIbpt(byte[] arquivo, String nome) {
        t.fiscal.importarIbpt(sessao(), arquivo, nome);
    }

    /** Código de barras de embalagem (F20.1) num produto já cadastrado, como o cadastro grava. */
    public void embalagem(long produtoId, String codigo, int quantidade) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(
                "INSERT INTO produto_codigo_barras (produto_id, codigo, principal, quantidade) VALUES (?, ?, FALSE, ?)")) {
            ps.setLong(1, produtoId);
            ps.setString(2, codigo);
            ps.setInt(3, quantidade);
            ps.executeUpdate();
        }
    }

    /**
     * NFC-e, parte A (F23.3): reserva o número da venda e monta, assina e valida o XML com o certificado que a tela
     * gravou no {@code config.properties} temporário (o mesmo caminho do sistema). Nada é transmitido.
     */
    public br.com.varesync.model.NfceGravada nfceDaVenda(long vendaId) {
        java.time.ZoneId fuso = java.time.ZoneId.systemDefault();
        Clock relogio = Clock.systemDefaultZone();
        Transacao transacao = new Transacao(dataSource);
        CertificadoService certificados = new CertificadoService(transacao, new br.com.varesync.dao.ConfiguracaoDAO(),
                new AuditoriaService(new br.com.varesync.dao.AuditoriaDAO(), relogio, TERMINAL),
                new br.com.varesync.config.CertificadoNoArquivo(br.com.varesync.config.AppConfig.arquivoPadrao()),
                relogio);
        br.com.varesync.service.nfce.ValidacaoXsd validacao = new br.com.varesync.service.nfce.ValidacaoXsd(
                br.com.varesync.config.AppConfig.pastaPadrao().resolve("schemas"));
        NfceService nfce = new NfceService(transacao, new br.com.varesync.dao.NfceDAO(),
                new br.com.varesync.dao.NumeracaoNfceDAO(), new br.com.varesync.dao.TributoVendaDAO(),
                new br.com.varesync.dao.ConfiguracaoDAO(), new br.com.varesync.service.nfce.GeracaoNfce(
                        new br.com.varesync.service.nfce.MontagemNfce(fuso),
                        new br.com.varesync.service.nfce.AssinaturaNfce(validacao.pasta(), fuso), validacao),
                certificados, "2.0.0-SNAPSHOT", new java.security.SecureRandom(), relogio, fuso);
        long id = transacao.executar(con -> nfce.reservar(con, vendaId,
                br.com.varesync.model.TipoEmissaoNfce.NORMAL, java.time.OffsetDateTime.now(relogio), null));
        return nfce.prepararXml(id);
    }

    public void configurar(String chave, String valor) throws SQLException {
        executar("UPDATE configuracao SET valor = ? WHERE chave = ?", valor, chave);
    }

    private void executar(String sql, String... parametros) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setString(i + 1, parametros[i]);
            }
            ps.executeUpdate();
        }
    }

    private Sessao sessao() {
        if (supervisor == null) {
            throw new IllegalStateException("Crie o supervisor antes (criarSupervisor)");
        }
        return supervisor;
    }
}
