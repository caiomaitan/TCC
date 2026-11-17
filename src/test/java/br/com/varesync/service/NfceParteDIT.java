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
import br.com.varesync.model.AlertasNfce;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.NotaFiscal;
import br.com.varesync.model.Permissao;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.report.Filtros;
import br.com.varesync.report.RelNfceEmitidas;
import br.com.varesync.report.ResultadoRelatorio;
import br.com.varesync.service.nfce.AssinaturaNfce;
import br.com.varesync.service.nfce.CertificadoTeste;
import br.com.varesync.service.nfce.GeracaoNfce;
import br.com.varesync.service.nfce.MontagemNfce;
import br.com.varesync.service.nfce.SefazFalsa;
import br.com.varesync.service.nfce.ValidacaoXsd;
import br.com.varesync.service.nfce.XmlNfceTeste;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NFC-e, parte D (F23.8 a F23.10), com a SEFAZ falsa: o cancelamento da venda com NFC-e e os prazos, a aba Notas
 * fiscais (lista, transmitir agora, corrigir e reenviar, inutilizar números, ZIP do mês), o XML completo dos eventos e
 * das inutilizações (procEventoNFe e procInutNFe, também os do transmissor), o aviso de contingência, os alertas do
 * painel e o R32.
 */
class NfceParteDIT extends IntegracaoBase {

    private static final String ARROZ = "7891234567895";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final String MOTIVO = "Cliente desistiu da compra no caixa";

    @TempDir
    static Path pasta;

    private AcessoTeste t;
    private Sessao admin;
    private VendaService vendas;
    private NfceTerminalService pdv;
    private EmissaoNfceService emissao;
    private TransmissorNfce transmissor;
    private NotasFiscaisService notas;
    private SefazFalsa sefaz;
    private Transacao transacao;
    private ValidacaoXsd validacao;
    private PublicKey chavePublica;
    private final NfceDAO dao = new NfceDAO();
    private long arroz;

