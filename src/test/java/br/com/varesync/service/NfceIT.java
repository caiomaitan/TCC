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
import br.com.varesync.model.FormaPagamento;
import br.com.varesync.model.InfoCertificado;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoDesconto;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.model.TipoValorBalanca;
import br.com.varesync.service.nfce.AssinaturaNfce;
import br.com.varesync.service.nfce.CertificadoTeste;
import br.com.varesync.service.nfce.ChaveAcesso;
import br.com.varesync.service.nfce.GeracaoNfce;
import br.com.varesync.service.nfce.MontagemNfce;
import br.com.varesync.service.nfce.ValidacaoXsd;
import br.com.varesync.service.nfce.XmlNfceTeste;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NFC-e, parte A (F23.1 a F23.3), pelo banco: a reserva do número, o XML de uma venda de verdade (desconto, item de
 * balança, embalagem com preço próprio e dois pagamentos) assinado e validado pelo XSD, a recusa local (REJEITADA, sem
 * ir à SEFAZ), o certificado do terminal com a auditoria e as regras do banco (homologação, uma NFC-e vigente por
 * venda, {@code vw_nfce_inconsistente}).
 */
class NfceIT extends IntegracaoBase {

    private static final String ARROZ = "7891234567895";
    private static final String REFRI = "7891000315507";
    private static final String CAIXA_REFRI = "17891000315504";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    @TempDir
    static Path pasta;

    private AcessoTeste t;
    private Sessao admin;
    private CertificadoEmMemoria terminal;
    private CertificadoService certificados;
    private NfceService nfce;
    private Transacao transacao;

    /** O certificado do terminal sem o config.properties. */
    static final class CertificadoEmMemoria implements CertificadoDoTerminal {
        String caminho = "";
        String senha = "";

