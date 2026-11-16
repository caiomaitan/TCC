package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.service.nfce.CertificadoA1;
import br.com.varesync.service.nfce.ContextoSefaz;
import br.com.varesync.service.nfce.RespostaSefaz;
import br.com.varesync.service.nfce.RespostasSefaz;
import br.com.varesync.service.nfce.Sefaz;
import br.com.varesync.service.nfce.SemComunicacaoException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emissão da NFC-e (F23.5), fora da transação da venda (na parte C, numa {@code Task}):
 * <ol>
 *   <li>pega a NFC-e com a trava ({@code transmitindo_ate} = agora + 2 minutos, pelo {@code Clock}); quem não consegue
 *   a trava deixa a nota para quem conseguiu;</li>
 *   <li>monta, assina, valida e grava o XML e o QR Code, se ainda não estão gravados;</li>
 *   <li>envia e grava a resposta: 100 ou 150 → AUTORIZADA (com o protocolo e o {@code nfeProc}); uso denegado →
 *   DENEGADA; rejeição → REJEITADA, com o cStat e o motivo;</li>
 *   <li>sem comunicação: a NFC-e fica SEM_RESPOSTA, e a venda recebe outra, com número novo, em contingência off-line
 *   (CONTINGENCIA); o terminal entra no modo contingência.</li>
 * </ol>
 * No modo contingência, a PENDENTE vira contingência no próprio número, sem tentar a autorização. A de contingência é
 * enviada só pelo transmissor ({@link TransmissorNfce}).
 *
 * <p>É tarefa do sistema: não exige permissão. Log: só o número, o cStat e o motivo.
 */
public class EmissaoNfceService {

    private static final Logger LOG = LoggerFactory.getLogger(EmissaoNfceService.class);
    public static final Duration DURACAO_TRAVA = Duration.ofMinutes(2);
    static final String SEM_COMUNICACAO = "Sem resposta da SEFAZ";

    /**
     * @param nfce           a NFC-e que vale para a venda depois da emissão (a de contingência, quando foi criada)
     * @param semComunicacao a SEFAZ não respondeu nesta tentativa
     * @param mensagem       para a tela: o motivo da rejeição, a contingência ou a trava de outro
     */
    public record Resultado(NfceGravada nfce, boolean semComunicacao, String mensagem) {
    }

    private final Transacao transacao;
    private final NfceDAO nfceDAO;
    private final NfceService nfceService;
    private final CertificadoService certificados;
    private final ConfiguracaoDAO configuracaoDAO;
    private final Sefaz sefaz;
    private final ModoContingencia modo;
    private final AuditoriaService auditoria;
    private final Clock clock;
    private final ZoneId fuso;
    private final String terminal;

    public EmissaoNfceService(Transacao transacao, NfceDAO nfceDAO, NfceService nfceService,
                              CertificadoService certificados, ConfiguracaoDAO configuracaoDAO, Sefaz sefaz,
                              ModoContingencia modo, AuditoriaService auditoria, Clock clock, ZoneId fuso,
                              String terminal) {
        this.transacao = transacao;
        this.nfceDAO = nfceDAO;
        this.nfceService = nfceService;
        this.certificados = certificados;
        this.configuracaoDAO = configuracaoDAO;
        this.sefaz = sefaz;
        this.modo = modo;
        this.auditoria = auditoria;
        this.clock = clock;
        this.fuso = fuso;
        this.terminal = terminal;
    }

    public ModoContingencia modo() {
        return modo;
    }

    /**
     * Emite a NFC-e PENDENTE (passos 1 a 4). A de contingência só é montada aqui; quem a envia é o transmissor. Sem o
     * certificado deste terminal (ou vencido), a recusa vai para quem chamou e nada muda.
     */
    public Resultado emitir(Sessao sessao, long nfceId) {
        return processar(sessao, nfceId, false);
    }

