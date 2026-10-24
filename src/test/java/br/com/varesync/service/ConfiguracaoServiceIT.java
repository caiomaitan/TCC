package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.ParametrosVenda;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelSemGiro;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Configurações (6.13): só supervisor, validação, gravação só do que mudou com auditoria, os services passam a usar o
 * valor novo (classe tipada {@link Configuracoes}) e o cadastro de formas de pagamento.
 */
class ConfiguracaoServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private ConfiguracaoService configuracoes;
    private Sessao admin;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        configuracoes = t.configuracoes;
        admin = t.supervisor();
    }

    private Sessao operador(Permissao... permissoes) {
        EnumSet<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        t.usuarios.criar(admin, "gerente", "Gerente", false, conjunto, "inicial1", "inicial1");
        return t.acesso.entrar("gerente", "inicial1");
    }

    @Test
    void carregaOsValoresDoBancoTipados() {
        Configuracoes c = configuracoes.carregar(admin);
        assertThat(c.loja().nome()).isEqualTo("Mercado Bom Preço"); // gravado pelo primeiro acesso
        assertThat(c.venda()).isEqualTo(ParametrosVenda.PADRAO);
        assertThat(c.margemMinimaPct()).isEqualByComparingTo("15");
        assertThat(c.diasSemGiro()).isEqualTo(30);
        assertThat(c.balanca()).isEqualTo(new Configuracoes.Balanca(false, 2, 5, TipoValorBalanca.PRECO));
    }

    @Test
    void soSupervisorAbreEAltera() {
        Sessao gerente = operador(Permissao.values());
        assertThatThrownBy(() -> configuracoes.carregar(gerente)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> configuracoes.salvar(gerente, Configuracoes.PADRAO))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> configuracoes.formasPagamento(gerente)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> configuracoes.criarForma(gerente, "Fiado", TipoFormaPagamento.OUTROS))
                .isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void salvaSoOQueMudouComAuditoriaEOsServicesUsamOValorNovo() throws Exception {
        Configuracoes atual = configuracoes.carregar(admin);
        Configuracoes novas = atual
                .comLoja(new Configuracoes.Loja("  Mercado  Bom Preço Ltda ", "11.222.333/0001-81",
                        "Rua das Flores, 100", "(16) 3333-4444"))
                .comVenda(new ParametrosVenda(false, new BigDecimal("10"), true))
                .comRelatorios(new BigDecimal("20"), 45)
                .comBalanca(new Configuracoes.Balanca(true, 2, 4, TipoValorBalanca.PESO))
                .comRodape("Volte sempre!");
        List<String> mudancas = configuracoes.salvar(admin, novas);
        assertThat(mudancas).hasSize(13);

        Configuracoes gravadas = configuracoes.carregar(admin);
        assertThat(gravadas.loja()).isEqualTo(new Configuracoes.Loja("Mercado Bom Preço Ltda", "11222333000181",
                "Rua das Flores, 100", "1633334444"));
        assertThat(gravadas.venda().permitirEstoqueNegativo()).isFalse();
        assertThat(gravadas.venda().descontoMaximoPct()).isEqualByComparingTo("10");
        assertThat(gravadas.margemMinimaPct()).isEqualByComparingTo("20");
        assertThat(gravadas.diasSemGiro()).isEqualTo(45);
        assertThat(gravadas.balanca()).isEqualTo(new Configuracoes.Balanca(true, 2, 4, TipoValorBalanca.PESO));
        assertThat(gravadas.rodapeComprovante()).isEqualTo("Volte sempre!");
        assertThat(gravadas.backup()).isEqualTo(atual.backup());

        List<String> auditoria = t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA);
        assertThat(auditoria).hasSize(1);
        assertThat(auditoria.get(0)).contains("desconto máximo do operador de 5,00% para 10,00%")
                .contains("venda sem estoque de \"permite e avisa\" para \"bloqueia\"")
                .contains("CNPJ/CPF da loja de (vazio) para 11.222.333/0001-81");

        // Os services leem a classe tipada a cada operação: o valor novo vale na hora.
        assertThat(t.vendas.parametros(admin)).isEqualTo(new ParametrosVenda(false, new BigDecimal("10"), true));
        assertThat(t.produtos.balancaAtiva(admin)).isTrue();
        assertThat(t.relatorios.diasSemGiroPadrao(admin)).isEqualTo(45);
        assertThat(t.relatorios.gerar(admin, new RelSemGiro(), Filtros.vazios()).loja())
                .isEqualTo("Mercado Bom Preço Ltda");
        assertThat(t.acesso.nomeEmpresa()).isEqualTo("Mercado Bom Preço Ltda");

        // Salvar de novo sem mudar nada não grava nem audita.
        assertThat(configuracoes.salvar(admin, gravadas)).isEmpty();
        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA)).hasSize(1);
    }

    @Test
    void valoresInvalidosSaoRecusadosComMensagem() {
        Configuracoes c = configuracoes.carregar(admin);
        assertThatThrownBy(() -> configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja(" ", "", "", ""))))
                .hasMessage("Informe o nome da loja.");
        assertThatThrownBy(() -> configuracoes.salvar(admin,
                c.comLoja(new Configuracoes.Loja("Loja", "11222333000180", "", ""))))
                .isInstanceOf(NegocioException.class).hasMessageContaining("CNPJ");
        assertThatThrownBy(() -> configuracoes.salvar(admin,
                c.comLoja(new Configuracoes.Loja("Loja", "", "", "3333"))))
                .hasMessageContaining("Telefone inválido");
        assertThatThrownBy(() -> configuracoes.salvar(admin,
                c.comVenda(new ParametrosVenda(true, new BigDecimal("101"), false))))
                .hasMessageContaining("entre 0 e 100");
        assertThatThrownBy(() -> configuracoes.salvar(admin,
                c.comVenda(new ParametrosVenda(true, new BigDecimal("5.555"), false))))
                .hasMessageContaining("casas decimais");
        assertThatThrownBy(() -> configuracoes.salvar(admin, c.comRelatorios(new BigDecimal("100"), 30)))
                .hasMessageContaining("A margem mínima");
        assertThatThrownBy(() -> configuracoes.salvar(admin, c.comRelatorios(new BigDecimal("15"), 0)))
                .hasMessageContaining("dias sem giro");
        assertThatThrownBy(() -> configuracoes.salvar(admin,
                c.comBalanca(new Configuracoes.Balanca(true, 2, 7, TipoValorBalanca.PRECO))))
                .hasMessageContaining("4, 5 ou 6");
        assertThatThrownBy(() -> configuracoes.salvar(admin,
                c.comBalanca(new Configuracoes.Balanca(true, 10, 5, TipoValorBalanca.PRECO))))
                .hasMessageContaining("prefixo");
        assertThat(t.vendas.parametros(admin)).isEqualTo(ParametrosVenda.PADRAO); // nada gravado
    }

    @Test
    void camposDeBackupValidadosEGravados(@org.junit.jupiter.api.io.TempDir java.nio.file.Path pasta)
            throws Exception {
        Configuracoes c = configuracoes.carregar(admin);
        java.util.function.Function<Configuracoes.Backup, Configuracoes> com = b -> new Configuracoes(c.loja(),
                c.venda(), c.margemMinimaPct(), c.diasSemGiro(), c.balanca(), b, c.rodapeComprovante(),
                c.inatividadeMinutos());
        assertThatThrownBy(() -> configuracoes.salvar(admin, com.apply(new Configuracoes.Backup("backups", "", 15))))
                .hasMessageContaining("caminho completo");
        assertThatThrownBy(() -> configuracoes.salvar(admin, com.apply(new Configuracoes.Backup("",
                pasta.resolve("nao-existe.exe").toString(), 15)))).hasMessageContaining("pg_dump informado não existe");
        assertThatThrownBy(() -> configuracoes.salvar(admin, com.apply(new Configuracoes.Backup("", "", 0))))
                .hasMessageContaining("entre 1 e 999");
        java.nio.file.Path arquivo = java.nio.file.Files.writeString(pasta.resolve("um-arquivo.txt"), "x");
        assertThatThrownBy(() -> configuracoes.salvar(admin, com.apply(new Configuracoes.Backup(arquivo.toString(),
                "", 15)))).hasMessageContaining("é um arquivo");

        List<String> mudancas = configuracoes.salvar(admin, com.apply(new Configuracoes.Backup(" " + pasta + " ", "",
                7)));
        assertThat(mudancas).containsExactly("pasta de backup de (padrão) para \"" + pasta + "\"",
                "backups mantidos de 15 para 7");
        assertThat(configuracoes.carregar(admin).backup()).isEqualTo(new Configuracoes.Backup(pasta.toString(), "", 7));
    }

    @Test
    void menosDigitosDeCodigoNaoDeixaPluCadastradoForaDaEtiqueta() {
        Configuracoes c = configuracoes.carregar(admin);
        configuracoes.salvar(admin, c.comBalanca(new Configuracoes.Balanca(true, 2, 5, TipoValorBalanca.PRECO)));
        long id = t.produtos.criar(admin, new DadosProduto("Queijo prato", null, "KG", new BigDecimal("49.90"),
                new BigDecimal("30"), null, 12345, List.of(), null));
        assertThatThrownBy(() -> configuracoes.salvar(admin,
                c.comBalanca(new Configuracoes.Balanca(true, 2, 4, TipoValorBalanca.PRECO))))
                .hasMessageContaining("o PLU vai até 9999")
                .hasMessageContaining("\"Queijo prato\" (código " + id + ")");

        // Com 4 dígitos, o cadastro também recusa PLU de 5 dígitos.
        t.produtos.alterar(admin, id, new DadosProduto("Queijo prato", null, "KG", new BigDecimal("49.90"),
                new BigDecimal("30"), null, 123, List.of(), null));
        configuracoes.salvar(admin, c.comBalanca(new Configuracoes.Balanca(true, 2, 4, TipoValorBalanca.PRECO)));
        assertThatThrownBy(() -> t.produtos.criar(admin, new DadosProduto("Presunto", null, "KG",
                new BigDecimal("39.90"), new BigDecimal("20"), null, 23456, List.of(), null)))
                .hasMessageContaining("entre 1 e 9999");
    }

    @Test
    void formasDePagamentoCriarDesativarEAoMenosUmaAtiva() throws Exception {
        assertThat(configuracoes.formasPagamento(admin)).extracting(FormaPagamentoCadastro::descricao)
                .containsExactly("Dinheiro", "PIX", "Cartão de débito", "Cartão de crédito", "Vale-alimentação");

        int fiado = configuracoes.criarForma(admin, "  Vale  presente ", TipoFormaPagamento.OUTROS);
        assertThat(configuracoes.formasPagamento(admin)).last()
                .isEqualTo(new FormaPagamentoCadastro(fiado, "Vale presente", TipoFormaPagamento.OUTROS, true));
        assertThatThrownBy(() -> configuracoes.criarForma(admin, "pix", TipoFormaPagamento.PIX))
                .hasMessage("Já existe a forma de pagamento \"PIX\".");
        assertThatThrownBy(() -> configuracoes.criarForma(admin, "", TipoFormaPagamento.PIX))
                .hasMessageContaining("descrição");

        // Desativada sai do PDV.
        int pix = configuracoes.formasPagamento(admin).get(1).id();
        configuracoes.alterarSituacaoForma(admin, pix, false);
        assertThat(t.vendas.formasAtivas(admin)).extracting(f -> f.descricao()).doesNotContain("PIX")
                .contains("Vale presente");
        assertThatThrownBy(() -> configuracoes.criarForma(admin, "PIX", TipoFormaPagamento.PIX))
                .hasMessageContaining("desativada. Ative-a");

        for (FormaPagamentoCadastro f : configuracoes.formasPagamento(admin)) {
            if (f.ativa() && !f.descricao().equals("Dinheiro")) {
                configuracoes.alterarSituacaoForma(admin, f.id(), false);
            }
        }
        int dinheiro = configuracoes.formasPagamento(admin).get(0).id();
        assertThatThrownBy(() -> configuracoes.alterarSituacaoForma(admin, dinheiro, false))
                .hasMessageContaining("única forma de pagamento ativa");
        configuracoes.alterarSituacaoForma(admin, pix, true);
        configuracoes.alterarSituacaoForma(admin, dinheiro, false);
        assertThat(t.vendas.formasAtivas(admin)).extracting(f -> f.descricao()).containsExactly("PIX");

        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA))
                .contains("Forma de pagamento \"Vale presente\" (Outros) criada",
                        "Forma de pagamento \"PIX\" desativada", "Forma de pagamento \"PIX\" ativada");
    }
}
