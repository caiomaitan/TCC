package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.IntegracaoBase;
import br.com.varesync.dao.AuditoriaDAO;
import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.dao.NumeracaoNfceDAO;
import br.com.varesync.dao.TributoVendaDAO;
import br.com.varesync.model.CodigoBarras;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.DadosFiscais;
import br.com.varesync.model.DadosProduto;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.model.TipoFormaPagamento;
import br.com.varesync.service.nfce.AssinaturaNfce;
import br.com.varesync.service.nfce.CertificadoTeste;
import br.com.varesync.service.nfce.GeracaoNfce;
import br.com.varesync.service.nfce.MontagemNfce;
import br.com.varesync.service.nfce.SefazFalsa;
import br.com.varesync.service.nfce.ValidacaoXsd;
import br.com.varesync.service.nfce.XmlNfceTeste;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NFC-e, parte B (F23.4 e F23.5), com a SEFAZ falsa: autorização, rejeição e denegação; sem resposta (a original
 * SEM_RESPOSTA e outra em contingência, o modo contingência); a SEFAZ de volta (o transmissor autoriza as de
 * contingência e cancela por substituição ou inutiliza a original); a PENDENTE largada no meio; duas transmissões ao
 * mesmo tempo; a REJEITADA de venda cancelada.
 */
class EmissaoNfceIT extends IntegracaoBase {

    private static final String ARROZ = "7891234567895";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    @TempDir
    static Path pasta;

    private AcessoTeste t;
    private Sessao admin;
    private NfceIT.CertificadoEmMemoria terminal;
    private NfceService nfce;
    private EmissaoNfceService emissao;
    private TransmissorNfce transmissor;
    private SefazFalsa sefaz;
    private Transacao transacao;
    private final NfceDAO dao = new NfceDAO();

    @BeforeEach
    void preparar() throws Exception {
        t = new AcessoTeste(dataSource);
        t.limpar();
        admin = t.supervisor();
        transacao = new Transacao(dataSource);
        terminal = new NfceIT.CertificadoEmMemoria();
        terminal.gravar(CertificadoTeste.arquivo().toString(), CertificadoTeste.SENHA);
        AuditoriaService auditoria = new AuditoriaService(new AuditoriaDAO(), t.relogio, AcessoTeste.TERMINAL);
        CertificadoService certificados = new CertificadoService(transacao, new ConfiguracaoDAO(), auditoria, terminal,
                t.relogio);
        ValidacaoXsd validacao = new ValidacaoXsd(pasta);
        nfce = new NfceService(transacao, dao, new NumeracaoNfceDAO(), new TributoVendaDAO(), new ConfiguracaoDAO(),
                new GeracaoNfce(new MontagemNfce(FUSO), new AssinaturaNfce(validacao.pasta(), FUSO), validacao),
                certificados, "2.0.0-SNAPSHOT", new Random(7), t.relogio, FUSO);
        sefaz = new SefazFalsa(t.relogio, pasta);
        emissao = new EmissaoNfceService(transacao, dao, nfce, certificados, new ConfiguracaoDAO(), sefaz,
                new ModoContingencia(), auditoria, t.relogio, FUSO, AcessoTeste.TERMINAL);
        transmissor = new TransmissorNfce(transacao, dao, emissao, certificados, sefaz, auditoria, t.relogio,
                AcessoTeste.TERMINAL);
        Configuracoes c = t.configuracoes.carregar(admin);
        t.configuracoes.salvar(admin, c.comLoja(new Configuracoes.Loja("Mercado Bom Preço", CertificadoTeste.CNPJ,
                        "Rua das Flores, 100", "1633334444", RegimeTributario.SIMPLES, "SP", "123456789012"))
                .comEmitente(new Configuracoes.Emitente("Empresa Teste Ltda", "Rua das Flores", "100", "", "Centro",
                        "14010100", "Ribeirão Preto", "3543402")));
        long perfil = t.fiscal.salvarPerfil(admin, null, FiscalServiceIT.simples("Simples tributado", "102"));
        long arroz = t.produtos.criar(admin, new DadosProduto("Arroz tipo 1 5 kg", null, "UN", new BigDecimal("5.49"),
                new BigDecimal("3.00"), null, null, List.of(new CodigoBarras(ARROZ, true)), new BigDecimal("100")));
        t.fiscal.salvarDadosFiscais(admin, arroz, new DadosFiscais("10063021", null, 0, perfil));
        t.caixa.abrir(admin, new BigDecimal("100.00"));
    }

