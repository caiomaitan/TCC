package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.AlvoPerfil;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosPerfil;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.OrigemMercadoria;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.ProdutoFiscal;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.SugestaoFiscal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cadastro fiscal (F19.1 a F19.3): perfis tributários (só o formato), dados fiscais do produto, aplicação do perfil em
 * lote (seleção, categoria inteira e NCM), tabelas NCM (CSV e JSON oficial) e CEST com a existência passando a ser
 * conferida, preenchimento pela NF-e só nos campos vazios, auditoria e permissão FISCAL.
 */
class FiscalServiceIT extends IntegracaoBase {

    private AcessoTeste t;
    private FiscalService fiscal;
    private Sessao admin;
    private long bebidas;
    private long cerveja;
    private long refrigerante;
    private long refriInativo;
    private long cafe;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        fiscal = t.fiscal;
        admin = t.supervisor();
        bebidas = t.categorias.criar(admin, "Bebidas geladas");
        cerveja = produto("Cerveja lata 350 ml", bebidas, null);
        refrigerante = produto("Refrigerante lata 350 ml", bebidas, "7896004000015");
        refriInativo = produto("Refrigerante antigo", bebidas, null);
        t.produtos.alterarSituacao(admin, refriInativo, false);
        cafe = produto("Café 500 g", null, "7891000315507");
    }

    private long produto(String nome, Long categoria, String ean) {
        return t.produtos.criar(admin, new DadosProduto(nome, categoria, "UN", new BigDecimal("5.00"),
                new BigDecimal("3.00"), null, null, ean == null ? List.of() : List.of(new CodigoBarras(ean, true)),
                null));
    }

    static DadosPerfil simples(String nome, String csosn) {
        return new DadosPerfil(nome, "5102", true, csosn, null, null, "49", null, "49", null, null, null, null, true);
    }

    private ProdutoFiscal dados(long id) {
        return fiscal.produtos(admin, String.valueOf(id), null, null, false, true).stream()
                .filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    private PerfilTributario perfil(long id) {
        return fiscal.perfis(admin, false).stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    // ----- Perfis -----

    @Test
    void perfilGravaNormalizadoEAuditado() throws Exception {
        long id = fiscal.salvarPerfil(admin, null, new DadosPerfil("  Tributado   18% ", "5.102", false, "00",
                new BigDecimal("18"), null, "01", new BigDecimal("1.65"), "01", new BigDecimal("7.6"), "sp010360",
                "000", "000001", true));
        PerfilTributario p = perfil(id);
        assertThat(p.nome()).isEqualTo("Tributado 18%");
        assertThat(p.cfop()).isEqualTo("5102");
        assertThat(p.cstIcms()).isEqualTo("00");
        assertThat(p.csosn()).isNull();
        assertThat(p.aliquotaIcms()).isEqualByComparingTo("18");
        assertThat(p.reducaoBaseIcms()).isNull();
        assertThat(p.aliquotaCofins()).isEqualByComparingTo("7.60");
        assertThat(p.codigoBeneficio()).isEqualTo("SP010360");
        assertThat(p.cstIbsCbs()).isEqualTo("000");
        assertThat(p.cclassTrib()).isEqualTo("000001");
        assertThat(t.auditoria(AcaoAuditoria.PERFIL_TRIBUTARIO_ALTERADO)).singleElement().asString()
                .startsWith("Perfil tributário \"Tributado 18%\" criado: CFOP 5102 · CST 00 · ICMS 18,00%");

        // A tela só confere o formato: CST 00 com cBenef passa (é aviso do R30, F19.4).
        fiscal.salvarPerfil(admin, id, new DadosPerfil("Tributado 18%", "5102", false, "20", new BigDecimal("18"),
                new BigDecimal("33.33"), "01", new BigDecimal("1.65"), "01", new BigDecimal("7.6"), "", null, null,
                false));
        p = perfil(id);
        assertThat(p.cstIcms()).isEqualTo("20");
        assertThat(p.reducaoBaseIcms()).isEqualByComparingTo("33.33");
        assertThat(p.codigoBeneficio()).isNull();
        assertThat(p.ativo()).isFalse();
        assertThat(t.auditoria(AcaoAuditoria.PERFIL_TRIBUTARIO_ALTERADO).get(1))
                .contains("situação do ICMS: CST 00 → CST 20", "redução da base: (vazio) → 33,33%",
                        "código de benefício: SP010360 → (vazio)", "cClassTrib: 000001 → (vazio)", "inativado");
    }

    @Test
    void perfilRecusaFormatoInvalidoENomeRepetido() {
        assertThatThrownBy(() -> fiscal.salvarPerfil(admin, null, new DadosPerfil("X", "51", true, "102", null, null,
                "49", null, "49", null, null, null, null, true))).hasMessageContaining("CFOP tem 4 dígitos");
        assertThatThrownBy(() -> fiscal.salvarPerfil(admin, null, simples("X", "10")))
                .hasMessageContaining("CSOSN tem 3 dígitos");
        assertThatThrownBy(() -> fiscal.salvarPerfil(admin, null, new DadosPerfil("X", "5102", true, "102", null, null,
                "49", null, "49", null, "SP12", null, null, true))).hasMessageContaining("código de benefício");
        assertThatThrownBy(() -> fiscal.salvarPerfil(admin, null, new DadosPerfil("X", "5102", true, "102", null, null,
                "49", null, "49", null, null, "1", null, true))).hasMessageContaining("CST do IBS e da CBS");
        fiscal.salvarPerfil(admin, null, simples("Simples 102", "102"));
        assertThatThrownBy(() -> fiscal.salvarPerfil(admin, null, simples("SIMPLES 102", "102")))
                .hasMessageContaining("Já existe o perfil tributário \"Simples 102\"");
        // Combinação errada (CSOSN com o regime NORMAL) não é recusada aqui: aparece no R30.
        Configuracoes c = t.configuracoes.carregar(admin);
        Configuracoes.Loja l = c.loja();
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja(l.nome(), l.documento(), l.endereco(),
                l.telefone(), RegimeTributario.NORMAL, "SP", "")));
        assertThat(fiscal.salvarPerfil(admin, null, simples("Simples 500", "500"))).isPositive();
    }

    @Test
    void perfilEmUsoNaoExcluiEInativoNaoEAplicado() {
        long id = fiscal.salvarPerfil(admin, null, simples("Simples 102", "102"));
        fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("0901.21.00", null, 0, id));
        assertThatThrownBy(() -> fiscal.excluirPerfil(admin, id)).isInstanceOf(RegistroEmUsoException.class)
                .hasMessageContaining("está em 1 produto");
        fiscal.salvarPerfil(admin, id, new DadosPerfil("Simples 102", "5102", true, "102", null, null, "49", null,
                "49", null, null, null, null, false));
        assertThatThrownBy(() -> fiscal.aplicarPerfil(admin, id, AlvoPerfil.categoria(bebidas)))
                .hasMessageContaining("está inativo");
        // O produto que já tinha o perfil inativo pode ser salvo com ele; outro produto, não.
        fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("0901.21.00", null, 1, id));
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(admin, cerveja, new DadosFiscais(null, null, null, id)))
                .hasMessageContaining("está inativo");
        long livre = fiscal.salvarPerfil(admin, null, simples("Sem uso", "102"));
        fiscal.excluirPerfil(admin, livre);
        assertThat(fiscal.perfis(admin, false)).extracting(PerfilTributario::nome).containsExactly("Simples 102");
    }

    // ----- Dados fiscais do produto -----

    @Test
    void dadosFiscaisDoProdutoComAuditoria() throws Exception {
        long id = fiscal.salvarPerfil(admin, null, simples("Simples 500 ST", "500"));
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais("2202.10.00", "03.007.00", 0, id));
        ProdutoFiscal p = dados(refrigerante);
        assertThat(p.ncm()).isEqualTo("22021000");
        assertThat(p.cest()).isEqualTo("0300700");
        assertThat(p.origem()).isZero();
        assertThat(p.perfilNome()).isEqualTo("Simples 500 ST");
        assertThat(t.auditoria(AcaoAuditoria.DADOS_FISCAIS_ALTERADOS)).singleElement().asString()
                .contains("\"Refrigerante lata 350 ml\"", "NCM: (vazio) → 2202.10.00", "CEST: (vazio) → 03.007.00",
                        "origem: (vazio) → 0 — Nacional", "perfil tributário: (vazio) → Simples 500 ST");
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais("2202", null, 0, id)))
                .hasMessageContaining("8 dígitos");
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais(null, "123", 0, id)))
                .hasMessageContaining("7 dígitos");
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais(null, null, 9, id)))
                .hasMessageContaining("0 a 8");
        // Sem mudança, sem auditoria nova.
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais("22021000", "0300700", 0, id));
        assertThat(t.auditoria(AcaoAuditoria.DADOS_FISCAIS_ALTERADOS)).hasSize(1);
    }

    @Test
    void listaComFiltrosEPendentes() {
        long id = fiscal.salvarPerfil(admin, null, simples("Simples 102", "102"));
        fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("09012100", null, 0, id));
        assertThat(fiscal.produtos(admin, "", null, null, true, false)).as("ativos sem NCM ou sem perfil")
                .extracting(ProdutoFiscal::id).containsExactlyInAnyOrder(cerveja, refrigerante);
        assertThat(fiscal.produtos(admin, "", null, id, false, false)).extracting(ProdutoFiscal::id)
                .containsExactly(cafe);
        assertThat(fiscal.produtos(admin, "refri", null, null, false, true)).extracting(ProdutoFiscal::id)
                .containsExactly(refriInativo, refrigerante);
        assertThat(fiscal.produtos(admin, "7896004000015", null, null, false, false)).extracting(ProdutoFiscal::id)
                .containsExactly(refrigerante);
    }

    // ----- Aplicação em lote -----

    /** Aceite: aplicar um perfil a uma categoria inteira funciona (ativos e inativos; fora da categoria nada muda). */
    @Test
    void aplicarPerfilACategoriaInteira() throws Exception {
        long st = fiscal.salvarPerfil(admin, null, simples("Simples 500 ST", "500"));
        long outro = fiscal.salvarPerfil(admin, null, simples("Simples 102", "102"));
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais(null, null, null, outro));

        FiscalService.Previa previa = fiscal.previa(admin, st, AlvoPerfil.categoria(bebidas));
        assertThat(previa.produtos()).isEqualTo(3);
        assertThat(previa.mudam()).isEqualTo(3);
        assertThat(fiscal.aplicarPerfil(admin, st, AlvoPerfil.categoria(bebidas))).isEqualTo(3);
        assertThat(List.of(dados(cerveja), dados(refrigerante), dados(refriInativo)))
                .extracting(ProdutoFiscal::perfilId).containsOnly(st);
        assertThat(dados(cafe).perfilId()).as("fora da categoria").isNull();
        assertThat(perfil(st).produtos()).isEqualTo(3);
        assertThat(t.auditoria(AcaoAuditoria.PERFIL_TRIBUTARIO_APLICADO)).singleElement().asString()
                .isEqualTo("Perfil \"Simples 500 ST\" aplicado a 3 produtos (categoria \"Bebidas geladas\"): Cerveja "
                        + "lata 350 ml, Refrigerante antigo, Refrigerante lata 350 ml");

        // De novo: ninguém muda, nada é auditado.
        assertThat(fiscal.previa(admin, st, AlvoPerfil.categoria(bebidas)).mudam()).isZero();
        assertThat(fiscal.aplicarPerfil(admin, st, AlvoPerfil.categoria(bebidas))).isZero();
        assertThat(t.auditoria(AcaoAuditoria.PERFIL_TRIBUTARIO_APLICADO)).hasSize(1);
        long vazia = t.categorias.criar(admin, "Vazia");
        assertThatThrownBy(() -> fiscal.aplicarPerfil(admin, st, AlvoPerfil.categoria(vazia)))
                .hasMessage("Nenhum produto na categoria \"Vazia\".");
    }

    @Test
    void aplicarPerfilASelecaoEAoNcm() {
        long id = fiscal.salvarPerfil(admin, null, simples("Simples 102", "102"));
        assertThat(fiscal.aplicarPerfil(admin, id, AlvoPerfil.selecao(List.of(cafe, cerveja, cafe)))).isEqualTo(2);
        assertThat(dados(refrigerante).perfilId()).isNull();
        assertThatThrownBy(() -> fiscal.aplicarPerfil(admin, id, AlvoPerfil.selecao(List.of())))
                .hasMessageContaining("Selecione os produtos");

        long st = fiscal.salvarPerfil(admin, null, simples("Simples 500", "500"));
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais("22021000", null, null, null));
        fiscal.salvarDadosFiscais(admin, cerveja, new DadosFiscais("22030000", null, null, id));
        fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("09012100", null, null, id));
        assertThat(fiscal.aplicarPerfil(admin, st, AlvoPerfil.ncm("2202.10.00"))).isEqualTo(1);
        assertThat(dados(refrigerante).perfilId()).isEqualTo(st);
        assertThat(fiscal.aplicarPerfil(admin, st, AlvoPerfil.ncm("22"))).as("começo do NCM").isEqualTo(1);
        assertThat(dados(cerveja).perfilId()).isEqualTo(st);
        assertThat(dados(cafe).perfilId()).isEqualTo(id);
        assertThatThrownBy(() -> fiscal.aplicarPerfil(admin, st, AlvoPerfil.ncm("2")))
                .hasMessageContaining("de 2 a 8 dígitos");
        assertThatThrownBy(() -> fiscal.aplicarPerfil(admin, st, AlvoPerfil.ncm("8471")))
                .hasMessageContaining("Nenhum produto com o NCM começando por 8471");
    }

    // ----- Tabelas NCM e CEST -----

    /** Aceite: a tabela NCM é importada (do CSV e do JSON oficial), e a existência do código passa a ser conferida. */
    @Test
    void tabelaNcmImportadaPassaAConferirAExistencia() throws Exception {
        // Sem tabela, só o formato.
        fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("9999.99.99", null, null, null));
        assertThat(fiscal.tabelas(admin).ncm()).isZero();

        FiscalService.Importacao csv = fiscal.importarNcm(admin, """
                codigo;descricao
                2203.00.00;Cervejas de malte.
                2202.10.00;Águas com açúcar
                """.getBytes(StandardCharsets.UTF_8), "ncm.csv");
        assertThat(csv.linhas()).isEqualTo(2);
        assertThat(fiscal.tabelas(admin).ncm()).isEqualTo(2);
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("0901.21.00", null, 0, null)))
                .hasMessage("O NCM 0901.21.00 não existe na tabela NCM importada. Confira o código ou importe a "
                        + "tabela atualizada.");
        fiscal.salvarDadosFiscais(admin, cerveja, new DadosFiscais("2203.00.00", null, 0, null));
        assertThat(fiscal.descricaoNcm(admin, "22030000")).isEqualTo("Cervejas de malte.");

        // O JSON oficial troca a tabela inteira.
        FiscalService.Importacao json = fiscal.importarNcm(admin, TabelaFiscalTest.JSON_SISCOMEX
                .getBytes(StandardCharsets.UTF_8), "Tabela_NCM_Vigente.json");
        assertThat(json.formato()).isEqualTo("JSON");
        assertThat(json.linhas()).isEqualTo(4);
        assertThat(json.pulados()).isEqualTo(3);
        assertThat(fiscal.tabelas(admin).ncm()).isEqualTo(4);
        fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("0901.21.00", null, 0, null));
        assertThat(dados(cafe).ncm()).isEqualTo("09012100");
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(admin, refrigerante,
                new DadosFiscais("2202.99.00", null, 0, null))).hasMessageContaining("não existe na tabela NCM");
        assertThat(t.auditoria(AcaoAuditoria.TABELA_FISCAL_IMPORTADA)).containsExactly(
                "Tabela NCM importada (CSV, ncm.csv): 2 códigos de 8 dígitos",
                "Tabela NCM importada (JSON, Tabela_NCM_Vigente.json): 4 códigos de 8 dígitos, 3 níveis intermediários "
                        + "pulados");

        // Arquivo com erro não muda a tabela.
        assertThatThrownBy(() -> fiscal.importarNcm(admin, "2203.00.00;Cervejas\n22O3.00.00;letra O\n"
                .getBytes(StandardCharsets.UTF_8), "e.csv"))
                .hasMessageContaining("Nada foi importado");
        assertThat(fiscal.tabelas(admin).ncm()).isEqualTo(4);
    }

    @Test
    void tabelaCestImportadaPassaAConferirAExistencia() throws Exception {
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais(null, "99.999.99", null, null));
        FiscalService.Importacao r = fiscal.importarCest(admin, """
                cest;ncm;descricao
                03.007.00;2202.10.00;Refrigerante
                03.001.00;2201.10.00, 2201.90.00;Água mineral
                """.getBytes(StandardCharsets.UTF_8), "cest.csv");
        assertThat(r.linhas()).as("CEST distintos").isEqualTo(2);
        assertThat(fiscal.tabelas(admin).cest()).isEqualTo(2);
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(admin, refrigerante,
                new DadosFiscais(null, "99.999.98", null, null))).hasMessageContaining("não existe na tabela CEST");
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais(null, "03.007.00", null, null));
        assertThat(t.auditoria(AcaoAuditoria.TABELA_FISCAL_IMPORTADA)).containsExactly(
                "Tabela CEST importada (cest.csv): 2 CEST em 3 pares CEST × NCM");
    }

    // ----- NF-e -----

    /** Aceite: o XML preenche NCM, CEST e origem vazios (o que já está no produto não muda). */
    @Test
    void xmlPreencheNcmCestEOrigemVazios() throws Exception {
        long arroz = produto("Arroz 5 kg", null, "7891234567895");
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais("2202.10.00", null, null, null));
        byte[] xml = LeitorNfeTest.recurso("nfe-fiscal.xml");
        long entrada = t.nfe.gerarEntrada(admin, xml, Map.of());
        assertThat(entrada).isPositive();

        List<SugestaoFiscal> sugestoes = fiscal.sugestoesDaNota(admin, xml, Map.of());
        assertThat(sugestoes).containsExactly(
                new SugestaoFiscal(cafe, "Café 500 g", "1, 4", "09012100", "1709600", 0),
                new SugestaoFiscal(refrigerante, "Refrigerante lata 350 ml", "2", null, "0300700", 2));
        assertThat(sugestoes).noneMatch(s -> s.produtoId() == arroz); // NCM, CEST e origem fora do formato

        assertThat(fiscal.preencherDaNota(admin, sugestoes, "5555")).isEqualTo(2);
        ProdutoFiscal c = dados(cafe);
        assertThat(c.ncm()).isEqualTo("09012100");
        assertThat(c.cest()).isEqualTo("1709600");
        assertThat(c.origem()).isZero();
        ProdutoFiscal r = dados(refrigerante);
        assertThat(r.ncm()).as("já estava preenchido").isEqualTo("22021000");
        assertThat(r.cest()).isEqualTo("0300700");
        assertThat(r.origem()).isEqualTo(2);
        assertThat(t.auditoria(AcaoAuditoria.DADOS_FISCAIS_ALTERADOS)).filteredOn(d -> d.contains("pela NF-e"))
                .hasSize(2).filteredOn(d -> d.contains("Refrigerante lata")).singleElement().asString()
                .contains("pela NF-e nº 5555", "CEST: (vazio) → 03.007.00", "origem: (vazio) → 2 — Estrangeira")
                .doesNotContain("NCM:");
        assertThat(fiscal.sugestoesDaNota(admin, xml, Map.of())).as("nada mais vazio").isEmpty();
    }

    /** Aceite (ajuste da parte B): a origem 1 do fornecedor entra como 2, e a 6 como 7; as outras são copiadas. */
    @Test
    void origemDeImportacaoDiretaDoFornecedorEntraComoMercadoInterno() throws Exception {
        String original = new String(LeitorNfeTest.recurso("nfe-fiscal.xml"), StandardCharsets.UTF_8);
        byte[] xml = original.replace("<ICMSSN500><orig>2</orig>", "<ICMSSN500><orig>1</orig>")
                .replaceFirst("<ICMS00><orig>0</orig>", "<ICMS00><orig>6</orig>").getBytes(StandardCharsets.UTF_8);
        assertThat(LeitorNfe.ler(xml).itens()).extracting(i -> i.fiscal().origem()).as("o leitor guarda o da nota")
                .containsExactly(6, 1, null, 0);
        List<SugestaoFiscal> sugestoes = fiscal.sugestoesDaNota(admin, xml, Map.of());
        assertThat(sugestoes).extracting(SugestaoFiscal::produtoId, SugestaoFiscal::origem)
                .containsExactly(tuple(cafe, 7), tuple(refrigerante, 2));
        fiscal.preencherDaNota(admin, sugestoes, "5556");
        assertThat(dados(refrigerante).origem()).isEqualTo(2);
        assertThat(dados(cafe).origem()).isEqualTo(7);
        assertThat(OrigemMercadoria.daNotaDoFornecedor(1)).isEqualTo(2);
        assertThat(OrigemMercadoria.daNotaDoFornecedor(6)).isEqualTo(7);
        for (int o : new int[] {0, 2, 3, 4, 5, 7, 8}) {
            assertThat(OrigemMercadoria.daNotaDoFornecedor(o)).isEqualTo(o);
        }
        assertThat(OrigemMercadoria.daNotaDoFornecedor(null)).isNull();
    }

    @Test
    void xmlComTabelaImportadaSoUsaNcmQueExisteEEscolhaAMao() throws Exception {
        fiscal.importarNcm(admin, "2202.10.00;Águas com açúcar\n".getBytes(StandardCharsets.UTF_8), "ncm.csv");
        long arroz = produto("Arroz 5 kg", null, null); // sem código de barras: escolhido à mão
        byte[] xml = LeitorNfeTest.recurso("nfe-fiscal.xml");
        List<SugestaoFiscal> sugestoes = fiscal.sugestoesDaNota(admin, xml, Map.of(3, arroz));
        assertThat(sugestoes).containsExactly(
                new SugestaoFiscal(cafe, "Café 500 g", "1, 4", null, "1709600", 0), // 0901.21.00 não está na tabela
                new SugestaoFiscal(refrigerante, "Refrigerante lata 350 ml", "2", "22021000", "0300700", 2));

        // Preenchido por outra pessoa entre a sugestão e a confirmação: fica como está.
        fiscal.salvarDadosFiscais(admin, refrigerante, new DadosFiscais(null, null, 1, null));
        assertThat(fiscal.preencherDaNota(admin, List.of(sugestoes.get(1)), "5555")).isEqualTo(1);
        assertThat(dados(refrigerante).origem()).isEqualTo(1);
        assertThat(dados(refrigerante).ncm()).isEqualTo("22021000");
    }

    // ----- Permissão e banco -----

    @Test
    void exigePermissaoFiscal() throws Exception {
        t.usuarios.criar(admin, "cadastro", "Cadastro", false, EnumSet.of(Permissao.PRODUTOS, Permissao.COMPRAS),
                "inicial1", "inicial1");
        Sessao semFiscal = t.acesso.entrar("cadastro", "inicial1");
        assertThatThrownBy(() -> fiscal.perfis(semFiscal, false)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> fiscal.salvarPerfil(semFiscal, null, simples("X", "102")))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> fiscal.salvarDadosFiscais(semFiscal, cafe, new DadosFiscais(null, null, 0, null)))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> fiscal.aplicarPerfil(semFiscal, 1, AlvoPerfil.categoria(bebidas)))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> fiscal.importarNcm(semFiscal, new byte[0], "x.csv"))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> fiscal.sugestoesDaNota(semFiscal, LeitorNfeTest.recurso("nfe-fiscal.xml"), Map.of()))
                .isInstanceOf(PermissaoNegadaException.class);

        t.usuarios.criar(admin, "fiscal", "Fiscal", false, EnumSet.of(Permissao.FISCAL), "inicial1", "inicial1");
        Sessao comFiscal = t.acesso.entrar("fiscal", "inicial1");
        assertThat(fiscal.salvarPerfil(comFiscal, null, simples("Simples 102", "102"))).isPositive();
    }

    /** O banco garante só o que não é regra fiscal: origem de 0 a 8, uma situação do ICMS, alíquotas e nome único. */
    @Test
    void bancoGuardaAsRegrasEstruturais() throws Exception {
        long id = fiscal.salvarPerfil(admin, null, simples("Simples 102", "102"));
        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE produto SET origem = 9 WHERE id = " + cafe))
                    .hasMessageContaining("ck_produto_origem");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE perfil_tributario SET cst_icms = '00' WHERE id = " + id))
                    .hasMessageContaining("ck_pt_icms");
            assertThatThrownBy(() -> st.executeUpdate(
                    "UPDATE perfil_tributario SET aliquota_icms = 101 WHERE id = " + id))
                    .hasMessageContaining("ck_pt_aliquotas");
            st.executeUpdate("UPDATE produto SET perfil_tributario_id = " + id + " WHERE id = " + cafe);
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM perfil_tributario WHERE id = " + id))
                    .hasMessageContaining("fk_produto_perfil_tributario");
            // Formato fora do sistema passa: quem acusa é o R30 (F19.4).
            st.executeUpdate("UPDATE produto SET ncm = '2203', cest = 'ABC' WHERE id = " + cafe);
        }
        assertThat(dados(cafe).ncm()).isEqualTo("2203");
    }
}
