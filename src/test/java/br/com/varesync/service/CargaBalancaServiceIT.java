package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DadosPromocao;
import br.com.varesync.model.ItemCargaBalanca;
import br.com.varesync.model.ItemCargaBalanca.Situacao;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.Produto;
import br.com.varesync.model.TipoValorBalanca;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Carga de balança (F18.2): só pesáveis com PLU, preço promocional do dia, pendências depois de alterar preço, nome,
 * validade ou PLU, arquivo gravado e registrado com auditoria, pasta não configurada, permissão; os dias de validade
 * no cadastro do produto; e a correção do CHECK do conteúdo líquido (V17).
 */
class CargaBalancaServiceIT extends IntegracaoBase {

    @TempDir
    Path pasta;

    private AcessoTeste t;
    private CargaBalancaService carga;
    private Sessao admin;
    private long queijo;
    private long presunto;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        carga = t.cargaBalanca;
        admin = t.supervisor();
        balanca(pasta.toString());
        queijo = pesavel("Queijo Muçarela Fatiado Especial Kg", "39.90", 123, 5);
        presunto = pesavel("Presunto cozido", "29.90", 124, null);
    }

    private void balanca(String pastaCarga) {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comBalanca(new Configuracoes.Balanca(true, 2, 5, TipoValorBalanca.PRECO,
                pastaCarga)));
    }

    private long pesavel(String nome, String preco, Integer plu, Integer validade) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, "KG", v(preco), v("10.00"), null, plu, List.of(),
                v("5"), null, null, null, validade));
    }

    private void alterar(long id, String nome, String preco, Integer plu, Integer validade) {
        Produto p = t.produtos.buscar(admin, id);
        t.produtos.alterar(admin, id, new DadosProduto(nome, p.categoriaId(), p.unidade(), v(preco), p.custo(),
                p.estoqueMinimo(), plu, p.codigosBarras(), null, null, null, null, validade));
    }

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private ItemCargaBalanca item(long id) {
        return carga.itens(admin).stream().filter(i -> i.produtoId() == id).findFirst().orElseThrow();
    }

    private static List<Integer> plus(List<ItemCargaBalanca> itens) {
        return itens.stream().map(ItemCargaBalanca::plu).toList();
    }

    @Test
    void soPesaveisComPluEPrecoPromocionalDoDia() throws Exception {
        pesavel("Banana prata", "6.99", null, null); // KG sem PLU
        t.produtos.criar(admin, new DadosProduto("Arroz 5 kg", null, "UN", v("27.90"), v("20.00"), null, null,
                List.of(), v("5")));
        long inativo = pesavel("Mortadela", "19.90", 125, null);
        t.produtos.alterarSituacao(admin, inativo, false);
        pesavel("Brinde", "0.00", 126, null); // sem preço: não vai para a balança
        t.produtos.criar(admin, new DadosProduto("Granel em litro", null, "L", v("9.90"), v("5.00"), null, 127,
                List.of(), v("5")));
        LocalDate hoje = t.promocoes.hoje();
        t.promocoes.criar(admin, new DadosPromocao(queijo, v("35.90"), hoje, hoje.plusDays(2), true));

        List<ItemCargaBalanca> itens = carga.itens(admin);
        assertThat(plus(itens)).as("ativos, em KG, com PLU e preço").containsExactly(123, 124);
        ItemCargaBalanca q = itens.get(0);
        assertThat(q.preco()).isEqualByComparingTo("35.90");
        assertThat(q.emPromocao()).isTrue();
        assertThat(q.descricao()).isEqualTo("QUEIJO MUCARELA FATIADO ESPECIAL KG");
        assertThat(q.validadeDias()).isEqualTo(5);
        assertThat(q.situacao()).isEqualTo(Situacao.NUNCA_CARREGADO);
        assertThat(itens.get(1).preco()).isEqualByComparingTo("29.90");
        assertThat(itens.get(1).emPromocao()).isFalse();
        assertThat(carga.contarPendentes(admin)).isEqualTo(2);

        // A promoção termina: o preço volta e a carga fica pendente de novo.
        carga.gerar(admin);
        assertThat(carga.pendentes(admin)).isEmpty();
        t.relogio.avancar(Duration.ofDays(3));
        assertThat(item(queijo).preco()).isEqualByComparingTo("39.90");
        assertThat(item(queijo).situacao()).isEqualTo(Situacao.PRECO_ALTERADO);
        assertThat(plus(carga.pendentes(admin))).containsExactly(123);
    }

    @Test
    void gerarGravaOArquivoERegistraACarga() throws Exception {
        CargaBalancaService.Carga gerada = carga.gerar(admin);

        Path arquivo = pasta.resolve("ITENSMGV.TXT");
        assertThat(gerada.arquivo()).isEqualTo(arquivo);
        assertThat(gerada.itens()).isEqualTo(2);
        assertThat(gerada.pendentes()).isEqualTo(2);
        byte[] bytes = Files.readAllBytes(arquivo);
        String conteudo = new String(bytes, StandardCharsets.US_ASCII);
        assertThat(conteudo).isEqualTo(LayoutMgvTest.AMOSTRA + "\r\n"
                + LayoutMgv.linha("Presunto cozido", 124, v("29.90"), null) + "\r\n");
        assertThat(bytes).hasSize(2 * 152);
        try (var arquivos = Files.list(pasta)) {
            assertThat(arquivos).as("sem temporário para trás").containsExactly(arquivo);
        }

        assertThat(carga.pendentes(admin)).isEmpty();
        assertThat(carga.ultimaCarga(admin)).isNotNull();
        assertThat(item(queijo).ultimaCargaEm()).isNotNull();
        assertThat(t.contar("SELECT count(*) FROM carga_balanca WHERE itens = 2 AND arquivo = '" + arquivo + "'"))
                .isEqualTo(1);
        assertThat(t.contar("SELECT count(*) FROM carga_balanca_item WHERE plu = 123 AND preco = 39.90 "
                + "AND descricao = 'QUEIJO MUCARELA FATIADO ESPECIAL KG' AND validade_dias = 5")).isEqualTo(1);
        assertThat(t.auditoria(AcaoAuditoria.CARGA_BALANCA_GERADA))
                .containsExactly("Carga de balança gerada: 2 produtos (2 pendentes), em " + arquivo);

        // Gerar de novo substitui o arquivo; a reimpressão continua possível sem pendências.
        alterar(presunto, "Presunto cozido", "31.90", 124, null);
        carga.gerar(admin);
        assertThat(Files.readString(arquivo, StandardCharsets.US_ASCII)).contains("000124003190000PRESUNTO COZIDO");
        assertThat(t.auditoria(AcaoAuditoria.CARGA_BALANCA_GERADA)).last().asString().contains("(1 pendente)");
        assertThat(carga.gerar(admin).pendentes()).isZero();
    }

    @Test
    void pendenteDepoisDeAlterarPrecoNomeValidadeOuPlu() throws Exception {
        carga.gerar(admin);
        assertThat(carga.pendentes(admin)).isEmpty();

        alterar(queijo, "Queijo Muçarela Fatiado Especial Kg", "41.90", 123, 5);
        assertThat(item(queijo).situacao()).isEqualTo(Situacao.PRECO_ALTERADO);
        assertThat(item(presunto).situacao()).isEqualTo(Situacao.EM_DIA);
        carga.gerar(admin);

        alterar(presunto, "Presunto cozido sem capa", "29.90", 124, null);
        assertThat(item(presunto).situacao()).isEqualTo(Situacao.DESCRICAO_ALTERADA);
        carga.gerar(admin);
        // Só maiúsculas e acentos: a balança recebe o mesmo texto, não fica pendente.
        alterar(presunto, "PRESUNTO COZIDO SEM CAPA", "29.90", 124, null);
        assertThat(item(presunto).situacao()).isEqualTo(Situacao.EM_DIA);

        alterar(queijo, "Queijo Muçarela Fatiado Especial Kg", "41.90", 123, 7);
        assertThat(item(queijo).situacao()).isEqualTo(Situacao.VALIDADE_ALTERADA);
        carga.gerar(admin);
        alterar(queijo, "Queijo Muçarela Fatiado Especial Kg", "41.90", 130, 7);
        assertThat(item(queijo).situacao()).isEqualTo(Situacao.PLU_ALTERADO);
        assertThat(carga.contarPendentes(admin)).isEqualTo(1);
    }

    @Test
    void semPastaConfiguradaOuInexistenteNadaERegistrado() throws Exception {
        balanca("");
        assertThatThrownBy(() -> carga.gerar(admin)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("Configurações › Balança");
        balanca(pasta.resolve("nao-existe").toString());
        assertThatThrownBy(() -> carga.gerar(admin)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("não existe");
        assertThat(t.contar("SELECT count(*) FROM carga_balanca")).isZero();
        assertThat(carga.contarPendentes(admin)).isEqualTo(2);
    }

    @Test
    void pastaDaCargaNasConfiguracoes() throws Exception {
        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA)).last().asString()
                .contains("pasta da carga de balança de (vazio) para \"" + pasta + "\"");
        assertThatThrownBy(() -> balanca("relativa\\itens")).isInstanceOf(NegocioException.class)
                .hasMessageContaining("caminho completo");
        assertThat(carga.pasta(admin)).isEqualTo(pasta.toString());
        balanca("  " + pasta + "  ");
        assertThat(t.configuracoes.carregar(admin).balanca().pastaCarga()).isEqualTo(pasta.toString());
    }

    @Test
    void semPesaveisNaoGeraCarga() throws Exception {
        alterar(queijo, "Queijo", "39.90", null, null);
        alterar(presunto, "Presunto", "29.90", null, null);
        assertThat(carga.itens(admin)).isEmpty();
        assertThatThrownBy(() -> carga.gerar(admin)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("Não há produtos para a balança");
        assertThat(carga.registrarSemArquivo(admin)).isZero();
        assertThat(t.contar("SELECT count(*) FROM carga_balanca")).isZero();
    }

    @Test
    void registrarSemArquivoDeixaABalancaEmDia() throws Exception {
        assertThat(carga.registrarSemArquivo(admin)).isEqualTo(2);
        assertThat(carga.pendentes(admin)).isEmpty();
        assertThat(t.contar("SELECT count(*) FROM carga_balanca WHERE arquivo IS NULL")).isEqualTo(1);
        try (var arquivos = Files.list(pasta)) {
            assertThat(arquivos).isEmpty();
        }
        assertThat(t.auditoria(AcaoAuditoria.CARGA_BALANCA_GERADA)).last().asString().contains("sem arquivo");
    }

    @Test
    void precoQueNaoCabeNaBalancaNaoGeraNada() throws Exception {
        alterar(presunto, "Presunto cozido", "10000.00", 124, null);
        assertThatThrownBy(() -> carga.gerar(admin)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("\"Presunto cozido\"");
        assertThat(t.contar("SELECT count(*) FROM carga_balanca")).isZero();
        try (var arquivos = Files.list(pasta)) {
            assertThat(arquivos).isEmpty();
        }
    }

    @Test
    void exigePermissaoDeProdutos() throws Exception {
        t.usuarios.criar(admin, "caixa1", "Caixa", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao caixa = t.acesso.entrar("caixa1", "inicial1");
        assertThatThrownBy(() -> carga.itens(caixa)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> carga.gerar(caixa)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> carga.registrarSemArquivo(caixa)).isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void validadeNaBalancaNoCadastroDoProduto() throws Exception {
        Produto q = t.produtos.buscar(admin, queijo);
        assertThat(q.validadeBalancaDias()).isEqualTo(5);
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_CRIADO).get(0)).contains("validade na balança 5 dias");
        assertThat(t.produtos.buscar(admin, presunto).validadeBalancaDias()).isNull();

        // Nulo não muda (os construtores antigos de DadosProduto); 0 tira.
        t.produtos.alterar(admin, queijo, new DadosProduto(q.nome(), null, "KG", v("39.90"), q.custo(), null, 123,
                List.of(), null));
        assertThat(t.produtos.buscar(admin, queijo).validadeBalancaDias()).isEqualTo(5);
        alterar(queijo, q.nome(), "39.90", 123, 10);
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_ALTERADO)).last().asString()
                .contains("validade na balança alterada de 5 dias para 10 dias");
        alterar(queijo, q.nome(), "39.90", 123, 0);
        assertThat(t.produtos.buscar(admin, queijo).validadeBalancaDias()).isNull();
        assertThat(t.auditoria(AcaoAuditoria.PRODUTO_ALTERADO)).last().asString()
                .contains("validade na balança alterada de 10 dias para (sem)");

        // Sem PLU, a validade é recusada; tirar o PLU tira a validade guardada.
        assertThatThrownBy(() -> pesavel("Coxão mole", "45.90", null, 3)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("só valem para o produto pesável, com PLU");
        assertThatThrownBy(() -> alterar(queijo, q.nome(), "39.90", 123, 991)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("entre 1 e 990");
        alterar(queijo, q.nome(), "39.90", 123, 4);
        alterar(queijo, q.nome(), "39.90", null, null);
        assertThat(t.produtos.buscar(admin, queijo).validadeBalancaDias()).isNull();
        assertThat(t.produtos.buscar(admin, queijo).plu()).isNull();
    }

    @Test
    void bancoRecusaValidadeForaDaFaixaEConteudoPelaMetade() throws Exception {
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE produto SET validade_balanca_dias = 991 WHERE id = "
                    + queijo)).isInstanceOf(SQLException.class).hasMessageContaining("ck_produto_validade_balanca");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE produto SET conteudo_quantidade = 500, "
                    + "conteudo_unidade = NULL WHERE id = " + queijo)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_produto_conteudo");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE produto SET conteudo_quantidade = NULL, "
                    + "conteudo_unidade = 'g' WHERE id = " + queijo)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_produto_conteudo");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE produto SET conteudo_quantidade = 0, "
                    + "conteudo_unidade = 'g' WHERE id = " + queijo)).isInstanceOf(SQLException.class);
            assertThat(st.executeUpdate("UPDATE produto SET conteudo_quantidade = 500, conteudo_unidade = 'g' "
                    + "WHERE id = " + queijo)).isEqualTo(1);
            assertThat(st.executeUpdate("UPDATE produto SET conteudo_quantidade = NULL, conteudo_unidade = NULL "
                    + "WHERE id = " + queijo)).isEqualTo(1);
        }
    }
}