    /** Venda de 2 arroz (R$ 10,98) em dinheiro, com a NFC-e PENDENTE reservada; devolve o id da NFC-e. */
    private long vendaComNfce() {
        Carrinho carrinho = new Carrinho();
        t.vendas.adicionarItem(admin, carrinho, t.vendas.lerProduto(admin, ARROZ).produto(), new BigDecimal("2"));
        Recebimento r = t.vendas.recebimento(admin, carrinho);
        r.adicionar(t.vendas.formasAtivas(admin).stream().filter(f -> f.tipo() == TipoFormaPagamento.DINHEIRO)
                .findFirst().orElseThrow(), new BigDecimal("20.00"));
        long venda = t.vendas.finalizar(admin, carrinho, r).vendaId();
        return transacao.executar(con -> nfce.reservar(con, venda, TipoEmissaoNfce.NORMAL,
                OffsetDateTime.now(t.relogio), null));
    }

    private NfceGravada nota(long id) {
        return transacao.executar(con -> dao.buscar(con, id, false)).orElseThrow();
    }

    private List<NfceGravada> daVenda(long vendaId) {
        return transacao.executar(con -> dao.daVenda(con, vendaId));
    }

    private long auditorias(String acao) throws Exception {
        return t.contar("SELECT count(*) FROM auditoria WHERE acao = '" + acao + "'");
    }

    // ----- Autoriza, rejeita, denega -----