    /** O certificado do terminal em memória. */
    static final class Terminal implements CertificadoDoTerminal {
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

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        transacao = new Transacao(dataSource);
        Terminal terminal = new Terminal();
        terminal.gravar(CertificadoTeste.arquivo().toString(), CertificadoTeste.SENHA);
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), t.relogio, AcessoTeste.TERMINAL);
        CertificadoService certificados = new CertificadoService(transacao, new ConfiguracaoDAO(), auditoria, terminal,
                t.relogio);
        validacao = new ValidacaoXsd(pasta);
        chavePublica = XmlNfceTeste.certificadoDeTeste().getPublicKey();
        NfceService nfce = new NfceService(transacao, dao, new NumeracaoNfceDAO(), new TributoVendaDAO(),
                new ConfiguracaoDAO(), new GeracaoNfce(new MontagemNfce(FUSO), new AssinaturaNfce(validacao.pasta(),
                FUSO), validacao), certificados, "2.0.0-SNAPSHOT", new Random(7), t.relogio, FUSO);
        sefaz = new SefazFalsa(t.relogio, pasta);
        emissao = new EmissaoNfceService(transacao, dao, nfce, certificados, new ConfiguracaoDAO(), sefaz,
                new ModoContingencia(), auditoria, t.relogio, FUSO, AcessoTeste.TERMINAL);
        transmissor = new TransmissorNfce(transacao, dao, emissao, certificados, sefaz, auditoria, t.relogio,
                AcessoTeste.TERMINAL);
        pdv = new NfceTerminalService(transacao, dao, new ConfiguracaoDAO(), new TributoVendaDAO(), emissao,
                certificados, sefaz, auditoria);
        vendas = t.vendasCom(new NfceNaVenda(new ConfiguracaoDAO(), new TributoVendaDAO(), certificados, nfce,
                new CancelamentoNfceService(dao, new ConfiguracaoDAO(), emissao, certificados, sefaz, auditoria,
                        t.relogio)));
        notas = new NotasFiscaisService(transacao, dao, new NumeracaoNfceDAO(), new ConfiguracaoDAO(),
                new TributosVendaService(new TributoVendaDAO(), new ConfiguracaoDAO()), emissao, transmissor, pdv,
                certificados, sefaz, auditoria, t.relogio, FUSO);

        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja("Mercado Bom Preço", CertificadoTeste.CNPJ,
                        "Rua das Flores, 100", "1633334444", RegimeTributario.SIMPLES, "SP", "123456789012"))
                .comEmitente(new Configuracoes.Emitente("Empresa Teste Ltda", "Rua das Flores", "100", "", "Centro",
                        "14010100", "Ribeirão Preto", "3543402")));
        long perfil = t.fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples("Simples tributado", "102"));
        arroz = t.produtos.criar(admin, new DadosProduto("Arroz tipo 1 5 kg", null, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.00"), null, null, List.of(new CodigoBarras(ARROZ, true)), new BigDecimal("100")));
        t.fiscal.salvarDadosFiscais(admin, arroz, new DadosFiscais("10063021", null, 0, perfil));
        t.caixa.abrir(admin, new BigDecimal("100.00"));
        pdv.ligar(admin, true);
    }

    // ----- Apoio -----

    /** Uma venda de 2 arroz em dinheiro, já emitida pelo PDV: a NFC-e que vale para a venda. */
    private NfceGravada vender() {
        Carrinho carrinho = new Carrinho();
        vendas.adicionarItem(admin, carrinho, vendas.lerProduto(admin, ARROZ).produto(), new BigDecimal("2"));
        Recebimento r = vendas.recebimento(admin, carrinho);
        r.adicionar(vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), new BigDecimal("20.00"));
        VendaService.VendaFinalizada v = vendas.finalizar(admin, carrinho, r);
        return pdv.emitir(admin, v.nfceId()).nfce();
    }

    private NfceGravada nota(long id) {
        return transacao.executar(con -> dao.buscar(con, id, false)).orElseThrow();
    }

    private String statusVenda(long vendaId) throws Exception {
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT status FROM venda WHERE id = ?")) {
            ps.setLong(1, vendaId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String texto(String sql) throws Exception {
        try (Connection con = dataSource.getConnection(); var st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void avancar(Duration d) {
        t.relogio.avancar(d);
    }

    // ----- F23.8 Cancelamento -----

    @Test
    void cancelarAos29MinutosCancelaANfceEDepoisAVenda() throws Exception {
        NfceGravada n = vender();
        assertThat(n.status()).isEqualTo(StatusNfce.AUTORIZADA);
        avancar(Duration.ofMinutes(29));

        vendas.cancelar(admin, n.vendaId(), MOTIVO, null);

        assertThat(nota(n.id()).status()).isEqualTo(StatusNfce.CANCELADA);
        assertThat(nota(n.id()).protocolo()).isEqualTo(n.protocolo()); // o de autorização continua
        assertThat(statusVenda(n.vendaId())).isEqualTo("CANCELADA");
        assertThat(sefaz.eventos()).containsExactly("110111 " + n.chave());
        // O evento gravado é o procEventoNFe: o pedido assinado com o retorno, válido no XSD.
        String xml = texto("SELECT xml FROM nfce_evento WHERE tipo = 'CANCELAMENTO'");
        assertThat(validacao.erros(xml, "procEventoCancNFe_v1.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(xml, chavePublica, "infEvento")).isTrue();
        assertThat(XmlNfceTeste.texto(xml, "xJust")).isEqualTo(MOTIVO);
        assertThat(texto("SELECT justificativa || '|' || cstat || '|' || (usuario_id IS NOT NULL) FROM nfce_evento"))
                .isEqualTo(MOTIVO + "|135|true");
        assertThat(t.auditoria(AcaoAuditoria.NFCE_CANCELADA)).singleElement().asString()
                .startsWith("NFC-e nº " + n.numero() + " (série 1) da venda nº " + n.vendaId()
                        + " cancelada pelo evento 110111 (cStat 135, protocolo ").endsWith("Motivo: " + MOTIVO);
        assertThat(t.auditoria(AcaoAuditoria.VENDA_CANCELADA)).hasSize(1);
        assertThat(t.contar("SELECT count(*) FROM vw_nfce_inconsistente")).isZero();
    }

    @Test
    void aos31MinutosRecusaEOrientaADevolucao() throws Exception {
        NfceGravada n = vender();
        avancar(Duration.ofMinutes(31));

        assertThatThrownBy(() -> vendas.cancelar(admin, n.vendaId(), MOTIVO, null)).isInstanceOf(NegocioException.class)
                .hasMessage("A NFC-e foi autorizada há mais de 30 minutos e não pode mais ser cancelada. Registre uma "
                        + "devolução.");
        // A conferência do F9 dá a mesma recusa, sem ir à SEFAZ.
        assertThatThrownBy(() -> vendas.conferirCancelamento(admin, n.vendaId(), MOTIVO, null))
                .hasMessageContaining("mais de 30 minutos");
        assertThat(sefaz.eventos()).isEmpty();
        assertThat(statusVenda(n.vendaId())).isEqualTo("FINALIZADA");
        assertThat(nota(n.id()).status()).isEqualTo(StatusNfce.AUTORIZADA);
    }

    @Test
    void naConferenciaAJustificativaCurtaERecusadaEOF9SabeQueVaiASefaz() {
        NfceGravada n = vender();
        assertThatThrownBy(() -> vendas.conferirCancelamento(admin, n.vendaId(), "Desistiu", null))
                .hasMessageStartingWith("Para cancelar a NFC-e, o motivo precisa ter de 15 a 255 caracteres");
        assertThat(vendas.conferirCancelamento(admin, n.vendaId(), MOTIVO, null)).isTrue();
        assertThat(nota(n.id()).status()).isEqualTo(StatusNfce.AUTORIZADA); // a conferência não muda nada
    }

    @Test
    void vendaComNotaEmContingenciaOuPendenteNaoCancela() throws Exception {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        NfceGravada contingencia = vender();
        assertThat(contingencia.status()).isEqualTo(StatusNfce.CONTINGENCIA);
        assertThatThrownBy(() -> vendas.cancelar(admin, contingencia.vendaId(), MOTIVO, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("sem resposta da SEFAZ");
        assertThat(statusVenda(contingencia.vendaId())).isEqualTo("FINALIZADA");

        // A de contingência, sozinha na venda (o terminal no modo, sem a original sem resposta):
        NfceGravada outra = vender();
        assertThat(outra.status()).isEqualTo(StatusNfce.CONTINGENCIA);
        assertThatThrownBy(() -> vendas.cancelar(admin, outra.vendaId(), MOTIVO, null))
                .hasMessageContaining("foi emitida em contingência e ainda não foi autorizada")
                .hasMessageContaining("Transmitir agora");

        // Autorizada pelo transmissor, cancela.
        sefaz.voltar();
        transmissor.transmitir(admin);
        assertThat(nota(outra.id()).status()).isEqualTo(StatusNfce.AUTORIZADA);
        vendas.cancelar(admin, outra.vendaId(), MOTIVO, null);
        assertThat(nota(outra.id()).status()).isEqualTo(StatusNfce.CANCELADA);
    }

    @Test
    void sefazQueRecusaOuNaoRespondeNaoCancelaAVenda() throws Exception {
        NfceGravada n = vender();
        sefaz.depoisNoEvento("580", SefazFalsa.Acao.SEM_RESPOSTA);

        assertThatThrownBy(() -> vendas.cancelar(admin, n.vendaId(), MOTIVO, null))
                .hasMessage("A SEFAZ recusou o cancelamento da NFC-e nº " + n.numero() + " (cStat 580 - Rejeicao: "
                        + "Codigo de teste 580): a venda não foi cancelada.");
        assertThatThrownBy(() -> vendas.cancelar(admin, n.vendaId(), MOTIVO, null))
                .hasMessageStartingWith("A SEFAZ não respondeu ao cancelamento da NFC-e nº " + n.numero())
                .hasMessageEndingWith("a venda não foi cancelada. Tente de novo em alguns minutos.");
        assertThat(statusVenda(n.vendaId())).isEqualTo("FINALIZADA");
        assertThat(t.contar("SELECT count(*) FROM nfce_evento")).isZero();
    }

    @Test
    void duplicidadeDeEventoNumNovoPedidoContaComoCancelada() throws Exception {
        NfceGravada n = vender();
        // O primeiro pedido chega à SEFAZ, mas a resposta se perde: a venda não é cancelada.
        sefaz.depoisNoEvento(SefazFalsa.Acao.SEM_RESPOSTA_MAS_AUTORIZOU);
        assertThatThrownBy(() -> vendas.cancelar(admin, n.vendaId(), MOTIVO, null)).isInstanceOf(NegocioException.class);
        assertThat(statusVenda(n.vendaId())).isEqualTo("FINALIZADA");

        // No novo pedido, a SEFAZ responde 573: conta como cancelada.
        vendas.cancelar(admin, n.vendaId(), MOTIVO, null);
        assertThat(nota(n.id()).status()).isEqualTo(StatusNfce.CANCELADA);
        assertThat(statusVenda(n.vendaId())).isEqualTo("CANCELADA");
        assertThat(texto("SELECT cstat FROM nfce_evento")).isEqualTo("573");
    }

    @Test
    void rejeitadaCancelaAVendaEOTransmissorInutilizaONumeroComOProcInut() throws Exception {
        sefaz.depois("539");
        NfceGravada n = vender();
        assertThat(n.status()).isEqualTo(StatusNfce.REJEITADA);

        vendas.cancelar(admin, n.vendaId(), "Desistiu", null); // sem evento: a justificativa curta vale para a venda
        assertThat(statusVenda(n.vendaId())).isEqualTo("CANCELADA");
        assertThat(sefaz.eventos()).isEmpty();

        transmissor.transmitir(admin);
        assertThat(nota(n.id()).status()).isEqualTo(StatusNfce.INUTILIZADA);
        String xml = texto("SELECT xml FROM nfce_inutilizacao");
        assertThat(xml).contains("<ProcInutNFe");
        assertThat(validacao.erros(xml, "procInutNFe_v4.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(xml, chavePublica, "infInut")).isTrue();
    }

    @Test
    void denegadaCancelaAVendaSemEvento() throws Exception {
        sefaz.depois("302");
        NfceGravada n = vender();
        assertThat(n.status()).isEqualTo(StatusNfce.DENEGADA);
        vendas.cancelar(admin, n.vendaId(), "Desistiu", null);
        assertThat(statusVenda(n.vendaId())).isEqualTo("CANCELADA");
        assertThat(sefaz.eventos()).isEmpty();
        assertThat(nota(n.id()).status()).isEqualTo(StatusNfce.DENEGADA);
        assertThat(t.contar("SELECT count(*) FROM vw_nfce_inconsistente")).isZero();
    }

    // ----- XML completo do transmissor (F23.9) e aviso da contingência -----

    @Test
    void transmissorGravaOProcEventoDaSubstituicaoEAvisaASaidaDaContingencia() throws Exception {
        List<Optional<OffsetDateTime>> avisos = new ArrayList<>();
        Runnable parar = pdv.observarContingencia(avisos::add);
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA_MAS_AUTORIZOU);
        NfceGravada contingencia = vender();
        assertThat(contingencia.status()).isEqualTo(StatusNfce.CONTINGENCIA);
        assertThat(avisos).hasSize(1);
        assertThat(avisos.get(0)).isPresent();

        transmissor.transmitir(admin);

        // O aviso do PDV some assim que o transmissor sai do modo.
        assertThat(avisos).hasSize(2);
        assertThat(avisos.get(1)).isEmpty();
        assertThat(pdv.contingenciaDesde()).isEmpty();
        parar.run();
        // O 110112 da original, que tinha chegado: o procEventoNFe.
        String xml = texto("SELECT xml FROM nfce_evento WHERE tipo = 'CANCELAMENTO_SUBSTITUICAO'");
        assertThat(validacao.erros(xml, "procEventoCancSubst_v1.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(xml, chavePublica, "infEvento")).isTrue();
        assertThat(XmlNfceTeste.texto(xml, "chNFeRef")).isEqualTo(nota(contingencia.id()).chave());
        assertThat(texto("SELECT count(*) FROM nfce_evento WHERE usuario_id IS NULL")).isEqualTo("1");
    }

    // ----- F23.9 Aba Notas fiscais -----

    @Test
    void listaComFiltroPorPeriodoSituacaoENumero() {
        NfceGravada autorizada = vender();
        sefaz.depois("539");
        NfceGravada rejeitada = vender();
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        NfceGravada contingencia = vender();
        LocalDate hoje = LocalDate.now(t.relogio);

        List<NotaFiscal> todas = notas.listar(admin, new NotasFiscaisService.Filtro(hoje, hoje, Set.of(), null));
        assertThat(todas).hasSize(4); // a original sem resposta também
        assertThat(notas.listar(admin, new NotasFiscaisService.Filtro(null, null, NotasFiscaisService.A_TRANSMITIR,
                null))).extracting(NotaFiscal::status).containsExactlyInAnyOrder(StatusNfce.SEM_RESPOSTA,
                StatusNfce.CONTINGENCIA);
        assertThat(notas.listar(admin, new NotasFiscaisService.Filtro(null, null, EnumSet.of(StatusNfce.REJEITADA),
                null))).singleElement().satisfies(n -> {
                    assertThat(n.id()).isEqualTo(rejeitada.id());
                    assertThat(n.mensagem()).isEqualTo("cStat 539 - Rejeicao: Codigo de teste 539");
                    assertThat(n.valor()).isEqualByComparingTo("10.98");
                    assertThat(n.consumidor()).isNull();
                });
        assertThat(notas.listar(admin, new NotasFiscaisService.Filtro(null, null, Set.of(), autorizada.numero())))
                .singleElement().extracting(NotaFiscal::protocolo).isEqualTo(autorizada.protocolo());
        assertThat(notas.listar(admin, new NotasFiscaisService.Filtro(hoje.minusDays(3), hoje.minusDays(1), Set.of(),
                null))).isEmpty();
        assertThat(contingencia.tipoEmissao()).isEqualTo(TipoEmissaoNfce.CONTINGENCIA_OFFLINE);
        assertThatThrownBy(() -> notas.listar(admin, new NotasFiscaisService.Filtro(hoje, hoje.minusDays(1), Set.of(),
                null))).hasMessage("A data final não pode ser anterior à inicial.");
    }

    @Test
    void semAPermissaoFiscalNaoVeAsNotasNemOsAlertas() throws Exception {
        t.usuarios.criar(admin, "caixa1", "Carlos Caixa", false, Set.of(Permissao.PDV, Permissao.RELATORIOS),
                "inicial1", "inicial1");
        try (Connection con = dataSource.getConnection(); var st = con.createStatement()) {
            st.executeUpdate("UPDATE usuario SET deve_trocar_senha = FALSE WHERE login = 'caixa1'");
        }
        Sessao caixa = t.acesso.entrar("caixa1", "inicial1");
        assertThatThrownBy(() -> notas.listar(caixa, new NotasFiscaisService.Filtro(null, null, Set.of(), null)))
                .isInstanceOf(NegocioException.class);
        assertThat(notas.alertas(caixa)).isEqualTo(AlertasNfce.NENHUM);
    }

    @Test
    void rejeitadaDeEmissaoNormalCorrigidaEReenviadaNoMesmoNumero() throws Exception {
        sefaz.depois("539");
        NfceGravada rejeitada = vender();
        assertThat(rejeitada.status()).isEqualTo(StatusNfce.REJEITADA);
        // O cadastro é corrigido (outro NCM) e o reenvio é feito uma hora depois.
        t.fiscal.salvarDadosFiscais(admin, arroz, new DadosFiscais("10063011", null, 0,
                t.fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples("Simples corrigido", "102"))));
        avancar(Duration.ofHours(1));

        EmissaoNfceService.Resultado r = notas.reenviar(admin, rejeitada.id());

        NfceGravada reenviada = r.nfce();
        assertThat(reenviada.status()).as(r.mensagem()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(reenviada.numero()).isEqualTo(rejeitada.numero());
        assertThat(reenviada.serie()).isEqualTo(rejeitada.serie());
        assertThat(reenviada.emitidaEm()).isAtSameInstantAs(OffsetDateTime.now(t.relogio)); // a emissão é agora
        assertThat(reenviada.emitidaEm()).isAfter(rejeitada.emitidaEm());
        assertThat(XmlNfceTeste.texto(reenviada.xml(), "NCM")).isEqualTo("10063011");
        assertThat(texto("SELECT ncm FROM venda_item_tributo")).isEqualTo("10063011"); // tributos recalculados
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE venda_id = " + rejeitada.vendaId())).isEqualTo(1);
        assertThat(t.auditoria(AcaoAuditoria.NFCE_REENVIADA)).singleElement().asString()
                .contains("corrigida e reenviada: emissão normal, com a data de emissão de agora e os tributos "
                        + "recalculados (1 item)").endsWith("Rejeição anterior: cStat 539 - Rejeicao: Codigo de "
                        + "teste 539");
        // Só a rejeitada é reenviada.
        assertThatThrownBy(() -> notas.reenviar(admin, reenviada.id())).isInstanceOf(NegocioException.class)
                .hasMessage("A NFC-e nº " + reenviada.numero() + " está autorizada: só a rejeitada é corrigida e "
                        + "reenviada.");
    }

    @Test
    void rejeitadaDeContingenciaMantemDataEValoresERecusaMudancaDeValor() throws Exception {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        NfceGravada contingencia = vender();
        sefaz.voltar();
        sefaz.depois("539");
        transmissor.transmitir(admin);
        NfceGravada rejeitada = nota(contingencia.id());
        assertThat(rejeitada.status()).isEqualTo(StatusNfce.REJEITADA);
        assertThat(rejeitada.tipoEmissao()).isEqualTo(TipoEmissaoNfce.CONTINGENCIA_OFFLINE);
        avancar(Duration.ofHours(2));

        // Uma tabela IBPT importada depois mudaria os tributos aproximados (um valor): recusa.
        t.fiscal.importarIbpt(admin, TabelaIbptTest.iso(TabelaIbptTest.CABECALHO
                + "10063021;;0;Arroz;13,45;15,45;18,00;0,00;01/10/2026;31/12/2026;X;26.2.A;IBPT\n"),
                "TabelaIBPTax.csv");
        assertThatThrownBy(() -> notas.reenviar(admin, rejeitada.id())).isInstanceOf(NegocioException.class)
                .hasMessageStartingWith("A NFC-e nº " + rejeitada.numero() + " é de contingência: a lei manda manter "
                        + "número, série, data de emissão e valores, e a correção mudaria o valor dos impostos (item 1 "
                        + "(Arroz tipo 1 5 kg): tributos aproximados)");
        assertThat(nota(rejeitada.id()).status()).isEqualTo(StatusNfce.REJEITADA);

        // Só o código: o NCM muda, e o reenvio vale, com a data de emissão e a chave mantidas.
        try (Connection con = dataSource.getConnection(); var st = con.createStatement()) {
            st.executeUpdate("DELETE FROM ibpt");
        }
        t.fiscal.salvarDadosFiscais(admin, arroz, new DadosFiscais("10063011", null, 0,
                t.fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples("Simples corrigido", "102"))));
        EmissaoNfceService.Resultado r = notas.reenviar(admin, rejeitada.id());
        assertThat(r.nfce().status()).as(r.mensagem()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(r.nfce().emitidaEm()).isEqualTo(rejeitada.emitidaEm());
        assertThat(r.nfce().contingenciaEm()).isEqualTo(rejeitada.contingenciaEm());
        assertThat(r.nfce().chave()).isEqualTo(rejeitada.chave());
        assertThat(XmlNfceTeste.texto(r.nfce().xml(), "NCM")).isEqualTo("10063011");
        assertThat(XmlNfceTeste.texto(r.nfce().xml(), "tpEmis")).isEqualTo("9");
    }

    @Test
    void reenvioComOTerminalEmContingenciaTentaAAutorizacao() {
        sefaz.depois("539");
        NfceGravada rejeitada = vender();
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        vender(); // o terminal entra no modo contingência
        assertThat(pdv.contingenciaDesde()).isPresent();

        EmissaoNfceService.Resultado r = notas.reenviar(admin, rejeitada.id());

        assertThat(r.nfce().status()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(r.nfce().tipoEmissao()).isEqualTo(TipoEmissaoNfce.NORMAL); // não virou contingência
        assertThat(r.nfce().numero()).isEqualTo(rejeitada.numero());
    }

    @Test
    void reenvioDaRejeitadaDeVendaCanceladaERecusado() {
        sefaz.depois("539");
        NfceGravada n = vender();
        vendas.cancelar(admin, n.vendaId(), "Desistiu", null);
        assertThatThrownBy(() -> notas.reenviar(admin, n.id())).hasMessageContaining("foi cancelada")
                .hasMessageContaining("o transmissor inutiliza o número");
    }

    @Test
    void inutilizacaoDeFaixaSemNfce() throws Exception {
        NfceGravada primeira = vender(); // nº 1
        String justificativa = "Numeros pulados na troca do sistema de caixa";

        NotasFiscaisService.Inutilizacao i = notas.inutilizar(admin, 1, 5, 7, justificativa);

        assertThat(i.protocolo()).isNotBlank();
        assertThat(sefaz.inutilizacoes()).containsExactly("1:5-7");
        String xml = texto("SELECT xml FROM nfce_inutilizacao");
        assertThat(validacao.erros(xml, "procInutNFe_v4.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(xml, chavePublica, "infInut")).isTrue();
        assertThat(texto("SELECT numero_inicial || '-' || numero_final || '|' || ano || '|' || (usuario_id IS NOT NULL)"
                + " FROM nfce_inutilizacao")).isEqualTo("5-7|26|true");
        assertThat(t.auditoria(AcaoAuditoria.NFCE_INUTILIZADA)).singleElement().asString()
                .startsWith("Números 5 a 7 da série 1 da NFC-e inutilizados (protocolo ");
        // A numeração pulou a faixa: a próxima venda recebe o 8.
        assertThat(vender().numero()).isEqualTo(8);

        // Recusas: número com NFC-e, faixa já inutilizada, justificativa curta.
        assertThatThrownBy(() -> notas.inutilizar(admin, 1, primeira.numero(), 2, justificativa))
                .hasMessageStartingWith("Há NFC-e com números dessa faixa");
        assertThatThrownBy(() -> notas.inutilizar(admin, 1, 7, 7, justificativa))
                .hasMessage("Algum número dessa faixa da série 1 já foi inutilizado.");
        assertThatThrownBy(() -> notas.inutilizar(admin, 1, 3, 4, "Curta"))
                .hasMessageStartingWith("A justificativa precisa ter de 15 a 255 caracteres");
        assertThat(sefaz.inutilizacoes()).hasSize(1);

        // Os números 2 a 4 foram pulados quando a nº 8 foi emitida (este mês): o prazo é o dia 10 do mês seguinte.
        LocalDate limite = YearMonth.from(LocalDate.now(t.relogio)).plusMonths(1).atDay(10);
        avancar(Duration.ofDays(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(t.relogio), limite) + 1));
        assertThatThrownBy(() -> notas.inutilizar(admin, 1, 2, 4, justificativa))
                .hasMessageStartingWith("O prazo para inutilizar esses números acabou em "
                        + br.com.varesync.util.Formatos.data(limite));
    }

    @Test
    void zipDoMesTemAsNotasOsEventosEAsInutilizacoes() throws Exception {
        NfceGravada autorizada = vender();
        NfceGravada cancelada = vender();
        vendas.cancelar(admin, cancelada.vendaId(), MOTIVO, null);
        sefaz.depois("539");
        vender(); // rejeitada: não vai para o ZIP
        notas.inutilizar(admin, 1, 10, 10, "Numero pulado na troca do sistema de caixa");
        Path zip = pasta.resolve("nfce-mes.zip");

        NotasFiscaisService.ExportacaoXml x = notas.exportarMes(admin, YearMonth.now(t.relogio), zip);

        assertThat(x).isEqualTo(new NotasFiscaisService.ExportacaoXml(2, 1, 1));
        Map<String, String> arquivos = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                ByteArrayOutputStream conteudo = new ByteArrayOutputStream();
                in.transferTo(conteudo);
                arquivos.put(e.getName(), conteudo.toString(StandardCharsets.UTF_8));
            }
        }
        assertThat(arquivos).containsKeys("NFC-e/" + autorizada.chave() + "-procNFe.xml",
                "NFC-e/" + cancelada.chave() + "-procNFe.xml");
        assertThat(arquivos.get("NFC-e/" + autorizada.chave() + "-procNFe.xml")).contains("<nfeProc", "<protNFe");
        assertThat(arquivos.keySet()).filteredOn(k -> k.startsWith("Eventos/")).singleElement().asString()
                .startsWith("Eventos/" + cancelada.chave() + "-110111-");
        String evento = arquivos.entrySet().stream().filter(e -> e.getKey().startsWith("Eventos/")).findFirst()
                .orElseThrow().getValue();
        assertThat(validacao.erros(evento, "procEventoCancNFe_v1.00.xsd")).isEmpty();
        String inut = arquivos.entrySet().stream().filter(e -> e.getKey().startsWith("Inutilizacoes/")).findFirst()
                .orElseThrow().getValue();
        assertThat(validacao.erros(inut, "procInutNFe_v4.00.xsd")).isEmpty();

        assertThatThrownBy(() -> notas.exportarMes(admin, YearMonth.now(t.relogio).minusMonths(1),
                pasta.resolve("vazio.zip"))).hasMessageStartingWith("Não há XML de NFC-e em ");
    }

    @Test
    void transmitirAgoraEnviaAsDeContingencia() {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        NfceGravada contingencia = vender();
        sefaz.cair(); // a SEFAZ continua fora
        assertThat(notas.transmitirAgora(admin)).isEqualTo("A SEFAZ continua sem responder.");
        sefaz.voltar();
        assertThat(notas.transmitirAgora(admin)).isEqualTo("NFC-e: 1 autorizada, 1 com o número inutilizado.");
        assertThat(nota(contingencia.id()).status()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(notas.transmitirAgora(admin)).isEqualTo("Nenhuma NFC-e precisava ser transmitida.");
    }

    @Test
    void verDanfeESalvarXml() {
        NfceGravada n = vender();
        assertThat(notas.danfe(admin, n.id()).chave()).isEqualTo(n.chave());
        NotasFiscaisService.XmlNota xml = notas.xml(admin, n.id());
        assertThat(xml.nomeArquivo()).isEqualTo(n.chave() + "-procNFe.xml");
        assertThat(xml.conteudo()).startsWith("<?xml").contains("<nfeProc");
        sefaz.depois("539");
        NfceGravada rejeitada = vender();
        assertThatThrownBy(() -> notas.danfe(admin, rejeitada.id())).hasMessageContaining("não tem DANFE");
        assertThat(notas.xml(admin, rejeitada.id()).nomeArquivo()).endsWith("-nfe.xml");
    }

    // ----- F23.10 Painel e R32 -----

    @Test
    void alertasDoPainel() throws Exception {
        assertThat(notas.alertas(admin).algum()).isFalse();
        sefaz.depois("539");
        vender();
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        vender();

        AlertasNfce a = notas.alertas(admin);
        assertThat(a.aTransmitir()).isEqualTo(2); // a original sem resposta e a de contingência
        assertThat(a.aTransmitirAntiga()).isFalse();
        assertThat(a.rejeitadas()).isEqualTo(1);
        assertThat(a.diasCertificado()).isNull(); // longe do vencimento

        avancar(Duration.ofDays(1));
        assertThat(notas.alertas(admin).aTransmitirAntiga()).isTrue(); // de um dia anterior: em vermelho

        // O certificado: a partir de nfce.alerta_certificado_dias antes do vencimento.
        try (Connection con = dataSource.getConnection(); var st = con.createStatement()) {
            st.executeUpdate("UPDATE configuracao SET valor = '365' WHERE chave = 'nfce.alerta_certificado_dias'");
        }
        assertThat(notas.alertas(admin).diasCertificado()).isNull(); // o de teste vale por 2 anos
        avancar(Duration.ofDays(400));
        assertThat(notas.alertas(admin).diasCertificado()).isPositive();
        assertThat(notas.alertas(admin).certificadoVencido()).isFalse();
        avancar(Duration.ofDays(20_000));
        assertThat(notas.alertas(admin).certificadoVencido()).isTrue();
    }

    @Test
    void r32BateComAAbaNotasFiscais() {
        vender();
        NfceGravada cancelada = vender();
        vendas.cancelar(admin, cancelada.vendaId(), MOTIVO, null);
        sefaz.depois("539");
        vender();
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        vender();
        LocalDate hoje = LocalDate.now(t.relogio);

        ResultadoRelatorio<NotaFiscal> r32 = t.relatorios.gerar(admin, new RelNfceEmitidas(),
                Filtros.vazios().comPeriodo(new br.com.varesync.model.Periodo(hoje, hoje)));
        List<NotaFiscal> aba = notas.listar(admin, new NotasFiscaisService.Filtro(hoje, hoje, Set.of(), null));

        assertThat(r32.linhas()).hasSize(5);
        assertThat(r32.linhas()).extracting(NotaFiscal::id).containsExactlyInAnyOrderElementsOf(
                aba.stream().map(NotaFiscal::id).toList());
        assertThat(r32.linhas()).extracting(NotaFiscal::status).containsExactlyInAnyOrderElementsOf(
                aba.stream().map(NotaFiscal::status).toList());
        var situacao = r32.colunas().get(4);
        assertThat(situacao.formatarTotal(r32.linhas())).isEqualTo("Autorizada: 1 (R$ 10,98) · Rejeitada: 1 "
                + "(R$ 10,98) · Sem resposta: 1 (R$ 10,98) · Em contingência: 1 (R$ 10,98) · Cancelada: 1 (R$ 10,98)");
    }
}
