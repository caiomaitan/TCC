package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Comprovante;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosPerfil;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.LinhaComprovante;
import br.com.varesync.model.Periodo;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.SugestaoFiscal;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TributoItem;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelImpostosVendas;
import br.com.varesync.report.RelInconsistenciasFiscais;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.service.fiscal.ContextoFiscal;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tributos da venda, parte B (F22.4 e F22.5): o ST retido pela NF-e de compra, os tributos aproximados no comprovante
 * (e na segunda via), o R31 batendo com a soma dos itens e as regras 14 a 17 do R30 no banco.
 */
class TributosParteBIT extends IntegracaoBase {

    private static final String CERVEJA = "7891000315507";
    private static final String REFRIGERANTE = "7896004000015";
    private static final String ARROZ = "7891234567895";
    private static final String ARROZ_CAIXA = "17891234567892";
    private static final String CAFE = "7896006716112";

    private AcessoTeste t;
    private VendaService vendas;
    private FiscalService fiscal;
    private Sessao admin;
    private FormaPagamento dinheiro;

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        vendas = t.vendas;
        fiscal = t.fiscal;
        admin = t.supervisor();
        dinheiro = vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow();
        t.caixa.abrir(admin, v("100.00"));
    }

    private long produto(String nome, String preco, String... codigos) {
        List<CodigoBarras> lista = new java.util.ArrayList<>();
        for (int i = 0; i < codigos.length; i++) {
            lista.add(new CodigoBarras(codigos[i], i == 0));
        }
        return t.produtos.criar(admin, new DadosProduto(nome, null, "UN", v(preco), v("3.00"), null, null, lista,
                v("100")));
    }

    private void executar(String sql, Object... parametros) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            ps.executeUpdate();
        }
    }

    private String st(long produtoId) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement("""
                SELECT st_base_unitaria || ';' || coalesce(st_aliquota::text, '-') || ';' || st_icms_substituto_unitario
                       || ';' || st_icms_retido_unitario FROM produto WHERE id = ?""")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private VendaService.VendaFinalizada vender(Map<Long, String> itens) {
        Carrinho carrinho = new Carrinho();
        itens.forEach((id, q) -> vendas.adicionarItem(admin, carrinho,
                vendas.lerProduto(admin, String.valueOf(id)).produto(), v(q)));
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return vendas.finalizar(admin, carrinho, r);
    }

    private long perfil(DadosPerfil d) {
        return fiscal.salvarPerfil(admin, null, d);
    }

    private static DadosPerfil simples(String nome, String cfop, String csosn) {
        return new DadosPerfil(nome, cfop, true, csosn, null, null, "49", null, "49", null, null, null, null, true);
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(t.relogio.instant(), ZoneId.systemDefault());
    }

    // ----- F22.4 ST retido pela NF-e -----

    @Test
    void notaComIcms60GravaOStPorUnidadeEAVendaUsa() throws Exception {
        long cerveja = produto("Cerveja lata 350 ml", "6.00", CERVEJA);
        long refri = produto("Refrigerante lata 350 ml", "3.50", REFRIGERANTE);
        long arroz = produto("Arroz tipo 1 1 kg", "7.00", ARROZ, ARROZ_CAIXA);
        executar("UPDATE produto_codigo_barras SET quantidade = 12 WHERE codigo = ?", ARROZ_CAIXA);
        long cafe = produto("Café 500 g", "15.00", CAFE);
        // O ST muda a cada compra: o que o produto tinha é substituído.
        executar("UPDATE produto SET st_base_unitaria = 1, st_icms_retido_unitario = 1 WHERE id = ?", refri);

        byte[] xml = LeitorNfeTest.recurso("nfe-st.xml");
        List<SugestaoFiscal> sugestoes = fiscal.sugestoesDaNota(admin, xml, Map.of(), Map.of());
        Map<Long, SugestaoFiscal> porProduto = sugestoes.stream()
                .collect(Collectors.toMap(SugestaoFiscal::produtoId, s -> s));
        assertThat(porProduto.get(cerveja).st().baseUnitaria()).isEqualByComparingTo("5.0000");
        assertThat(porProduto.get(cerveja).st().icmsRetidoUnitario()).isEqualByComparingTo("0.3000");
        assertThat(porProduto.get(arroz).st().baseUnitaria()).as("2 caixas de 12 = 24 un")
                .isEqualByComparingTo("10.0000");
        assertThat(porProduto.get(cafe).st()).as("ICMS00 sem ST").isNull();

        assertThat(fiscal.preencherDaNota(admin, sugestoes, "7777")).isEqualTo(4);
        assertThat(st(cerveja)).isEqualTo("5.0000;18.00;0.2000;0.3000");
        assertThat(st(refri)).isEqualTo("3.5000;18.00;0.4500;0.1800");
        assertThat(st(arroz)).isEqualTo("10.0000;18.00;0.8333;0.9667");
        assertThat(t.auditoria(AcaoAuditoria.DADOS_FISCAIS_ALTERADOS)).anySatisfy(d -> assertThat(d)
                .contains("Cerveja lata 350 ml", "pela NF-e nº 7777",
                        "ST retido por unidade: (vazio) → base R$ 5,0000, alíquota 18,00%, ICMS próprio R$ 0,2000, "
                                + "retido R$ 0,3000"));

        // Aceite: vender 2 un registra R$ 10,00 e R$ 0,60.
        long perfilSt = perfil(simples("Simples ST", "5405", "500"));
        fiscal.salvarDadosFiscais(admin, cerveja, new DadosFiscais("22030000", "0302100", 0, perfilSt));
        long vendaId = vender(Map.of(cerveja, "2")).vendaId();
        TributoItem item = tributo(vendaId);
        assertThat(item.baseStRetido()).isEqualByComparingTo("10.00");
        assertThat(item.icmsStRetido()).isEqualByComparingTo("0.60");

        // A mesma nota de novo não muda nada (o ST é o mesmo).
        assertThat(fiscal.preencherDaNota(admin, fiscal.sugestoesDaNota(admin, xml, Map.of(), Map.of()), "7777"))
                .isZero();
    }

    @Test
    void fatorEscolhidoAMaoDivideOSt() throws Exception {
        long arroz = produto("Arroz tipo 1 1 kg", "7.00", ARROZ);
        // O item 3 (2 CX, EAN de caixa não cadastrado) escolhido à mão com o fator 12.
        List<SugestaoFiscal> s = fiscal.sugestoesDaNota(admin, LeitorNfeTest.recurso("nfe-st.xml"),
                Map.of(3, arroz), Map.of(3, v("12")));
        assertThat(s).singleElement().satisfies(x -> assertThat(x.st().icmsRetidoUnitario())
                .isEqualByComparingTo("0.9667"));
        List<SugestaoFiscal> semFator = fiscal.sugestoesDaNota(admin, LeitorNfeTest.recurso("nfe-st.xml"),
                Map.of(3, arroz), Map.of());
        assertThat(semFator.get(0).st().icmsRetidoUnitario()).as("sem fator, 2 un").isEqualByComparingTo("11.6000");
    }

    private TributoItem tributo(long vendaId) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM venda_item WHERE venda_id = ? ORDER BY sequencia LIMIT 1")) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new TributoVendaDAO().buscar(con, rs.getLong(1)).orElseThrow();
            }
        }
    }

    // ----- F22.5 comprovante, R31 e R30 -----

    private long[] cenarioDeVendas() {
        fiscal.importarIbpt(admin, TabelaIbptTest.iso(TabelaIbptTest.ARQUIVO), "TabelaIBPTaxSP26.2.A.csv");
        long tributado = perfil(simples("Simples tributado", "5102", "102"));
        long agua = produto("Água com gás 500 ml", "10.00", CERVEJA);
        long cafe = produto("Café 500 g", "15.00", CAFE);
        long semPerfil = produto("Pilha AA", "8.00", REFRIGERANTE);
        fiscal.salvarDadosFiscais(admin, agua, new DadosFiscais("22021000", null, 0, tributado));
        fiscal.salvarDadosFiscais(admin, cafe, new DadosFiscais("09012100", null, 0, tributado));
        return new long[] {agua, cafe, semPerfil};
    }

    @Test
    void comprovanteComOsTributosAproximadosESegundaViaPeloGravado() throws Exception {
        long[] p = cenarioDeVendas();
        VendaService.VendaFinalizada venda = vender(new java.util.LinkedHashMap<>(Map.of(p[0], "1", p[1], "1")));

        // Água: 1,35 / 1,80 / 0,00; café (8,03 / 7,00): 1,20 / 1,05 / 0,00.
        Comprovante c = venda.comprovante();
        assertThat(c.tributos().texto()).isEqualTo("Tributos aproximados: Federal R$ 2,55 · Estadual R$ 2,85 · "
                + "Municipal R$ 0,00 (Fonte: IBPT)");
        String texto = c.linhas().stream().map(LinhaComprovante::esquerda).collect(Collectors.joining(" "));
        assertThat(texto).contains("Tributos aproximados: Federal R$ 2,55 · Estadual R$ 2,85 · Municipal R$ 0,00",
                "(Fonte: IBPT)");
        assertThat(c.linhas()).allSatisfy(l -> assertThat(l.esquerda().length()).isLessThanOrEqualTo(
                Comprovante.COLUNAS));

        // Uma tabela nova não muda a segunda via.
        String nova = TabelaIbptTest.CABECALHO
                + "22021000;;0;Água;50,00;50,00;50,00;50,00;01/10/2026;31/12/2026;Z;27.1.A;IBPT\n";
        fiscal.importarIbpt(admin, TabelaIbptTest.iso(nova), "TabelaIBPTaxSP27.1.A.csv");
        assertThat(vendas.segundaVia(admin, venda.vendaId()).tributos()).isEqualTo(c.tributos());

        // Sem item com a tabela IBPT, sem a linha.
        long vendaSem = vender(Map.of(p[2], "1")).vendaId();
        assertThat(vendas.segundaVia(admin, vendaSem).tributos()).isNull();
        assertThat(vendas.segundaVia(admin, vendaSem).linhas()).noneSatisfy(l -> assertThat(l.esquerda())
                .contains("Tributos aproximados"));
    }

    @Test
    void r31BateComASomaDosItensSemCanceladasEComOsItensSemTributacao() throws Exception {
        long[] p = cenarioDeVendas();
        vender(new java.util.LinkedHashMap<>(Map.of(p[0], "2", p[1], "1")));
        vender(Map.of(p[2], "3")); // sem perfil: sem tributação calculada
        long cancelada = vender(Map.of(p[0], "5")).vendaId();
        vendas.cancelar(admin, cancelada, "Teste", null);

        ResultadoRelatorio<RelImpostosVendas.Linha> r31 = t.relatorios.gerar(admin, new RelImpostosVendas(),
                Filtros.vazios().comPeriodo(new Periodo(hoje(), hoje())));
        List<RelImpostosVendas.Linha> linhas = r31.linhas();
        assertThat(linhas).hasSize(2);
        RelImpostosVendas.Linha grupo = linhas.get(0);
        assertThat(grupo.cfop()).isEqualTo("5102");
        assertThat(grupo.situacao()).isEqualTo("CSOSN 102");
        assertThat(grupo.itens()).isEqualTo(2);
        assertThat(grupo.valorLiquido()).isEqualByComparingTo("35.00");
        // Água 2 × 10 = 20: 2,69 / 3,60; café 15: 1,20 / 1,05. Total 8,54 (canceladas fora).
        assertThat(grupo.aproximados()).isEqualByComparingTo("8.54");
        assertThat(grupo.cbs()).as("Simples sem CBS").isNull();
        RelImpostosVendas.Linha sem = linhas.get(1);
        assertThat(sem.semTributacao()).isTrue();
        assertThat(sem.situacao()).isEqualTo(RelImpostosVendas.SEM_TRIBUTACAO);
        assertThat(sem.itens()).isEqualTo(1);
        assertThat(sem.valorLiquido()).isEqualByComparingTo("24.00");

        // A soma dos itens (fora as canceladas) bate com o R31.
        assertThat(new BigDecimal(valor("""
                SELECT sum(t.trib_federal + t.trib_estadual + t.trib_municipal)
                  FROM venda_item_tributo t JOIN venda_item vi ON vi.id = t.venda_item_id
                  JOIN venda v ON v.id = vi.venda_id WHERE v.status = 'FINALIZADA'"""))).isEqualByComparingTo("8.54");
        assertThat(new BigDecimal(valor("""
                SELECT sum(t.trib_federal + t.trib_estadual + t.trib_municipal)
                  FROM venda_item_tributo t"""))).as("com a cancelada").isGreaterThan(v("8.54"));
    }

    @Test
    void r31PedeAPermissaoFiscal() throws Exception {
        t.usuarios.criar(admin, "rel", "Relatórios", false, EnumSet.of(Permissao.RELATORIOS), "inicial1",
                "inicial1");
        Sessao soRelatorios = t.acesso.entrar("rel", "inicial1");
        assertThatThrownBy(() -> t.relatorios.gerar(soRelatorios, new RelImpostosVendas(), Filtros.vazios()
                .comPeriodo(new Periodo(hoje(), hoje())))).isInstanceOf(PermissaoNegadaException.class);
    }

    @Test
    void r30ComAsRegras14A17() throws Exception {
        executar("UPDATE configuracao SET valor = 'NORMAL' WHERE chave = 'empresa.regime_tributario'");
        fiscal.importarIbpt(admin, TabelaIbptTest.iso(TabelaIbptTest.ARQUIVO), "TabelaIBPTaxSP26.2.A.csv");
        long cst10 = perfil(new DadosPerfil("CST 10", "5102", false, "10", v("18"), null, "01", v("1.65"), "01",
                v("7.6"), null, "000", "000001", true));
        long st60 = perfil(new DadosPerfil("ST 60", "5405", false, "60", null, null, "04", null, "04", null, null,
                "000", "000001", true));
        long cst550 = perfil(new DadosPerfil("IBS 550", "5102", false, "00", v("18"), null, "01", v("1.65"), "01",
                v("7.6"), null, "550", "550001", true));
        long cst200 = perfil(new DadosPerfil("IBS 200 sem redução", "5102", false, "00", v("18"), null, "01",
                v("1.65"), "01", v("7.6"), null, "200", "200001", true));
        long a = produto("A cst 10", "5.00", CERVEJA);
        long b = produto("B st sem retido", "5.00", REFRIGERANTE);
        long c = produto("C ncm fora do ibpt", "5.00", CAFE);
        long d = produto("D ibs 550", "5.00", ARROZ);
        long e = produto("E ibs 200", "5.00");
        fiscal.salvarDadosFiscais(admin, a, new DadosFiscais("22021000", null, 0, cst10));
        fiscal.salvarDadosFiscais(admin, b, new DadosFiscais("22021000", "0300700", 0, st60));
        fiscal.salvarDadosFiscais(admin, c, new DadosFiscais("10063021", null, 0, cst550));
        fiscal.salvarDadosFiscais(admin, d, new DadosFiscais("22021000", null, 0, cst550));
        fiscal.salvarDadosFiscais(admin, e, new DadosFiscais("22021000", null, 0, cst200));

        List<RelInconsistenciasFiscais.Linha> linhas;
        try (Connection con = dataSource.getConnection()) {
            linhas = RelInconsistenciasFiscais.inconsistencias(con, ContextoFiscal.da(
                    t.configuracoes.carregar(admin).loja(), hoje()));
        }
        Map<String, List<Integer>> regras = linhas.stream().filter(l -> l.regra() >= 14).collect(
                Collectors.groupingBy(RelInconsistenciasFiscais.Linha::produto, Collectors.mapping(
                        RelInconsistenciasFiscais.Linha::regra, Collectors.toList())));
        assertThat(regras).containsEntry("A cst 10", List.of(14)).containsEntry("B st sem retido", List.of(16))
                .containsEntry("C ncm fora do ibpt", List.of(15, 17)).containsEntry("D ibs 550", List.of(17))
                .containsEntry("E ibs 200", List.of(17));
        // Com o ST retido, a 16 some.
        executar("UPDATE produto SET st_icms_retido_unitario = 0.3 WHERE id = ?", b);
        try (Connection con = dataSource.getConnection()) {
            assertThat(RelInconsistenciasFiscais.inconsistencias(con, ContextoFiscal.da(
                    t.configuracoes.carregar(admin).loja(), hoje()))).noneMatch(l -> l.regra() == 16);
            // Sem o dia de hoje (contexto antigo), a 15 não confere.
            assertThat(RelInconsistenciasFiscais.inconsistencias(con, ContextoFiscal.da(
                    t.configuracoes.carregar(admin).loja()))).noneMatch(l -> l.regra() == 15);
        }
    }

    private String valor(String sql) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
