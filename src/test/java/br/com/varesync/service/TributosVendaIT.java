package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosPerfil;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.FormaPagamentoCadastro;
import br.com.varesync.model.MeioPagamentoNfce;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TributoItem;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tributos da venda, parte A (F22.1 a F22.3), com os critérios de aceite que dependem dela: o cálculo ao finalizar
 * (Simples com a tabela IBPT, regime normal com desconto rateado, ST retido, item sem dados fiscais), a importação da
 * tabela IBPT, o tPag das formas de pagamento e as configurações {@code fiscal.*}.
 */
class TributosVendaIT extends IntegracaoBase {

    private static final String NCM_AGUA = "22021000";
    private static final String ARQUIVO_IBPT = "TabelaIBPTaxSP26.2.A.csv";

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

    private long produto(String nome, String preco, String ean) {
        return t.produtos.criar(admin, new DadosProduto(nome, null, "UN", v(preco), v("3.00"), null, null,
                List.of(new CodigoBarras(ean, true)), v("100")));
    }

    private void fiscalDoProduto(long produtoId, String ncm, Integer origem, Long perfilId) {
        fiscal.salvarDadosFiscais(admin, produtoId, new DadosFiscais(ncm, null, origem, perfilId));
    }

    private long perfilSimples(String nome, String csosn) {
        return fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples(nome, csosn));
    }

    private long perfilNormal(String nome, String cst, String cstIbsCbs) {
        return fiscal.salvarPerfil(admin, null, new DadosPerfil(nome, "5102", false, cst, v("18"), null, "01",
                v("1.65"), "01", v("7.60"), null, cstIbsCbs, cstIbsCbs == null ? null : cstIbsCbs + "001", true));
    }

    private void executar(String sql, Object... parametros) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            ps.executeUpdate();
        }
    }

    private void regimeNormal() throws SQLException {
        executar("UPDATE configuracao SET valor = 'NORMAL' WHERE chave = 'empresa.regime_tributario'");
    }

    private void importarIbpt() {
        fiscal.importarIbpt(admin, TabelaIbptTest.iso(TabelaIbptTest.ARQUIVO), ARQUIVO_IBPT);
    }

    private VendaService.VendaFinalizada vender(Carrinho carrinho) {
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(dinheiro, r.falta());
        t.relogio.avancar(Duration.ofMinutes(1));
        return vendas.finalizar(admin, carrinho, r);
    }

    private void incluir(Carrinho carrinho, long produtoId, String quantidade) {
        VendaService.LeituraProduto leitura = vendas.lerProduto(admin, String.valueOf(produtoId));
        vendas.adicionarItem(admin, carrinho, leitura.produto(), v(quantidade));
    }

    /** Tributos gravados do item de sequência {@code sequencia} da venda. */
    private Optional<TributoItem> tributo(long vendaId, int sequencia) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM venda_item WHERE venda_id = ? AND sequencia = ?")) {
            ps.setLong(1, vendaId);
            ps.setInt(2, sequencia);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new TributoVendaDAO().buscar(con, rs.getLong(1));
            }
        }
    }

    // ----- Cálculo ao finalizar (F22.2) -----

    @Test
    void simplesCsosn102ComATabelaIbpt() throws Exception {
        importarIbpt();
        long perfil = perfilSimples("Simples tributado", "102");
        long agua = produto("Água com gás 500 ml", "10.00", "7891000315507");
        long importada = produto("Água importada 500 ml", "10.00", "7891234567895");
        fiscalDoProduto(agua, NCM_AGUA, 0, perfil);
        fiscalDoProduto(importada, NCM_AGUA, 2, perfil);

        Carrinho carrinho = new Carrinho();
        incluir(carrinho, agua, "1");
        incluir(carrinho, importada, "1");
        VendaService.VendaFinalizada venda = vender(carrinho);

        assertThat(venda.total()).as("o cálculo não muda a venda").isEqualByComparingTo("20.00");
        TributoItem t1 = tributo(venda.vendaId(), 1).orElseThrow();
        assertThat(t1.csosn()).isEqualTo("102");
        assertThat(t1.ncm()).isEqualTo(NCM_AGUA);
        assertThat(t1.cfop()).isEqualTo("5102");
        assertThat(t1.valorIcms()).isEqualByComparingTo("0");
        assertThat(t1.valorPis()).isEqualByComparingTo("0");
        assertThat(t1.valorCofins()).isEqualByComparingTo("0");
        assertThat(t1.baseIbsCbs()).isNull();
        assertThat(t1.tribFederal()).isEqualByComparingTo("1.35");
        assertThat(t1.tribEstadual()).isEqualByComparingTo("1.80");
        assertThat(t1.tribMunicipal()).isEqualByComparingTo("0.00");
        assertThat(t1.tributosAproximados()).isEqualByComparingTo("3.15");
        assertThat(t1.ibptVersao()).isEqualTo("26.2.A");
        TributoItem t2 = tributo(venda.vendaId(), 2).orElseThrow();
        assertThat(t2.origem()).isEqualTo(2);
        assertThat(t2.tribFederal()).isEqualByComparingTo("1.55");
    }

    @Test
    void normalCst00ComDescontoRateado() throws Exception {
        regimeNormal();
        long perfil = perfilNormal("Tributado 18%", "00", "000");
        long refri = produto("Refrigerante lata", "5.00", "7891000315507");
        fiscalDoProduto(refri, NCM_AGUA, 0, perfil);

        Carrinho carrinho = new Carrinho();
        incluir(carrinho, refri, "2");
        vendas.aplicarDescontoVenda(admin, carrinho, TipoDesconto.VALOR, v("0.50"), null);
        VendaService.VendaFinalizada venda = vender(carrinho);

        assertThat(venda.total()).isEqualByComparingTo("9.50");
        TributoItem item = tributo(venda.vendaId(), 1).orElseThrow();
        assertThat(item.baseIcms()).isEqualByComparingTo("9.50");
        assertThat(item.valorIcms()).isEqualByComparingTo("1.71");
        assertThat(item.basePis()).isEqualByComparingTo("7.79");
        assertThat(item.valorPis()).isEqualByComparingTo("0.13");
        assertThat(item.valorCofins()).isEqualByComparingTo("0.59");
        assertThat(item.baseIbsCbs()).isEqualByComparingTo("7.07");
        assertThat(item.valorCbs()).isEqualByComparingTo("0.06");
        assertThat(item.valorIbsUf()).isEqualByComparingTo("0.01");
        assertThat(item.valorIbsMun()).isEqualByComparingTo("0.00");
        assertThat(item.cstIbsCbs()).isEqualTo("000");
        assertThat(item.cclassTrib()).isEqualTo("000001");
        assertThat(item.tribFederal()).as("sem a tabela IBPT").isNull();
        assertThat(t.contar("SELECT count(*) FROM venda WHERE id = " + venda.vendaId()
                + " AND valor_total = 9.50 AND desconto_venda = 0.50")).isEqualTo(1);
    }

    @Test
    void cst60ComOStRetidoDoProduto() throws Exception {
        regimeNormal();
        long perfil = fiscal.salvarPerfil(admin, null, new DadosPerfil("ST", "5405", false, "60", null, null, "04",
                null, "04", null, null, null, null, true));
        long cerveja = produto("Cerveja lata 350 ml", "6.00", "7891000315507");
        fiscalDoProduto(cerveja, "22030000", 0, perfil);
        // O ST retido vem da NF-e de compra na parte B (F22.4): aqui, direto no banco.
        executar("UPDATE produto SET st_base_unitaria = 5.0000, st_aliquota = 18, st_icms_substituto_unitario = 0.5000,"
                + " st_icms_retido_unitario = 0.3000 WHERE id = ?", cerveja);

        Carrinho carrinho = new Carrinho();
        incluir(carrinho, cerveja, "2");
        TributoItem item = tributo(vender(carrinho).vendaId(), 1).orElseThrow();
        assertThat(item.baseStRetido()).isEqualByComparingTo("10.00");
        assertThat(item.icmsStRetido()).isEqualByComparingTo("0.60");
        assertThat(item.icmsSubstituto()).isEqualByComparingTo("1.00");
        assertThat(item.aliquotaSt()).isEqualByComparingTo("18");
        assertThat(item.valorIcms()).isEqualByComparingTo("0");
    }

    @Test
    void soOsItensComNcmOrigemEPerfilAtivoGanhamTributos() throws Exception {
        long perfil = perfilSimples("Simples tributado", "102");
        long inativo = perfilSimples("Simples antigo", "102");
        long comTudo = produto("Com tudo", "2.00", "7891000315507");
        long semPerfil = produto("Sem perfil", "2.00", "7891234567895");
        long semNcm = produto("Sem NCM", "2.00", "7896004000015");
        long semOrigem = produto("Sem origem", "2.00", "7896006716112");
        long perfilInativo = produto("Perfil inativo", "2.00", "7896543900012");
        fiscalDoProduto(comTudo, NCM_AGUA, 0, perfil);
        fiscalDoProduto(semPerfil, NCM_AGUA, 0, null);
        fiscalDoProduto(semNcm, null, 0, perfil);
        fiscalDoProduto(semOrigem, NCM_AGUA, null, perfil);
        fiscalDoProduto(perfilInativo, NCM_AGUA, 0, inativo);
        PerfilTributario p = fiscal.perfis(admin, false).stream().filter(x -> x.id() == inativo).findFirst()
                .orElseThrow();
        fiscal.salvarPerfil(admin, inativo, new DadosPerfil(p.nome(), p.cfop(), true, p.csosn(), null, null, "49",
                null, "49", null, null, null, null, false));

        Carrinho carrinho = new Carrinho();
        for (long id : List.of(comTudo, semPerfil, semNcm, semOrigem, perfilInativo, comTudo)) {
            incluir(carrinho, id, "1");
        }
        vendas.cancelarItem(admin, carrinho, 6, null);
        long vendaId = vender(carrinho).vendaId();

        assertThat(tributo(vendaId, 1)).isPresent();
        for (int seq = 2; seq <= 6; seq++) {
            assertThat(tributo(vendaId, seq)).as("item " + seq).isEmpty();
        }
        assertThat(t.contar("SELECT count(*) FROM venda_item_tributo")).isEqualTo(1);
    }

    @Test
    void vendaCanceladaContinuaComOsTributosGravados() throws Exception {
        long perfil = perfilSimples("Simples tributado", "102");
        long agua = produto("Água", "10.00", "7891000315507");
        fiscalDoProduto(agua, NCM_AGUA, 0, perfil);
        Carrinho carrinho = new Carrinho();
        incluir(carrinho, agua, "1");
        long vendaId = vender(carrinho).vendaId();

        vendas.cancelar(admin, vendaId, "Teste", null);

        assertThat(tributo(vendaId, 1)).as("o R31 (parte B) é que deixa a cancelada de fora").isPresent();
    }

    // ----- Tabela IBPT (F22.3) -----

    @Test
    void importarIbptTrocaATabelaInteiraComAuditoria() throws Exception {
        importarIbpt();
        assertThat(t.contar("SELECT count(*) FROM ibpt")).isEqualTo(3);
        assertThat(t.contar("SELECT count(*) FROM ibpt WHERE ex = ''")).isEqualTo(2);
        FiscalService.SituacaoIbpt s = fiscal.situacaoIbpt(admin);
        assertThat(s.linhas()).isEqualTo(3);
        assertThat(s.versao()).isEqualTo("26.2.A");
        assertThat(s.vencida()).isFalse();
        assertThat(t.auditoria(AcaoAuditoria.TABELA_FISCAL_IMPORTADA)).anySatisfy(d -> assertThat(d)
                .contains("Tabela IBPT importada (" + ARQUIVO_IBPT + ")").contains("versão 26.2.A")
                .contains("vigência de 01/10/2026 a 31/12/2026").contains("3 linhas de NCM")
                .contains("2 linhas de serviço fora"));

        String nova = TabelaIbptTest.CABECALHO
                + "09012100;;0;Café;8,03;10,03;7,00;0,00;01/01/2027;31/03/2027;Z9;27.1.A;IBPT\n";
        fiscal.importarIbpt(admin, TabelaIbptTest.iso(nova), "TabelaIBPTaxSP27.1.A.csv");
        assertThat(t.contar("SELECT count(*) FROM ibpt")).as("reimportar troca a tabela inteira").isEqualTo(1);
        assertThat(fiscal.situacaoIbpt(admin).versao()).isEqualTo("27.1.A");

        long antes = t.contar("SELECT count(*) FROM ibpt");
        assertThatThrownBy(() -> fiscal.importarIbpt(admin, TabelaIbptTest.iso(TabelaIbptTest.CABECALHO
                + "123;;0;Erro;1;1;1;1;01/01/2027;31/03/2027;Z;1;X\n"), "erro.csv"))
                .isInstanceOf(NegocioException.class);
        assertThat(t.contar("SELECT count(*) FROM ibpt")).as("com erro, nada muda").isEqualTo(antes);
    }

    @Test
    void tabelaIbptVencidaETabelaForaDaVigenciaNaoEntraNaVenda() throws Exception {
        String passada = TabelaIbptTest.CABECALHO
                + NCM_AGUA + ";;0;Água;13,45;15,45;18,00;0,00;01/07/2026;30/09/2026;A;26.1.A;IBPT\n";
        fiscal.importarIbpt(admin, TabelaIbptTest.iso(passada), "TabelaIBPTaxSP26.1.A.csv");
        FiscalService.SituacaoIbpt s = fiscal.situacaoIbpt(admin);
        assertThat(s.vencida()).isTrue();

        long perfil = perfilSimples("Simples tributado", "102");
        long agua = produto("Água", "10.00", "7891000315507");
        fiscalDoProduto(agua, NCM_AGUA, 0, perfil);
        Carrinho carrinho = new Carrinho();
        incluir(carrinho, agua, "1");
        TributoItem item = tributo(vender(carrinho).vendaId(), 1).orElseThrow();
        assertThat(item.tribFederal()).as("sem linha vigente na data da venda").isNull();
    }

    @Test
    void tabelaIbptPedeAPermissaoFiscal() throws Exception {
        t.usuarios.criar(admin, "olga", "Olga", false, EnumSet.of(Permissao.PDV), "inicial1", "inicial1");
        Sessao operador = t.acesso.entrar("olga", "inicial1");
        assertThatThrownBy(() -> fiscal.importarIbpt(operador, TabelaIbptTest.iso(TabelaIbptTest.ARQUIVO), "x.csv"))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> fiscal.situacaoIbpt(operador)).isInstanceOf(PermissaoNegadaException.class);
    }

    // ----- Perfil, tPag e configurações (F22.1) -----

    @Test
    void perfilGravaOFcpEAReducaoDeAliquotaDoIbsECbs() throws Exception {
        long id = fiscal.salvarPerfil(admin, null, new DadosPerfil("Tributado com FCP", "5102", false, "00", v("18"),
                null, "01", v("1.65"), "01", v("7.6"), null, "200", "200001", true, v("2"), v("60")));
        PerfilTributario p = fiscal.perfis(admin, false).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
        assertThat(p.aliquotaFcp()).isEqualByComparingTo("2");
        assertThat(p.reducaoAliquotaIbsCbs()).isEqualByComparingTo("60");

        fiscal.salvarPerfil(admin, id, new DadosPerfil("Tributado com FCP", "5102", false, "00", v("18"), null, "01",
                v("1.65"), "01", v("7.6"), null, "200", "200001", true, v("1"), null));
        assertThat(t.auditoria(AcaoAuditoria.PERFIL_TRIBUTARIO_ALTERADO)).anySatisfy(d -> assertThat(d)
                .contains("alíquota do FCP: 2,00% → 1,00%")
                .contains("redução da alíquota do IBS e da CBS: 60,00% → (vazio)"));

        assertThatThrownBy(() -> fiscal.salvarPerfil(admin, null, new DadosPerfil("X", "5102", false, "00", null,
                null, "01", null, "01", null, null, null, null, true, v("101"), null)))
                .hasMessageContaining("FCP");
        assertThatThrownBy(() -> fiscal.salvarPerfil(admin, null, new DadosPerfil("X", "5102", false, "00", null,
                null, "01", null, "01", null, null, null, null, true, null, v("100.5"))))
                .hasMessageContaining("redução da alíquota do IBS e da CBS");
    }

    @Test
    void tpagPreenchidoPeloTipoEIgualAoCheckDoBanco() throws Exception {
        Map<String, String> porDescricao = t.configuracoes.formasPagamento(admin).stream()
                .collect(Collectors.toMap(FormaPagamentoCadastro::descricao, FormaPagamentoCadastro::tpag));
        assertThat(porDescricao).containsEntry("Dinheiro", "01").containsEntry("PIX", "20")
                .containsEntry("Cartão de débito", "04").containsEntry("Cartão de crédito", "03")
                .containsEntry("Vale-alimentação", "10").containsEntry("Crédito do cliente", "21")
                .containsEntry("Crediário", "05");

        int nova = t.configuracoes.criarForma(admin, "Cheque pré", TipoFormaPagamento.OUTROS);
        int pix = t.configuracoes.criarForma(admin, "PIX da maquininha", TipoFormaPagamento.PIX);
        assertThat(forma(nova).tpag()).as("forma nova pelo trigger").isEqualTo("99");
        assertThat(forma(pix).tpag()).isEqualTo("20");

        t.configuracoes.alterarTpagForma(admin, pix, "17");
        assertThat(forma(pix).tpag()).isEqualTo("17");
        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA)).anySatisfy(d -> assertThat(d)
                .isEqualTo("Forma de pagamento \"PIX da maquininha\": meio de pagamento da NFC-e de 20 — PIX estático "
                        + "para 17 — PIX dinâmico"));
        assertThatThrownBy(() -> t.configuracoes.alterarTpagForma(admin, pix, "90"))
                .hasMessageContaining("Escolha o meio de pagamento");
        assertThatThrownBy(() -> executar("UPDATE forma_pagamento SET tpag = '06' WHERE id = ?", pix))
                .hasMessageContaining("ck_fp_tpag");

        Set<String> doEnum = MeioPagamentoNfce.TODOS.stream().map(MeioPagamentoNfce::codigo)
                .collect(Collectors.toSet());
        assertThat(doEnum).isEqualTo(valoresDoCheck("ck_fp_tpag"));
        for (TipoFormaPagamento tipo : TipoFormaPagamento.values()) {
            assertThat(doEnum).contains(MeioPagamentoNfce.padrao(tipo));
        }
    }

    private FormaPagamentoCadastro forma(int id) {
        return t.configuracoes.formasPagamento(admin).stream().filter(f -> f.id() == id).findFirst().orElseThrow();
    }

    @Test
    void configuracoesFiscaisComValidacaoEAuditoria() throws Exception {
        Configuracoes atuais = t.configuracoes.carregar(admin);
        assertThat(atuais.tributos().aliquotaCbs()).isEqualByComparingTo("0.9");
        assertThat(atuais.tributos().aliquotaIbsUf()).isEqualByComparingTo("0.1");
        assertThat(atuais.tributos().aliquotaIbsMun()).isEqualByComparingTo("0");
        assertThat(atuais.tributos().excluirIcmsBasePisCofins()).isTrue();

        List<String> mudancas = t.configuracoes.salvar(admin, atuais.comTributos(new Configuracoes.Tributos(v("8.8"),
                v("17.7"), v("0.5"), false)));
        assertThat(mudancas).contains("alíquota da CBS de 0,90% para 8,80%",
                "alíquota do IBS da UF de 0,10% para 17,70%", "alíquota do IBS do município de 0,00% para 0,50%",
                "ICMS na base do PIS e da COFINS de excluído para incluído");
        Configuracoes gravadas = t.configuracoes.carregar(admin);
        assertThat(gravadas.tributos().aliquotaIbsUf()).isEqualByComparingTo("17.7");
        assertThat(gravadas.tributos().excluirIcmsBasePisCofins()).isFalse();
        assertThat(gravadas.loja()).as("o resto não muda").isEqualTo(atuais.loja());

        assertThatThrownBy(() -> t.configuracoes.salvar(admin, atuais.comTributos(new Configuracoes.Tributos(
                v("101"), v("0"), v("0"), true)))).hasMessageContaining("A alíquota da CBS deve estar entre 0 e 100%");
        assertThatThrownBy(() -> t.configuracoes.salvar(admin, atuais.comTributos(new Configuracoes.Tributos(
                v("0.125"), v("0"), v("0"), true)))).hasMessageContaining("casas decimais");
    }

    @Test
    void outrosMetodosComOsTributosMantemOsTributos() {
        Configuracoes.Tributos outros = new Configuracoes.Tributos(v("1"), v("2"), v("3"), false);
        Configuracoes c = Configuracoes.PADRAO.comTributos(outros);
        assertThat(c.comLoja(c.loja()).tributos()).isEqualTo(outros);
        assertThat(c.comAssistente(c.assistente()).tributos()).isEqualTo(outros);
        assertThat(c.comEtiquetas(c.etiquetas()).tributos()).isEqualTo(outros);
        assertThat(c.comInatividade(5).tributos()).isEqualTo(outros);
        assertThat(Configuracoes.de(c.paraMapa()).tributos().aliquotaIbsMun()).isEqualByComparingTo("3");
    }

    private Set<String> valoresDoCheck(String constraint) throws Exception {
        String definicao;
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, constraint);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(constraint).isTrue();
                definicao = rs.getString(1);
            }
        }
        Set<String> valores = new HashSet<>();
        Matcher m = Pattern.compile("'(\\w+)'").matcher(definicao);
        while (m.find()) {
            valores.add(m.group(1));
        }
        return valores;
    }
}
