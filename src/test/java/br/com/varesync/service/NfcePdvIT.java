package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.dao.NumeracaoNfceDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.DanfeNfce;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.ComprovantePdf;
import br.com.varesync.report.LeituraQrCode;
import br.com.varesync.service.nfce.AssinaturaNfce;
import br.com.varesync.service.nfce.CertificadoTeste;
import br.com.varesync.service.nfce.GeracaoNfce;
import br.com.varesync.service.nfce.MontagemNfce;
import br.com.varesync.service.nfce.SefazFalsa;
import br.com.varesync.service.nfce.ValidacaoXsd;
import br.com.varesync.service.nfce.XmlNfceTeste;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NFC-e, parte C (F23.6 e F23.7), com a SEFAZ falsa: a venda com a NFC-e ligada (certificado, dados fiscais do
 * produto, CPF/CNPJ na nota, limites de valor, reserva do número na transação), a emissão do PDV com os DANFE
 * (autorizado e de contingência, com a via do estabelecimento e o QR Code lido de volta), a causa da falta de
 * comunicação, a trava da nota de contingência na montagem, "NFC-e ligada", "Testar comunicação" e a reimpressão.
 */
class NfcePdvIT extends IntegracaoBase {

    private static final String ARROZ = "7891234567895";
    private static final String BALA = "7891000315507";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    @TempDir
    static Path pasta;

    private AcessoTeste t;
    private Sessao admin;
    private Terminal terminal;
    private VendaService vendas;
    private NfceTerminalService pdv;
    private EmissaoNfceService emissao;
    private SefazFalsa sefaz;
    private Transacao transacao;
    private final NfceDAO dao = new NfceDAO();
    private long arroz;

    /**
     * O certificado do terminal em memória; a cada uso (a montagem lê o certificado), anota se há NFC-e de contingência
     * sem a trava, para conferir que a de contingência é montada ainda com a trava dela.
     */
    final class Terminal implements CertificadoDoTerminal {
        String caminho = "";
        String senha = "";
        final List<String> contingenciaNaMontagem = new ArrayList<>();