    @Test
    void autorizaComProtocoloENfeProc() {
        long id = vendaComNfce();

        EmissaoNfceService.Resultado r = emissao.emitir(admin, id);

        NfceGravada n = r.nfce();
        assertThat(n.status()).as(r.mensagem()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(n.cstat()).isEqualTo("100");
        assertThat(n.protocolo()).isNotBlank();
        assertThat(n.autorizadaEm()).isNotNull();
        assertThat(n.xml()).contains("<nfeProc", "<protNFe", "<nProt>" + n.protocolo() + "</nProt>", "<Signature");
        assertThat(XmlNfceTeste.assinaturaConfere(n.xml(), XmlNfceTeste.certificadoDeTeste().getPublicKey()))
                .as("a assinatura continua conferindo dentro do nfeProc").isTrue();
        assertThat(sefaz.envios()).hasSize(1);
        assertThat(emissao.modo().ativo()).isFalse();
    }

    @Test
    void rejeitaComCstatEMotivoEDenegaGastandoONumero() {
        sefaz.depois("539", "302");
        long rejeitada = vendaComNfce();
        long denegada = vendaComNfce();

        NfceGravada r = emissao.emitir(admin, rejeitada).nfce();
        NfceGravada d = emissao.emitir(admin, denegada).nfce();

        assertThat(r.status()).isEqualTo(StatusNfce.REJEITADA);
        assertThat(r.cstat()).isEqualTo("539");
        assertThat(r.motivo()).contains("Rejeicao");
        assertThat(r.protocolo()).isNull();
        assertThat(d.status()).isEqualTo(StatusNfce.DENEGADA);
        assertThat(d.cstat()).isEqualTo("302");
    }

    @Test
    void semCertificadoNadaMudaENaoVaiASefaz() {
        long id = vendaComNfce();
        terminal.gravar("", "");
        assertThatThrownBy(() -> emissao.emitir(admin, id)).isInstanceOf(NegocioException.class)
                .hasMessageContaining("certificado digital");
        assertThat(nota(id).status()).isEqualTo(StatusNfce.PENDENTE);
        assertThat(sefaz.envios()).isEmpty();
    }

    // ----- Sem resposta: contingência -----

    @Test
    void semRespostaDeixaAOriginalSemRespostaEOutraEmContingenciaEAProximaVendaNemTenta() throws Exception {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        long id = vendaComNfce();
        NfceGravada original = nota(id);

        EmissaoNfceService.Resultado r = emissao.emitir(admin, id);

        assertThat(r.semComunicacao()).isTrue();
        List<NfceGravada> notas = daVenda(original.vendaId());
        assertThat(notas).hasSize(2);
        NfceGravada semResposta = notas.get(0);
        NfceGravada contingencia = notas.get(1);
        assertThat(semResposta.status()).isEqualTo(StatusNfce.SEM_RESPOSTA);
        assertThat(semResposta.substitutaId()).isEqualTo(contingencia.id());
        assertThat(contingencia.status()).isEqualTo(StatusNfce.CONTINGENCIA);
        assertThat(contingencia.numero()).as("número seguinte").isEqualTo(original.numero() + 1);
        assertThat(contingencia.tipoEmissao()).isEqualTo(TipoEmissaoNfce.CONTINGENCIA_OFFLINE);
        assertThat(contingencia.chave().charAt(34)).isEqualTo('9');
        assertThat(XmlNfceTeste.texto(contingencia.xml(), "xJust")).isEqualTo(NfceService.JUSTIFICATIVA_CONTINGENCIA);
        assertThat(XmlNfceTeste.texto(contingencia.xml(), "dhCont")).isNotNull();
        assertThat(contingencia.qrcode().split("\\|", -1)).as("QR Code de contingência").hasSize(8);
        assertThat(r.nfce().id()).isEqualTo(contingencia.id());
        assertThat(emissao.modo().ativo()).isTrue();
        assertThat(auditorias("NFCE_CONTINGENCIA_INICIADA")).isEqualTo(1);

        // A venda seguinte do terminal já sai em contingência, no próprio número, sem tentar a autorização.
        t.relogio.avancar(Duration.ofMinutes(1));
        long seguinte = vendaComNfce();
        int numeroReservado = nota(seguinte).numero();
        EmissaoNfceService.Resultado r2 = emissao.emitir(admin, seguinte);
        assertThat(sefaz.envios()).as("nem tentou").hasSize(1);
        NfceGravada n2 = r2.nfce();
        assertThat(n2.status()).isEqualTo(StatusNfce.CONTINGENCIA);
        assertThat(n2.numero()).isEqualTo(numeroReservado);
        assertThat(n2.contingenciaEm()).as("dhCont = o início do modo").isEqualTo(contingencia.contingenciaEm());
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE status = 'SEM_RESPOSTA'")).isEqualTo(1);
        assertThat(auditorias("NFCE_CONTINGENCIA_INICIADA")).as("entrou uma vez só").isEqualTo(1);
    }

    @Test
    void sefazDeVoltaAutorizaAsDeContingenciaECancelaAOriginalPorSubstituicaoSeElaChegou() throws Exception {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA_MAS_AUTORIZOU);
        long id = vendaComNfce();
        NfceGravada original = nota(id);
        emissao.emitir(admin, id);
        sefaz.cair();
        TransmissorNfce.Resumo fora = transmissor.transmitir(admin);
        assertThat(fora.semComunicacao()).isTrue();
        assertThat(emissao.modo().ativo()).as("continua em contingência").isTrue();

        sefaz.voltar();
        t.relogio.avancar(Duration.ofMinutes(5));
        TransmissorNfce.Resumo resumo = transmissor.transmitir(admin);

        assertThat(resumo.semComunicacao()).as(resumo.mensagem()).isFalse();
        assertThat(emissao.modo().ativo()).isFalse();
        assertThat(auditorias("NFCE_CONTINGENCIA_ENCERRADA")).isEqualTo(1);
        List<NfceGravada> notas = daVenda(original.vendaId());
        assertThat(notas.get(1).status()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(notas.get(1).xml()).startsWith("<?xml").contains("<nfeProc");
        NfceGravada substituida = notas.get(0);
        assertThat(substituida.status()).isEqualTo(StatusNfce.SUBSTITUIDA);
        assertThat(substituida.protocolo()).as("o protocolo da autorização, pela consulta").isNotBlank();
        assertThat(sefaz.eventos()).containsExactly("110112 " + substituida.chave() + " por " + notas.get(1).chave());
        assertThat(t.contar("SELECT count(*) FROM nfce_evento WHERE tipo = 'CANCELAMENTO_SUBSTITUICAO' "
                + "AND cstat = '135' AND usuario_id IS NULL AND nfce_id = " + substituida.id())).isEqualTo(1);
        assertThat(resumo.autorizadas()).isEqualTo(1);
        assertThat(resumo.substituidas()).isEqualTo(1);
        assertThat(t.contar("SELECT count(*) FROM auditoria WHERE acao = 'NFCE_CANCELADA' "
                + "AND descricao LIKE '%(automático)'")).isEqualTo(1);
    }

    @Test
    void sefazDeVoltaInutilizaAOriginalQueNaoConsta() throws Exception {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        long id = vendaComNfce();
        NfceGravada original = nota(id);
        emissao.emitir(admin, id);

        TransmissorNfce.Resumo resumo = transmissor.transmitir(admin);

        List<NfceGravada> notas = daVenda(original.vendaId());
        assertThat(notas.get(1).status()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(notas.get(0).status()).isEqualTo(StatusNfce.INUTILIZADA);
        assertThat(sefaz.inutilizacoes()).containsExactly("1:" + original.numero() + "-" + original.numero());
        assertThat(t.contar("SELECT count(*) FROM nfce_inutilizacao WHERE numero_inicial = " + original.numero()
                + " AND numero_final = " + original.numero() + " AND cstat = '102' AND ano = 26")).isEqualTo(1);
        assertThat(resumo.inutilizadas()).isEqualTo(1);
        assertThat(auditorias("NFCE_INUTILIZADA")).isEqualTo(1);
    }

    @Test
    void transmissorComASefazForaDeixaEmContingenciaETentaDepois() {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        long id = vendaComNfce();
        long vendaId = nota(id).vendaId();
        emissao.emitir(admin, id);
        emissao.modo().sair(); // o terminal reiniciou: o modo é da memória
        sefaz.cair();

        TransmissorNfce.Resumo resumo = transmissor.transmitir(admin);

        assertThat(resumo.semComunicacao()).isTrue();
        NfceGravada contingencia = daVenda(vendaId).get(1);
        assertThat(contingencia.status()).isEqualTo(StatusNfce.CONTINGENCIA);
        assertThat(contingencia.motivo()).isEqualTo(EmissaoNfceService.SEM_COMUNICACAO);
        assertThat(daVenda(vendaId).get(0).status()).isEqualTo(StatusNfce.SEM_RESPOSTA);
    }

    @Test
    void duplicidadeNaContingenciaContaComoAutorizadaPelaConsulta() {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA, SefazFalsa.Acao.DUPLICIDADE);
        long id = vendaComNfce();
        long vendaId = nota(id).vendaId();
        emissao.emitir(admin, id);

        transmissor.transmitir(admin);

        NfceGravada contingencia = daVenda(vendaId).get(1);
        assertThat(contingencia.status()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(contingencia.protocolo()).isNotBlank();
    }

    // ----- O sistema caiu no meio -----

    @Test
    void pendenteLargadaHaMaisDeDoisMinutosViraContingenciaPeloTransmissor() throws Exception {
        long id = vendaComNfce();
        long vendaId = nota(id).vendaId();
        t.relogio.avancar(Duration.ofMinutes(1));
        transmissor.transmitir(admin);
        assertThat(nota(id).status()).as("ainda dentro dos 2 minutos").isEqualTo(StatusNfce.PENDENTE);

        t.relogio.avancar(Duration.ofMinutes(2));
        transmissor.transmitir(admin);

        List<NfceGravada> notas = daVenda(vendaId);
        assertThat(notas).hasSize(2);
        assertThat(notas.get(1).status()).as("a de contingência, transmitida na mesma passada")
                .isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(notas.get(0).status()).as("a original não constava: inutilizada").isEqualTo(StatusNfce.INUTILIZADA);
        assertThat(emissao.modo().ativo()).as("quem caiu foi o sistema, não a SEFAZ").isFalse();
    }

    // ----- Trava -----

    @Test
    void duasTransmissoesAoMesmoTempoNaoEnviamAMesmaNotaDuasVezes() throws Exception {
        long id = vendaComNfce();
        sefaz.demorar(400);
        CyclicBarrier largada = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<EmissaoNfceService.Resultado>> resultados = threads.invokeAll(List.of(
                    () -> {
                        largada.await();
                        return emissao.emitir(admin, id);
                    },
                    () -> {
                        largada.await();
                        return emissao.emitir(admin, id);
                    }));
            assertThat(sefaz.envios()).as("um envio só").hasSize(1);
            long comTrava = resultados.stream().map(this::valor)
                    .filter(r -> r.mensagem() != null && r.mensagem().contains("já está sendo transmitida")).count();
            assertThat(comTrava).isEqualTo(1);
        } finally {
            threads.shutdown();
            threads.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(nota(id).status()).isEqualTo(StatusNfce.AUTORIZADA);
        assertThat(t.contar("SELECT count(*) FROM nfce WHERE transmitindo_ate IS NOT NULL")).as("trava solta")
                .isZero();
    }

    private EmissaoNfceService.Resultado valor(Future<EmissaoNfceService.Resultado> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void travaVencidaPodeSerRetomada() throws Exception {
        long id = vendaComNfce();
        transacao.executar(con -> dao.travar(con, id, "OUTRO#1", OffsetDateTime.now(t.relogio),
                OffsetDateTime.now(t.relogio).plus(EmissaoNfceService.DURACAO_TRAVA)));
        assertThat(emissao.emitir(admin, id).mensagem()).contains("já está sendo transmitida");
        assertThat(sefaz.envios()).isEmpty();

        t.relogio.avancar(Duration.ofMinutes(2).plusSeconds(1));
        assertThat(emissao.emitir(admin, id).nfce().status()).isEqualTo(StatusNfce.AUTORIZADA);
    }

    // ----- Venda cancelada -----

    @Test
    void rejeitadaDeVendaCanceladaTemONumeroInutilizado() throws Exception {
        sefaz.depois("539");
        long id = vendaComNfce();
        NfceGravada n = emissao.emitir(admin, id).nfce();
        assertThat(n.status()).isEqualTo(StatusNfce.REJEITADA);
        t.vendas.cancelar(admin, n.vendaId(), "Cliente desistiu da compra", null);

        TransmissorNfce.Resumo resumo = transmissor.transmitir(admin);

        assertThat(nota(id).status()).isEqualTo(StatusNfce.INUTILIZADA);
        assertThat(resumo.inutilizadas()).isEqualTo(1);
        assertThat(t.contar("SELECT count(*) FROM nfce_inutilizacao WHERE usuario_id IS NULL")).isEqualTo(1);
    }

    @Test
    void semCertificadoOTransmissorNaoFazNada() {
        sefaz.depois(SefazFalsa.Acao.SEM_RESPOSTA);
        long id = vendaComNfce();
        emissao.emitir(admin, id);
        terminal.gravar("", "");

        TransmissorNfce.Resumo resumo = transmissor.transmitir(admin);

        assertThat(resumo.mensagem()).contains("certificado digital");
        assertThat(sefaz.envios()).hasSize(1);
    }
}