    /** O transmissor: envia a NFC-e de contingência (a resposta é a do passo 3; sem comunicação, ela continua). */
    Resultado transmitirContingencia(Sessao sessao, long nfceId) {
        return processar(sessao, nfceId, true);
    }

    private Resultado processar(Sessao sessao, long nfceId, boolean automatico) {
        CertificadoA1 certificado = certificados.doTerminal();
        return comTrava(nfceId, n -> {
            NfceGravada nota = n;
            if (nota.status() == StatusNfce.PENDENTE && modo.ativo()) {
                OffsetDateTime inicio = modo.inicio().orElseGet(() -> OffsetDateTime.now(clock));
                transacao.executar(con -> {
                    nfceService.paraContingencia(con, nfceDAO.buscar(con, nfceId, true).orElseThrow(), inicio);
                    return null;
                });
                nota = buscar(nfceId);
                LOG.info("NFC-e nº {} em contingência (terminal no modo contingência)", nota.numero());
            }
            if (nota.status() != StatusNfce.PENDENTE && nota.status() != StatusNfce.CONTINGENCIA) {
                return new Resultado(nota, false, "A NFC-e nº " + nota.numero() + " está "
                        + nota.status().rotulo().toLowerCase() + ".");
            }
            if (nota.xml() == null) {
                nota = nfceService.prepararXml(nfceId);
                if (nota.status() == StatusNfce.REJEITADA) {
                    return new Resultado(nota, false, nota.motivo());
                }
            }
            if (nota.status() == StatusNfce.CONTINGENCIA && !automatico) {
                return new Resultado(nota, false, "NFC-e emitida em contingência: será transmitida quando a SEFAZ "
                        + "voltar a responder.");
            }
            return enviar(sessao, nota, certificado, automatico);
        });
    }

    /** Passos 3 e 4: envia e grava a resposta; sem comunicação, a contingência. */
    private Resultado enviar(Sessao sessao, NfceGravada nota, CertificadoA1 certificado, boolean automatico) {
        ContextoSefaz contexto = contexto(certificado);
        RespostaSefaz resposta;
        try {
            resposta = sefaz.autorizar(contexto, nota.xml(), String.valueOf(nota.numero()));
            if (resposta.duplicidade()) {
                // A nota já chegou antes (a resposta se perdeu): vale o que a consulta disser.
                RespostaSefaz consulta = sefaz.consultar(contexto, nota.chave());
                if (consulta.autorizada()) {
                    resposta = consulta;
                }
            }
        } catch (SemComunicacaoException e) {
            if (nota.status() == StatusNfce.CONTINGENCIA) {
                transacao.executar(con -> {
                    nfceDAO.responder(con, nota.id(), StatusNfce.CONTINGENCIA, null, SEM_COMUNICACAO,
                            OffsetDateTime.now(clock));
                    return null;
                });
                LOG.info("NFC-e nº {} continua em contingência: SEFAZ sem resposta", nota.numero());
                return new Resultado(buscar(nota.id()), true, "A SEFAZ não respondeu: a NFC-e continua em "
                        + "contingência.");
            }
            NfceGravada nova = contingencia(sessao, nota, true, automatico);
            return new Resultado(nova, true, "A SEFAZ não respondeu: a NFC-e foi emitida em contingência (nº "
                    + nova.numero() + ").");
        }
        return new Resultado(gravar(nota, resposta), false, resposta.autorizada() ? null : resposta.descricao());
    }