        @Override
        public String caminho() {
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

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        transacao = new Transacao(dataSource);
        terminal = new CertificadoEmMemoria();
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), t.relogio, AcessoTeste.TERMINAL);
        certificados = new CertificadoService(transacao, new ConfiguracaoDAO(), auditoria, terminal, t.relogio);
        ValidacaoXsd validacao = new ValidacaoXsd(pasta);
        nfce = new NfceService(transacao, new NfceDAO(), new NumeracaoNfceDAO(), new TributoVendaDAO(),
                new ConfiguracaoDAO(), new GeracaoNfce(new MontagemNfce(FUSO), new AssinaturaNfce(validacao.pasta(),
                FUSO), validacao), certificados, "2.0.0-SNAPSHOT", new Random(7), t.relogio, FUSO);
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja("Mercado Bom Preço", "12345678000195",
                        "Rua das Flores, 100", "1633334444", RegimeTributario.SIMPLES, "SP", "123456789012"))
                .comEmitente(new Configuracoes.Emitente("Empresa Teste Ltda", "Rua das Flores", "100", "",
                        "Centro", "14010100", "Ribeirão Preto", "3543402"))
                .comBalanca(new Configuracoes.Balanca(true, 2, 5, TipoValorBalanca.PRECO)));
        t.caixa.abrir(admin, v("100.00"));
    }

    private void executar(String sql, Object... parametros) throws SQLException {
        try (Connection con = dataSource.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setObject(i + 1, parametros[i]);
            }
            ps.executeUpdate();
        }
    }

    private FormaPagamento forma(TipoFormaPagamento tipo) {
        return t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == tipo).findFirst().orElseThrow();
    }

    /** Produtos com NCM, origem e o perfil do Simples (CSOSN 102), o queijo pesável e a caixa de 12 refrigerantes. */
    private void produtos(boolean comFiscal) throws SQLException {
        long perfil = t.fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples("Simples tributado", "102"));
        long arroz = t.produtos.criar(admin, new DadosProduto("Arroz tipo 1 5 kg", null, "UN", v("5.49"), v("3.00"),
                null, null, List.of(new CodigoBarras(ARROZ, true)), v("100")));
        long queijo = t.produtos.criar(admin, new DadosProduto("Queijo muçarela", null, "KG", v("39.90"),
                v("28.00"), null, 123, List.of(), v("5")));
        long refri = t.produtos.criar(admin, new DadosProduto("Refrigerante cola 350 ml", null, "UN", v("5.49"),
                v("3.20"), null, null, List.of(new CodigoBarras(REFRI, true), new CodigoBarras(CAIXA_REFRI, false)),
                v("100")));
        executar("UPDATE produto_codigo_barras SET quantidade = 12, preco_embalagem = 59.90 WHERE codigo = ?",
                CAIXA_REFRI);
        if (comFiscal) {
            t.fiscal.salvarDadosFiscais(admin, arroz, new DadosFiscais("10063021", null, 0, perfil));
            t.fiscal.salvarDadosFiscais(admin, queijo, new DadosFiscais("04061010", null, 0, perfil));
        }
        t.fiscal.salvarDadosFiscais(admin, refri, new DadosFiscais("22021000", null, 0, perfil));
    }

    private void ler(Carrinho carrinho, String digitado) {
        EntradaPdv lida = EntradaPdv.interpretar(digitado);
        VendaService.LeituraProduto leitura = t.vendas.lerProduto(admin, lida.codigo());
        if (leitura.etiqueta() != null) {
            t.vendas.adicionarEtiqueta(admin, carrinho, leitura);
        } else if (leitura.embalagem() != null) {
            t.vendas.adicionarEmbalagem(admin, carrinho, leitura, lida.quantidade());
        } else {
            t.vendas.adicionarItem(admin, carrinho, leitura.produto(), lida.quantidade());
        }
    }

    /**
     * 2 × arroz (R$ 10,98), 0,345 kg de queijo pela etiqueta (R$ 13,77), a caixa de 12 refrigerantes (R$ 59,90) e
     * R$ 1,00 de desconto na venda: total de R$ 83,65, com R$ 40,00 no cartão de débito e R$ 50,00 em dinheiro.
     */
    private long vender() {
        Carrinho carrinho = new Carrinho();
        ler(carrinho, "2*" + ARROZ);
        ler(carrinho, "2001230013776");
        ler(carrinho, CAIXA_REFRI);
        t.vendas.aplicarDescontoVenda(admin, carrinho, TipoDesconto.VALOR, v("1.00"), null);
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(forma(TipoFormaPagamento.CARTAO_DEBITO), v("40.00"));
        r.adicionar(forma(TipoFormaPagamento.DINHEIRO), v("50.00"));
        return t.vendas.finalizar(admin, carrinho, r).vendaId();
    }

    private long reservar(long venda) {
        return transacao.executar(con -> nfce.reservar(con, venda, TipoEmissaoNfce.NORMAL,
                OffsetDateTime.now(t.relogio), null));
    }

    private void comCertificado() {
        terminal.gravar(CertificadoTeste.arquivo().toString(), CertificadoTeste.SENHA);
    }

    // ----- Aceite da parte A -----

    @Test
    void vendaComDescontoBalancaEmbalagemEDoisPagamentosValidaNoXsd() throws Exception {
        produtos(true);
        comCertificado();
        long venda = vender();
        assertThat(t.contar("SELECT valor_total * 100 FROM venda WHERE id = " + venda)).isEqualTo(8365);
        long id = reservar(venda);

        NfceGravada n = nfce.prepararXml(id);

        assertThat(n.status()).as(n.motivo()).isEqualTo(StatusNfce.PENDENTE);
        assertThat(n.numero()).isEqualTo(1);
        assertThat(ChaveAcesso.valida(n.chave())).isTrue();
        assertThat(n.chave()).startsWith("352610" + "12345678000195" + "65" + "001" + "000000001" + "1");
        assertThat(n.chave().substring(35, 43)).isEqualTo(n.codigoNumerico());
        String xml = n.xml();
        assertThat(XmlNfceTeste.assinaturaConfere(xml, XmlNfceTeste.certificadoDeTeste().getPublicKey())).isTrue();
        assertThat(XmlNfceTeste.texto(xml, "vNF")).isEqualTo("83.65");
        assertThat(XmlNfceTeste.todos(xml, "vProd")).containsExactly("10.98", "13.77", "59.90", "84.65");
        assertThat(XmlNfceTeste.todos(xml, "vDesc").get(3)).as("o total dos descontos").isEqualTo("1.00");
        List<String> q = XmlNfceTeste.todos(xml, "qCom");
        List<String> u = XmlNfceTeste.todos(xml, "vUnCom");
        List<String> p = XmlNfceTeste.todos(xml, "vProd");
        for (int i = 0; i < 3; i++) {
            assertThat(new BigDecimal(u.get(i)).multiply(new BigDecimal(q.get(i))).setScale(2, RoundingMode.HALF_UP))
                    .as("item %d", i + 1).isEqualByComparingTo(p.get(i));
        }
        assertThat(XmlNfceTeste.todos(xml, "cEAN")).containsExactly(ARROZ, "SEM GTIN", REFRI);
        assertThat(XmlNfceTeste.todos(xml, "xProd").get(0)).isEqualTo(MontagemNfce.DESCRICAO_HOMOLOGACAO);
        assertThat(XmlNfceTeste.todos(xml, "tPag")).containsExactly("04", "01");
        assertThat(XmlNfceTeste.todos(xml, "vPag")).containsExactly("40.00", "50.00");
        assertThat(XmlNfceTeste.texto(xml, "vTroco")).isEqualTo("6.35");
        assertThat(n.qrcode()).endsWith("?p=" + n.chave() + "|3|2");
        assertThat(XmlNfceTeste.texto(xml, "qrCode")).isEqualTo(n.qrcode());
        // a validação de novo, pelo XML gravado
        ValidacaoXsd validacao = new ValidacaoXsd(pasta);
        assertThat(validacao.erros(AssinaturaNfce.loteCom(xml, "1"), ValidacaoXsd.XSD_LOTE)).isEmpty();
    }

    @Test
    void contingenciaComOCpfNaNota() throws Exception {
        produtos(true);
        comCertificado();
        long venda = vender();
        executar("UPDATE venda SET consumidor_documento = '52998224725' WHERE id = ?", venda);
        OffsetDateTime agora = OffsetDateTime.now(t.relogio);
        long id = transacao.executar(con -> nfce.reservar(con, venda, TipoEmissaoNfce.CONTINGENCIA_OFFLINE, agora,
                agora));
        NfceGravada n = nfce.prepararXml(id);
        assertThat(n.status()).as(n.motivo()).isEqualTo(StatusNfce.CONTINGENCIA);
        assertThat(n.chave().charAt(34)).isEqualTo('9');
        assertThat(n.qrcode().split("\\|", -1)).hasSize(8);
        assertThat(XmlNfceTeste.assinaturaQrCodeConfere(n.qrcode(),
                XmlNfceTeste.certificadoDeTeste().getPublicKey())).isTrue();
        assertThat(XmlNfceTeste.texto(n.xml(), "CPF")).isEqualTo("52998224725");
        assertThat(XmlNfceTeste.texto(n.xml(), "xJust")).isEqualTo(NfceService.JUSTIFICATIVA_CONTINGENCIA);
    }

    @Test
    void produtoSemDadosFiscaisDeixaANfceRejeitadaSemIrASefaz() throws Exception {
        produtos(false);
        comCertificado();
        long venda = vender();
        NfceGravada n = nfce.prepararXml(reservar(venda));
        assertThat(n.status()).isEqualTo(StatusNfce.REJEITADA);
        assertThat(n.cstat()).as("sem cStat: não foi à SEFAZ").isNull();
        assertThat(n.motivo()).contains("Arroz tipo 1 5 kg").contains("dados fiscais");
        assertThat(n.xml()).isNull();
        assertThatThrownBy(() -> nfce.prepararXml(n.id())).isInstanceOf(NegocioException.class)
                .hasMessageContaining("rejeitada");
    }

    @Test
    void emitenteIncompletoTambemRejeita() throws Exception {
        produtos(true);
        comCertificado();
        long venda = vender();
        long id = reservar(venda);
        executar("UPDATE configuracao SET valor = '' WHERE chave = 'empresa.razao_social'");
        NfceGravada n = nfce.prepararXml(id);
        assertThat(n.status()).isEqualTo(StatusNfce.REJEITADA);
        assertThat(n.motivo()).contains("a razão social");
    }

    @Test
    void semCertificadoNadaMudaEAMensagemExplica() throws Exception {
        produtos(true);
        long id = reservar(vender());
        assertThatThrownBy(() -> nfce.prepararXml(id)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("não tem o certificado digital");
        terminal.gravar(CertificadoTeste.vencido().toString(), CertificadoTeste.SENHA);
        assertThatThrownBy(() -> nfce.prepararXml(id)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("vencido");
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE status = 'PENDENTE' AND xml IS NULL")).isEqualTo(1);
    }

    @Test
    void reservasSeguidasPorSerieECnfDiferenteDoNumero() throws Exception {
        produtos(true);
        long venda1 = vender();
        long venda2 = vender();
        reservar(venda1);
        reservar(venda2);
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE numero IN (1, 2) AND serie = 1")).isEqualTo(2);
        assertThat(t.contar("SELECT proximo_numero FROM nfce_numeracao WHERE serie = 1")).isEqualTo(3);
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE codigo_numerico = lpad(numero::text, 8, '0')"))
                .isZero();
        // a série nova começa do 1
        executar("UPDATE configuracao SET valor = '5' WHERE chave = 'nfce.serie'");
        int primeiro = transacao.executar(con -> nfce.reservarNumero(con, 5));
        assertThat(primeiro).isEqualTo(1);
    }

    // ----- Banco (F23.1) -----

    @Test
    void soHomologacaoEUmaNfceVigentePorVenda() throws Exception {
        produtos(true);
        long venda = vender();
        long id = reservar(venda);
        assertThatThrownBy(() -> executar("UPDATE nfce SET ambiente = 1 WHERE id = ?", id))
                .hasMessageContaining("ck_nfce_ambiente");
        assertThatThrownBy(() -> reservar(venda)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("Esta venda já tem uma NFC-e");
        // a original sem resposta e a de contingência podem conviver
        executar("UPDATE nfce SET status = 'SEM_RESPOSTA' WHERE id = ?", id);
        OffsetDateTime agora = OffsetDateTime.now(t.relogio);
        transacao.executar(con -> nfce.reservar(con, venda, TipoEmissaoNfce.CONTINGENCIA_OFFLINE, agora, agora));
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE venda_id = " + venda)).isEqualTo(2);
        assertThatThrownBy(() -> executar("UPDATE venda SET consumidor_documento = '123' WHERE id = ?", venda))
                .hasMessageContaining("ck_venda_consumidor_documento");
    }

    @Test
    void viewDeInconsistenciaAcusaAutorizadaDeVendaCancelada() throws Exception {
        produtos(true);
        long venda = vender();
        long id = reservar(venda);
        assertThat(nfce.diagnostico(admin)).isEmpty();
        executar("UPDATE nfce SET status = 'AUTORIZADA', protocolo = '135260000000001', autorizada_em = now() "
                + "WHERE id = ?", id);
        t.vendas.cancelar(admin, venda, "Teste da view de inconsistência", null);
        assertThat(nfce.diagnostico(admin)).containsExactly("NFC-e nº 1 (série 1), venda nº " + venda
                + ": NFC-e autorizada de venda cancelada");
        executar("UPDATE nfce SET status = 'CANCELADA' WHERE id = ?", id);
        assertThat(nfce.diagnostico(admin)).isEmpty();
    }

    // ----- Certificado do terminal (F23.2) -----

    @Test
    void certificadoDoTerminalComAuditoriaSemCaminhoNemSenha() throws Exception {
        assertThat(certificados.atual(admin)).isEmpty();
        assertThatThrownBy(() -> certificados.salvar(admin, CertificadoTeste.arquivo().toString(), "errada"))
                .isInstanceOf(NegocioException.class).hasMessage("A senha do certificado não confere.");
        assertThat(terminal.caminho()).isEmpty();

        InfoCertificado info = certificados.salvar(admin, CertificadoTeste.arquivo().toString(),
                CertificadoTeste.SENHA);

        assertThat(info.titular()).isEqualTo("EMPRESA TESTE LTDA");
        assertThat(info.cnpj()).isEqualTo("12345678000195");
        assertThat(info.raizConfere()).isTrue();
        assertThat(info.vencido()).isFalse();
        assertThat(info.problema()).isEmpty();
        assertThat(terminal.caminho()).isEqualTo(CertificadoTeste.arquivo().toAbsolutePath().toString());
        assertThat(certificados.atual(admin)).contains(info);
        List<String> auditoria = t.auditoria(AcaoAuditoria.NFCE_CERTIFICADO_ALTERADO);
        assertThat(auditoria).singleElement().satisfies(d -> assertThat(d)
                .contains("EMPRESA TESTE LTDA").contains("12.345.678/0001-95")
                .doesNotContain(CertificadoTeste.SENHA).doesNotContain(CertificadoTeste.arquivo().getFileName()
                        .toString()));
        assertThat(t.contar("SELECT count(*) FROM configuracao WHERE valor LIKE '%" + CertificadoTeste.SENHA + "%' "
                + "OR valor LIKE '%.pfx%'")).as("nada do certificado no banco").isZero();

        certificados.remover(admin);
        assertThat(terminal.caminho()).isEmpty();
        assertThat(t.auditoria(AcaoAuditoria.NFCE_CERTIFICADO_ALTERADO)).hasSize(2);
    }

    @Test
    void certificadoDeOutraEmpresaAparece() throws Exception {
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja(c.loja().nome(), "11222333000181", "", "",
                RegimeTributario.SIMPLES, "SP", "123456789012")));
        InfoCertificado info = certificados.conferir(admin, CertificadoTeste.arquivo().toString(),
                CertificadoTeste.SENHA);
        assertThat(info.raizConfere()).isFalse();
        assertThat(info.problema()).contains("O CNPJ do certificado não é o da loja.");
    }

    @Test
    void soOSupervisorMexeNoCertificado() throws Exception {
        t.usuarios.criar(admin, "caixa", "Operador", false, java.util.EnumSet.of(
                br.com.varesync.model.Permissao.PDV), "inicial1", "inicial1");
        Sessao operador = t.acesso.entrar("caixa", "inicial1");
        assertThatThrownBy(() -> certificados.salvar(operador, CertificadoTeste.arquivo().toString(),
                CertificadoTeste.SENHA)).isInstanceOf(NegocioException.class);
    }

    // ----- Configurações (F23.2, seção 0.4) -----

    @Test
    void enderecoDoEmitenteENfceSalvosComAuditoria() throws Exception {
        Configuracoes c = t.configuracoes.carregar(admin);
        assertThat(c.emitente().cep()).isEqualTo("14010100");
        assertThat(c.nfce()).isEqualTo(Configuracoes.Nfce.PADRAO);
        t.configuracoes.salvar(admin, c.comNfce(new Configuracoes.Nfce(false, 2, 20, 15, 3, v("5000"),
                v("150000"), false, 45, "", "https://consulta.exemplo.gov.br/nfce",
                new Configuracoes.RespTecnico("11.222.333/0001-81", "Fulano", "a@b.com.br", "(16) 99999-8888"))));
        Configuracoes gravada = t.configuracoes.carregar(admin);
        assertThat(gravada.nfce().serie()).isEqualTo(2);
        assertThat(gravada.nfce().respTecnico().cnpj()).isEqualTo("11222333000181");
        assertThat(t.auditoria(AcaoAuditoria.CONFIGURACAO_ALTERADA)).last().satisfies(d -> assertThat(d)
                .contains("série da NFC-e de 1 para 2").contains("CNPJ do responsável técnico"));
    }
}