        @Override
        public String caminho() {
            try (Connection con = dataSource.getConnection(); Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT numero, transmitindo_ate IS NOT NULL FROM nfce "
                         + "WHERE status = 'CONTINGENCIA' AND xml IS NULL")) {
                while (rs.next()) {
                    contingenciaNaMontagem.add(rs.getInt(1) + (rs.getBoolean(2) ? " travada" : " sem trava"));
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return caminho;
        }

        @Override
        public String senha() {
            return senha;
        }

        @Override
        public boolean senhaNaVariavel() {
            return false;
        }

        @Override
        public void gravar(String novoCaminho, String novaSenha) {
            caminho = novoCaminho;
            senha = novaSenha;
        }
    }

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        transacao = new Transacao(dataSource);
        terminal = new Terminal();
        terminal.gravar(CertificadoTeste.arquivo().toString(), CertificadoTeste.SENHA);
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), t.relogio, AcessoTeste.TERMINAL);
        CertificadoService certificados = new CertificadoService(transacao, new ConfiguracaoDAO(), auditoria, terminal,
                t.relogio);
        ValidacaoXsd validacao = new ValidacaoXsd(pasta);
        NfceService nfce = new NfceService(transacao, dao, new NumeracaoNfceDAO(), new TributoVendaDAO(),
                new ConfiguracaoDAO(), new GeracaoNfce(new MontagemNfce(FUSO), new AssinaturaNfce(validacao.pasta(),
                FUSO), validacao), certificados, "2.0.0-SNAPSHOT", new Random(7), t.relogio, FUSO);
        sefaz = new SefazFalsa(t.relogio, pasta);
        emissao = new EmissaoNfceService(transacao, dao, nfce, certificados, new ConfiguracaoDAO(), sefaz,
                new ModoContingencia(), auditoria, t.relogio, FUSO, AcessoTeste.TERMINAL);
        pdv = new NfceTerminalService(transacao, dao, new ConfiguracaoDAO(), new TributoVendaDAO(), emissao,
                certificados, sefaz, auditoria);
        vendas = t.vendasCom(new NfceNaVenda(new ConfiguracaoDAO(), new TributoVendaDAO(), certificados, nfce,
                new CancelamentoNfceService(dao, new ConfiguracaoDAO(), emissao, certificados, sefaz, auditoria,
                        t.relogio)));

        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja("Mercado Bom Preço", CertificadoTeste.CNPJ,
                        "Rua das Flores, 100", "1633334444", RegimeTributario.SIMPLES, "SP", "123456789012"))
                .comEmitente(new Configuracoes.Emitente("Empresa Teste Ltda", "Rua das Flores", "100", "", "Centro",
                        "14010100", "Ribeirão Preto", "3543402")));
        long perfil = t.fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples("Simples tributado", "102"));
        arroz = t.produtos.criar(admin, new DadosProduto("Arroz tipo 1 5 kg", null, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.00"), null, null, List.of(new CodigoBarras(ARROZ, true)), new BigDecimal("100")));
        t.fiscal.salvarDadosFiscais(admin, arroz, new DadosFiscais("10063021", null, 0, perfil));
        // A bala fica sem dados fiscais.
        t.produtos.criar(admin, new DadosProduto("Bala de goma", null, "UN", new BigDecimal("1.00"),
                new BigDecimal("0.40"), null, null, List.of(new CodigoBarras(BALA, true)), new BigDecimal("100")));
        t.caixa.abrir(admin, new BigDecimal("100.00"));
        pdv.ligar(admin, true);
    }

    private Carrinho carrinho(String codigo, String quantidade) {
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(admin, carrinho, vendas.lerProduto(admin, codigo).produto(), new BigDecimal(quantidade));
        return carrinho;
    }

    private Recebimento emDinheiro(Carrinho carrinho, String valor, String documento) {
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), new BigDecimal(valor));
        r.definirConsumidorDocumento(documento);
        return r;
    }

    private NfceGravada nota(long id) {
        return transacao.executar(con -> dao.buscar(con, id, false)).orElseThrow();
    }

    // ----- Com a NFC-e ligada, no PDV -----

    @Test
    void produtoSemDadosFiscaisERecusadoNoPdv() {
        assertThatThrownBy(() -> carrinho(BALA, "1")).isInstanceOf(NegocioException.class)
                .hasMessage("O produto \"Bala de goma\" não pode ser vendido com a NFC-e ligada: falta o NCM, a "
                        + "origem da mercadoria e o perfil tributário. Complete os dados fiscais no cadastro do "
                        + "produto.");
        // O produto completo passa.
        assertThat(carrinho(ARROZ, "1").temItensAtivos()).isTrue();
    }

    @Test
    void semCertificadoAVendaNaoComecaEPedeOSupervisor() {
        terminal.gravar("", "");
        assertThatThrownBy(() -> carrinho(ARROZ, "1")).isInstanceOf(NegocioException.class)
                .hasMessageStartingWith("A NFC-e está ligada, e a venda não pode começar: Este terminal não tem o "
                        + "certificado digital").hasMessageEndingWith("Chame o supervisor.");
        terminal.gravar(CertificadoTeste.vencido().toString(), CertificadoTeste.SENHA);
        assertThatThrownBy(() -> carrinho(ARROZ, "1")).hasMessageContaining("vencido");
    }

    @Test
    void cpfInvalidoERecusadoENadaEGravado() throws Exception {
        Carrinho carrinho = carrinho(ARROZ, "2");
        assertThatThrownBy(() -> vendas.finalizar(admin, carrinho, emDinheiro(carrinho, "20.00", "529.982.247-24")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("CPF/CNPJ na nota");
        assertThat(t.contar("SELECT count(*) FROM venda")).isZero();
        assertThat(t.contar("SELECT count(*) FROM nfce")).isZero();
    }

    @Test
    void vendaDeDezMilSemCpfNaoFechaEComCpfFecha() throws Exception {
        Carrinho carrinho = new Carrinho();
        long caro = t.produtos.criar(admin, new DadosProduto("Televisor 55", null, "UN", new BigDecimal("5000.00"),
                new BigDecimal("3000.00"), null, null, List.of(), new BigDecimal("10")));
        t.fiscal.salvarDadosFiscais(admin, caro, new DadosFiscais("85287200", null, 0,
                t.fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples("Simples 2", "102"))));
        vendas.adicionarItem(admin, carrinho, vendas.produtoParaVenda(admin, caro), new BigDecimal("2"));
        assertThat(carrinho.total()).isEqualByComparingTo("10000.00");

        assertThatThrownBy(() -> vendas.finalizar(admin, carrinho, emDinheiro(carrinho, "10000.00", "")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Informe o CPF ou o CNPJ na nota");
        assertThat(t.contar("SELECT count(*) FROM venda")).isZero();

        VendaService.VendaFinalizada v = vendas.finalizar(admin, carrinho, emDinheiro(carrinho, "10000.00",
                "529.982.247-25"));
        assertThat(t.contar("SELECT count(*) FROM venda WHERE consumidor_documento = '52998224725'")).isEqualTo(1);
        assertThat(nota(v.nfceId()).status()).isEqualTo(StatusNfce.PENDENTE);
    }

    @Test
    void vendaReservaONumeroEAAutorizadaImprimeODanfeComOQrCodeLidoDeVolta() throws Exception {
        Carrinho carrinho = carrinho(ARROZ, "2");
        VendaService.VendaFinalizada v = vendas.finalizar(admin, carrinho, emDinheiro(carrinho, "20.00", null));
        // A reserva foi na transação da venda: a NFC-e já existe, PENDENTE, com o número 1.
        NfceGravada reservada = nota(v.nfceId());
        assertThat(reservada.status()).isEqualTo(StatusNfce.PENDENTE);
        assertThat(reservada.vendaId()).isEqualTo(v.vendaId());
        assertThat(reservada.numero()).isEqualTo(1);

        NfceTerminalService.Emissao e = pdv.emitir(admin, v.nfceId());

        assertThat(e.autorizada()).as(e.mensagem()).isTrue();
        assertThat(e.danfes()).hasSize(1);
        DanfeNfce danfe = e.danfes().get(0);
        String texto = danfe.texto();
        assertThat(texto).contains(DanfeNfce.chaveEmGrupos(e.nfce().chave()).get(0),
                "Protocolo de autorização: " + e.nfce().protocolo(), "EMITIDA EM AMBIENTE DE HOMOLOGAÇÃO",
                "SEM VALOR FISCAL", DanfeNfce.NAO_IDENTIFICADO, "Arroz tipo 1 5 kg", "Troco R$");
        assertThat(texto).doesNotContain("NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO"); // a descrição de verdade
        String doXml = XmlNfceTeste.texto(e.nfce().xml(), "qrCode");
        assertThat(danfe.qrCode()).isEqualTo(doXml).isEqualTo(e.nfce().qrcode()).endsWith("|3|2");
        assertThat(LeituraQrCode.lerDaPagina(new ComprovantePdf().preencher(danfe))).isEqualTo(doXml);
        assertThat(danfe.urlConsulta()).isEqualTo(XmlNfceTeste.texto(e.nfce().xml(), "urlChave"));

        // F9 › Reimprimir: o DANFE.
        assertThat(pdv.danfeDaVenda(admin, v.vendaId())).get().extracting(DanfeNfce::chave)
                .isEqualTo(e.nfce().chave());
    }

    @Test
    void emContingenciaODanfeSaiComOsTextosSemProtocoloEComAVia() throws Exception {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        Carrinho carrinho = carrinho(ARROZ, "2");
        VendaService.VendaFinalizada v = vendas.finalizar(admin, carrinho, emDinheiro(carrinho, "20.00",
                "52998224725"));

        NfceTerminalService.Emissao e = pdv.emitir(admin, v.nfceId());

        assertThat(e.contingencia()).isTrue();
        assertThat(e.danfes()).hasSize(2); // nfce.via_estabelecimento = true
        String consumidor = e.danfes().get(0).texto();
        assertThat(consumidor).contains(DanfeNfce.CONTINGENCIA, DanfeNfce.PENDENTE, "CONSUMIDOR - CPF: 529.982.247-25")
                .doesNotContain("Protocolo de autorização", DanfeNfce.VIA_ESTABELECIMENTO);
        assertThat(e.danfes().get(1).texto()).contains(DanfeNfce.VIA_ESTABELECIMENTO);
        String doXml = XmlNfceTeste.texto(e.nfce().xml(), "qrCode");
        assertThat(doXml.split("\\|")).hasSize(8);
        assertThat(LeituraQrCode.lerDaPagina(new ComprovantePdf().preencher(e.danfes().get(1)))).isEqualTo(doXml);

        // A causa da falta de comunicação vai para o motivo da SEM_RESPOSTA e para a auditoria da entrada.
        NfceGravada original = transacao.executar(con -> dao.daVenda(con, v.vendaId())).get(0);
        assertThat(original.status()).isEqualTo(StatusNfce.SEM_RESPOSTA);
        assertThat(original.motivo()).isEqualTo("Sem resposta da SEFAZ: SEFAZ falsa: tempo esgotado");
        assertThat(t.auditoria(AcaoAuditoria.NFCE_CONTINGENCIA_INICIADA)).singleElement().asString()
                .contains("(SEFAZ falsa: tempo esgotado)");

        // O aviso fixo do PDV e a nota de contingência montada ainda com a trava dela (e solta depois).
        assertThat(pdv.contingenciaDesde()).isPresent();
        assertThat(NfceTerminalService.avisoContingencia(pdv.contingenciaDesde().get()))
                .matches("NFC-e em contingência desde \\d\\d:\\d\\d: as notas serão transmitidas quando a SEFAZ "
                        + "voltar");
        assertThat(terminal.contingenciaNaMontagem).contains(e.nfce().numero() + " travada")
                .doesNotContain(e.nfce().numero() + " sem trava");
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE transmitindo_por IS NOT NULL")).isZero();

        // A venda seguinte já sai em contingência, com o DANFE de contingência.
        Carrinho outra = carrinho(ARROZ, "1");
        NfceTerminalService.Emissao segunda = pdv.emitir(admin, vendas.finalizar(admin, outra,
                emDinheiro(outra, "10.00", null)).nfceId());
        assertThat(segunda.contingencia()).isTrue();
        assertThat(sefaz.envios()).hasSize(1);
    }

    @Test
    void rejeitadaNaoTemDanfeEOrientaACorrecao() {
        sefaz.depois("778");
        Carrinho carrinho = carrinho(ARROZ, "1");
        VendaService.VendaFinalizada v = vendas.finalizar(admin, carrinho, emDinheiro(carrinho, "10.00", null));

        NfceTerminalService.Emissao e = pdv.emitir(admin, v.nfceId());

        assertThat(e.recusada()).isTrue();
        assertThat(e.danfes()).isEmpty();
        assertThat(e.mensagem()).contains("foi rejeitada: cStat 778", "continua registrada",
                "Fiscal › Notas fiscais");
        // A venda continua registrada; o Reimprimir volta ao comprovante não fiscal.
        assertThat(pdv.danfeDaVenda(admin, v.vendaId())).isEmpty();
        assertThat(vendas.comprovante(admin, v.vendaId()).texto()).contains("NÃO É DOCUMENTO FISCAL");
    }

    @Test
    void desligadaNadaMuda() throws Exception {
        pdv.ligar(admin, false);
        Carrinho carrinho = carrinho(BALA, "1"); // sem dados fiscais: passa
        VendaService.VendaFinalizada v = vendas.finalizar(admin, carrinho, emDinheiro(carrinho, "10.00",
                "529.982.247-24"));
        assertThat(v.nfceId()).isNull();
        assertThat(t.contar("SELECT count(*) FROM nfce")).isZero();
        assertThat(t.contar("SELECT count(*) FROM venda WHERE consumidor_documento IS NULL")).isEqualTo(1);
        assertThat(vendas.nfceLigada(admin)).isFalse();
    }

    // ----- Configurações › NFC-e -----

    @Test
    void soLigaComOEmitenteCompletoEOCertificadoNaValidade() throws Exception {
        pdv.ligar(admin, false);
        terminal.gravar("", "");
        assertThatThrownBy(() -> pdv.ligar(admin, true)).isInstanceOf(NegocioException.class)
                .hasMessageStartingWith("Para ligar a NFC-e, este terminal precisa do certificado digital");
        terminal.gravar(CertificadoTeste.arquivo().toString(), CertificadoTeste.SENHA);
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comEmitente(new Configuracoes.Emitente("Empresa Teste Ltda", "", "100", "",
                "Centro", "14010100", "Ribeirão Preto", "3543402")));
        assertThatThrownBy(() -> pdv.ligar(admin, true)).hasMessageStartingWith("Para ligar a NFC-e, complete em "
                + "Configurações › Loja");
        assertThat(vendas.nfceLigada(admin)).isFalse();

        t.configuracoes.salvar(admin, t.configuracoes.carregar(admin).comEmitente(c.emitente()));
        pdv.ligar(admin, true);
        assertThat(vendas.nfceLigada(admin)).isTrue();
        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA)).anyMatch(a -> a.contains("NFC-e ligada"))
                .anyMatch(a -> a.contains("NFC-e desligada"));
    }

    @Test
    void testarComunicacaoMostraOCstatOuOProblemaEmLinguagemSimples() {
        NfceTerminalService.TesteComunicacao ok = pdv.testarComunicacao(admin);
        assertThat(ok.emOperacao()).isTrue();
        assertThat(ok.mensagem()).contains("cStat 107", "em operação");

        sefaz.cair();
        assertThat(pdv.testarComunicacao(admin).mensagem()).isEqualTo("Sem comunicação com a SEFAZ: SEFAZ falsa fora "
                + "do ar.");
        sefaz.voltar();

        terminal.gravar(CertificadoTeste.arquivo().toString(), "senha errada");
        NfceTerminalService.TesteComunicacao senha = pdv.testarComunicacao(admin);
        assertThat(senha.emOperacao()).isFalse();
        assertThat(senha.mensagem()).containsIgnoringCase("senha").doesNotContain("senha errada",
                CertificadoTeste.arquivo().toString());

        terminal.gravar(CertificadoTeste.vencido().toString(), CertificadoTeste.SENHA);
        assertThat(pdv.testarComunicacao(admin).mensagem()).contains("vencido");

        terminal.gravar("", "");
        assertThat(pdv.testarComunicacao(admin).mensagem()).contains("não tem o certificado");
    }

    @Test
    void cnpjDoCertificadoDiferenteDoDaLojaApareceNoTeste() {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja("Mercado Bom Preço", "11222333000181",
                "Rua das Flores, 100", "1633334444", RegimeTributario.SIMPLES, "SP", "123456789012")));
        assertThat(pdv.testarComunicacao(admin).mensagem()).contains("não é o da loja");
    }
}