    private NfceGravada gravar(NfceGravada nota, RespostaSefaz r) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        LOG.info("NFC-e nº {}: cStat {} - {}", nota.numero(), r.cstat(), r.motivo());
        transacao.executar(con -> {
            if (r.autorizada()) {
                String xml = r.xml() == null ? nota.xml() : RespostasSefaz.nfeProc(nota.xml(), r.xml());
                nfceDAO.autorizar(con, nota.id(), r.cstat(), r.motivo(), r.protocolo(),
                        r.recebidoEm() == null ? agora : r.recebidoEm(), xml, agora);
            } else {
                nfceDAO.responder(con, nota.id(), r.denegada() ? StatusNfce.DENEGADA : StatusNfce.REJEITADA,
                        r.cstat(), r.motivo(), agora);
            }
            return null;
        });
        return buscar(nota.id());
    }

    /**
     * Passo 4: a original fica SEM_RESPOSTA e a venda recebe outra NFC-e, com número novo, em contingência off-line
     * (dhEmi e dhCont = agora). Com {@code entrarNoModo}, o terminal entra no modo contingência (com auditoria).
     */
    NfceGravada contingencia(Sessao sessao, NfceGravada original, boolean entrarNoModo, boolean automatico) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        boolean iniciaModo = entrarNoModo && !modo.ativo();
        long novaId = transacao.executar(con -> {
            NfceGravada atual = nfceDAO.buscar(con, original.id(), true).orElseThrow();
            if (atual.status() != StatusNfce.PENDENTE) {
                throw new IllegalStateException("A NFC-e " + atual.id() + " não está pendente");
            }
            nfceDAO.semResposta(con, atual.id(), SEM_COMUNICACAO, agora);
            long id = nfceService.reservar(con, atual.vendaId(), TipoEmissaoNfce.CONTINGENCIA_OFFLINE, agora, agora);
            nfceDAO.ligarSubstituta(con, atual.id(), id, agora);
            if (iniciaModo) {
                auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_CONTINGENCIA_INICIADA, NfceService.ENTIDADE, id,
                        "Terminal " + terminal + " em contingência: a SEFAZ não respondeu à NFC-e nº "
                                + atual.numero() + ", que ficou sem resposta; a venda nº " + atual.vendaId()
                                + " recebeu a NFC-e nº " + numero(con, id) + " em contingência" + automatico(automatico));
            }
            return id;
        });
        if (iniciaModo) {
            modo.entrar(agora);
        }
        LOG.info("NFC-e nº {} sem resposta: a venda recebeu outra NFC-e em contingência", original.numero());
        return nfceService.prepararXml(novaId);
    }

    private int numero(java.sql.Connection con, long id) throws java.sql.SQLException {
        return nfceDAO.buscar(con, id, false).orElseThrow().numero();
    }

    static String automatico(boolean automatico) {
        return automatico ? " (automático)" : "";
    }

    // ----- Trava, contexto e leitura -----

    /**
     * Executa com a trava da transmissão (F23.5, passo 1). Sem a trava (outro está transmitindo), devolve a nota como
     * está, com a mensagem, sem fazer nada.
     */
    Resultado comTrava(long nfceId, Function<NfceGravada, Resultado> acao) {
        String dono = (terminal + "#" + UUID.randomUUID().toString().substring(0, 8));
        OffsetDateTime agora = OffsetDateTime.now(clock);
        Optional<NfceGravada> travada = transacao.executar(con -> nfceDAO.travar(con, nfceId, dono, agora,
                agora.plus(DURACAO_TRAVA)));
        if (travada.isEmpty()) {
            NfceGravada atual = buscar(nfceId);
            return new Resultado(atual, false, "A NFC-e nº " + atual.numero() + " já está sendo transmitida.");
        }
        try {
            return acao.apply(travada.get());
        } finally {
            transacao.executar(con -> {
                nfceDAO.soltar(con, nfceId, dono, OffsetDateTime.now(clock));
                return null;
            });
        }
    }

    ContextoSefaz contexto(CertificadoA1 certificado) {
        Configuracoes config = transacao.executar(configuracaoDAO::carregar);
        return new ContextoSefaz(config.loja().uf(), config.loja().documento(), certificado,
                config.nfce().timeoutSegundos(), fuso);
    }

    NfceGravada buscar(long id) {
        return transacao.executar(con -> nfceDAO.buscar(con, id, false))
                .orElseThrow(() -> new NegocioException("NFC-e não encontrada."));
    }
}
