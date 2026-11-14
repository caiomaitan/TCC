package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosPerfil;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelInconsistenciasFiscais;
import br.com.varesync.report.RelInconsistenciasFiscais.Linha;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.service.fiscal.Gravidade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R30 Inconsistências fiscais (F19.4) e o alerta do painel (F19.5), de ponta a ponta no banco: só produtos ativos, as
 * regras do perfil em cada produto que o usa, a existência nas tabelas NCM e CEST conferida no banco, o filtro por
 * regra, o regime da empresa, o painel contando só os produtos com erro e as permissões RELATORIOS e FISCAL.
 */
class InconsistenciasFiscaisIT extends IntegracaoBase {

    private final RelInconsistenciasFiscais r30 = new RelInconsistenciasFiscais();
    private AcessoTeste t;
    private FiscalService fiscal;
    private Sessao admin;
    private long tributado;
    private long st;
    private long certo;
    private long semNcm;
    private long energetico;
    private long carvao;
    private long azeite;
    private long pilsen;
    private long puroMalte;

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        fiscal = t.fiscal;
        admin = t.supervisor();
        loja(RegimeTributario.SIMPLES);
        tributado = fiscal.salvarPerfil(admin, null, perfil("Simples tributado", "5102", true, "102", "0", "49", "0"));
        st = fiscal.salvarPerfil(admin, null, perfil("Simples ST", "5405", true, "500", "0", "49", "0"));
        long stErrado = fiscal.salvarPerfil(admin, null, perfil("Simples ST (revisar)", "5405", true, "102", "0", "49",
                "0"));
        long normal = fiscal.salvarPerfil(admin, null, new DadosPerfil("Regime normal (revisar)", "5102", false, "00",
                new BigDecimal("18"), null, "01", new BigDecimal("1.65"), "01", new BigDecimal("7.6"), null, null, null,
                true));
        certo = produto("Arroz 5 kg", "1006.30.21", null, 0, tributado);
        semNcm = produto("Guarda-chuva", null, null, 0, tributado);
        energetico = produto("Energético 250 ml", "2202.99.00", null, 0, st);
        carvao = produto("Carvão 3 kg", "4402.90.00", null, 0, stErrado);
        azeite = produto("Azeite 500 ml", "1509.20.00", null, 0, normal);
        pilsen = produto("Cerveja pilsen", "2203.00.00", "03.021.00", 0, st);
        puroMalte = produto("Cerveja puro malte", "2203.00.00", "03.021.00", 0, tributado);
        long inativo = produto("Produto antigo sem nada", null, null, null, null);
        t.produtos.alterarSituacao(admin, inativo, false);
    }

    private static DadosPerfil perfil(String nome, String cfop, boolean csosn, String situacao, String icms,
                                      String cstPisCofins, String aliquota) {
        return new DadosPerfil(nome, cfop, csosn, situacao, new BigDecimal(icms), null, cstPisCofins,
                new BigDecimal(aliquota), cstPisCofins, new BigDecimal(aliquota), null, null, null, true);
    }

    private long produto(String nome, String ncm, String cest, Integer origem, Long perfil) {
        long id = t.produtos.criar(admin, new DadosProduto(nome, null, "UN", new BigDecimal("5.00"),
                new BigDecimal("3.00"), null, null, List.<CodigoBarras>of(), null));
        if (ncm != null || cest != null || origem != null || perfil != null) {
            fiscal.salvarDadosFiscais(admin, id, new DadosFiscais(ncm, cest, origem, perfil));
        }
        return id;
    }

    private void loja(RegimeTributario regime) {
        Configuracoes c = t.configuracoes.carregar(admin);
        Configuracoes.Loja l = c.loja();
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja(l.nome(), l.documento(), l.endereco(),
                l.telefone(), regime, "SP", "")));
    }

    private List<Linha> gerar(Integer regra) {
        return t.relatorios.gerar(admin, r30, Filtros.vazios().comRegraFiscal(regra)).linhas();
    }

    private Integer alerta(Sessao sessao) {
        return t.painel.carregar(sessao).alertas().inconsistenciasFiscais();
    }

    @Test
    void r30ListaCadaInconsistenciaDosProdutosAtivos() {
        ResultadoRelatorio<Linha> r = t.relatorios.gerar(admin, r30, Filtros.vazios());
        assertThat(r.linhas()).extracting(Linha::produto, Linha::regra, Linha::gravidade).containsExactly(
                tuple("Azeite 500 ml", 4, Gravidade.ERRO),
                tuple("Azeite 500 ml", 11, Gravidade.ERRO),
                tuple("Azeite 500 ml", 11, Gravidade.ERRO),
                tuple("Carvão 3 kg", 5, Gravidade.ERRO),
                tuple("Cerveja pilsen", 13, Gravidade.AVISO),
                tuple("Cerveja pilsen", 16, Gravidade.AVISO),
                tuple("Cerveja puro malte", 13, Gravidade.AVISO),
                tuple("Energético 250 ml", 9, Gravidade.ERRO),
                tuple("Energético 250 ml", 16, Gravidade.AVISO),
                tuple("Guarda-chuva", 1, Gravidade.ERRO));
        assertThat(r.linhas()).extracting(Linha::produtoId).doesNotContain(certo); // e o inativo não entra
        Linha cerveja = r.linhas().get(4);
        assertThat(cerveja.perfil()).isEqualTo("Simples ST");
        assertThat(cerveja.ncm()).isEqualTo("22030000");
        assertThat(cerveja.regraRotulo()).isEqualTo("13 — Mesmo NCM em perfis diferentes");
        assertThat(cerveja.mensagem()).contains("no perfil \"Simples tributado\"");
        assertThat(r.linhas().get(3).mensagem()).contains("CFOP 5405", "CSOSN 102");
        assertThat(r.totais()).contains("6 produtos", "10 inconsistências (6 erros, 4 avisos)");
        assertThat(r.relatorio().descreverFiltros(r.filtros())).isEqualTo("Regra: todas");
    }

    @Test
    void filtroPorRegra() {
        assertThat(gerar(13)).extracting(Linha::produtoId).containsExactly(pilsen, puroMalte);
        assertThat(gerar(9)).extracting(Linha::produtoId).containsExactly(energetico);
        assertThat(gerar(7)).isEmpty();
        assertThat(t.relatorios.gerar(admin, r30, Filtros.vazios().comRegraFiscal(5)).filtros()
                .descrever(r30.filtros())).isEqualTo("Regra: 5 — CFOP");
        assertThatThrownBy(() -> gerar(18)).isInstanceOf(NegocioException.class).hasMessageContaining("de 1 a 17");
        // F22.5: CSOSN 500 sem o ST retido (aviso).
        assertThat(gerar(16)).extracting(Linha::produtoId).containsExactly(pilsen, energetico);
    }

    /** F19.5: o painel conta só os produtos com erro (as duas cervejas só têm aviso). */
    @Test
    void painelContaSoOsProdutosComErro() {
        assertThat(alerta(admin)).isEqualTo(4); // azeite, carvão, energético e guarda-chuva
        fiscal.salvarDadosFiscais(admin, semNcm, new DadosFiscais("6601.91.00", null, 0, tributado));
        fiscal.salvarDadosFiscais(admin, energetico, new DadosFiscais("2202.99.00", "03.005.00", 0, st));
        assertThat(alerta(admin)).isEqualTo(2);
        assertThat(gerar(null)).extracting(Linha::produtoId).doesNotContain(semNcm);
        assertThat(gerar(null)).filteredOn(l -> l.produtoId() == energetico).extracting(Linha::regra)
                .as("só o aviso do ST retido (F22.5)").containsExactly(16);
    }

    @Test
    void regimeNormalTrocaARegra4ELigaARegra12() {
        loja(RegimeTributario.NORMAL);
        List<Linha> azeite = gerar(null).stream().filter(l -> l.produtoId() == this.azeite).toList();
        // CST 00 no regime normal está certo; sem IBS e CBS é aviso; as alíquotas do PIS e da COFINS passam.
        assertThat(azeite).extracting(Linha::regra, Linha::gravidade).containsExactly(tuple(12, Gravidade.AVISO));
        assertThat(gerar(4)).extracting(Linha::produtoId).as("CSOSN no regime normal")
                .contains(certo, carvao, pilsen).doesNotContain(azeite.get(0).produtoId());
        // No regime normal, todo perfil sem IBS e CBS é avisado (os do Simples também, agora fora do regime).
        assertThat(gerar(12)).extracting(Linha::produtoId).hasSize(7).contains(this.azeite, certo);
        loja(RegimeTributario.SIMPLES);
        assertThat(gerar(12)).as("no Simples, a regra 12 não se aplica").isEmpty();
    }

    /** Regras 2 e 3: formato gravado fora do sistema e a existência nas tabelas importadas, conferida no banco. */
    @Test
    void tabelasNcmECestEFormatoGravadoForaDoSistema() throws Exception {
        assertThat(gerar(2)).isEmpty();
        assertThat(gerar(3)).isEmpty();
        // Só a tabela NCM com o NCM do arroz e da cerveja: os outros NCM passam a não existir.
        fiscal.importarNcm(admin, "1006.30.21;Arroz\n2203.00.00;Cervejas\n".getBytes(StandardCharsets.UTF_8),
                "ncm.csv");
        assertThat(gerar(2)).extracting(Linha::produtoId).containsExactlyInAnyOrder(energetico, carvao, azeite);
        assertThat(gerar(2).get(0).mensagem()).contains("não existe na tabela NCM importada");
        // CEST 03.021.00 só para o NCM 2202 (começo): o das cervejas (2203) não corresponde.
        fiscal.importarCest(admin, "cest;ncm;descricao\n03.021.00;2202;Bebidas\n".getBytes(StandardCharsets.UTF_8),
                "cest.csv");
        assertThat(gerar(3)).extracting(Linha::produtoId, Linha::mensagem).containsExactly(
                tuple(pilsen, "O CEST 03.021.00 não corresponde ao NCM 2203.00.00 na tabela CEST importada."),
                tuple(puroMalte, "O CEST 03.021.00 não corresponde ao NCM 2203.00.00 na tabela CEST importada."));
        fiscal.importarCest(admin, "cest;ncm;descricao\n03.021.00;2203;Cerveja\n".getBytes(StandardCharsets.UTF_8),
                "cest.csv");
        assertThat(gerar(3)).isEmpty();

        try (Connection con = dataSource.getConnection(); Statement st = con.createStatement()) {
            st.executeUpdate("UPDATE produto SET ncm = '2203', cest = 'ABC' WHERE id = " + certo);
        }
        assertThat(gerar(2)).filteredOn(l -> l.produtoId() == certo).extracting(Linha::mensagem)
                .containsExactly("NCM \"2203\" fora do formato: são 8 dígitos (ex.: 2203.00.00).");
        assertThat(gerar(3)).filteredOn(l -> l.produtoId() == certo).extracting(Linha::mensagem)
                .containsExactly("CEST \"ABC\" fora do formato: são 7 dígitos (ex.: 03.021.00).");
    }

    @Test
    void exigeRelatoriosEFiscal() {
        t.usuarios.criar(admin, "gerente", "Gerente", false, EnumSet.of(Permissao.RELATORIOS), "inicial1", "inicial1");
        Sessao semFiscal = t.acesso.entrar("gerente", "inicial1");
        assertThat(t.relatorios.disponiveis(semFiscal)).noneMatch(r -> r instanceof RelInconsistenciasFiscais);
        assertThatThrownBy(() -> t.relatorios.gerar(semFiscal, r30, Filtros.vazios()))
                .isInstanceOf(PermissaoNegadaException.class);
        assertThat(alerta(semFiscal)).as("sem FISCAL, o alerta não se aplica").isNull();

        t.usuarios.criar(admin, "fiscal", "Fiscal", false, EnumSet.of(Permissao.FISCAL), "inicial1", "inicial1");
        Sessao soFiscal = t.acesso.entrar("fiscal", "inicial1");
        assertThat(alerta(soFiscal)).isEqualTo(4);
        // O Cadastro fiscal (menu Fiscal, F19.5) lista as categorias pela FISCAL, sem a permissão PRODUTOS.
        assertThat(fiscal.categorias(soFiscal)).isNotEmpty();
        assertThatThrownBy(() -> t.categorias.listar(soFiscal, true)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> fiscal.categorias(semFiscal)).isInstanceOf(PermissaoNegadaException.class);
        assertThatThrownBy(() -> t.relatorios.gerar(soFiscal, r30, Filtros.vazios()))
                .isInstanceOf(PermissaoNegadaException.class);

        t.usuarios.criar(admin, "contador", "Contador", false, EnumSet.of(Permissao.RELATORIOS, Permissao.FISCAL),
                "inicial1", "inicial1");
        Sessao contador = t.acesso.entrar("contador", "inicial1");
        assertThat(t.relatorios.disponiveis(contador)).anyMatch(r -> r instanceof RelInconsistenciasFiscais);
        assertThat(t.relatorios.gerar(contador, r30, Filtros.vazios()).linhas()).hasSize(10);
    }
}
