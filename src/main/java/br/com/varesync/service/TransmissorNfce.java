package br.com.varesync.service;

import br.com.varesync.dao.NfceDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.service.nfce.CertificadoA1;
import br.com.varesync.service.nfce.ContextoSefaz;
import br.com.varesync.service.nfce.RespostaSefaz;
import br.com.varesync.service.nfce.Sefaz;
import br.com.varesync.service.nfce.SemComunicacaoException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transmissor das NFC-e (F23.5): roda em segundo plano em todo terminal com alguém logado (pelo
 * {@link TransmissorAgendado}) e, na parte D, pelo botão "Transmitir agora". É tarefa do sistema: usa a sessão de quem
 * está logado sem exigir permissão, e a auditoria diz "automático". Em ordem, cada nota com a trava:
 * <ol>
 *   <li>no modo contingência, o status do serviço: 107 encerra o modo; senão, para por aqui;</li>
 *   <li>PENDENTE reservada há mais de 2 minutos e sem trava válida (o sistema caiu no meio): o passo 4 da emissão
 *   (SEM_RESPOSTA e outra NFC-e em contingência);</li>
 *   <li>CONTINGENCIA: envia; sem comunicação, ela continua em contingência e o transmissor para;</li>
 *   <li>SEM_RESPOSTA cuja substituta foi autorizada: consulta pela chave; autorizada → evento 110112 (SUBSTITUIDA);
 *   não consta (217) → inutiliza o número (INUTILIZADA);</li>
 *   <li>REJEITADA de venda cancelada: inutiliza o número.</li>
 * </ol>
 */
public class TransmissorNfce {

    private static final Logger LOG = LoggerFactory.getLogger(TransmissorNfce.class);
    static final String JUSTIFICATIVA_SUBSTITUICAO = "Cancelada por substituicao: SEFAZ sem resposta na emissao, "
            + "a NFC-e de contingencia da mesma venda foi autorizada";
    static final String JUSTIFICATIVA_INUTILIZACAO = "Numero de NFC-e nao utilizado: a nota nao foi autorizada";

    /**
     * O que o transmissor fez.
     *
     * @param mensagem por que parou antes do fim (sem certificado, SEFAZ sem resposta), ou nulo
     */
    public record Resumo(int autorizadas, int rejeitadas, int emContingencia, int substituidas, int inutilizadas,
                         boolean semComunicacao, String mensagem) {

        static Resumo parou(String mensagem, boolean semComunicacao) {
            return new Resumo(0, 0, 0, 0, 0, semComunicacao, mensagem);
        }
    }

    private final Transacao transacao;
    private final NfceDAO nfceDAO;
    private final EmissaoNfceService emissao;
    private final CertificadoService certificados;
    private final Sefaz sefaz;
    private final AuditoriaService auditoria;
    private final Clock clock;
    private final String terminal;

    public TransmissorNfce(Transacao transacao, NfceDAO nfceDAO, EmissaoNfceService emissao,
                           CertificadoService certificados, Sefaz sefaz, AuditoriaService auditoria, Clock clock,
                           String terminal) {
        this.transacao = transacao;
        this.nfceDAO = nfceDAO;
        this.emissao = emissao;
        this.certificados = certificados;
        this.sefaz = sefaz;
        this.auditoria = auditoria;
        this.clock = clock;
        this.terminal = terminal;
    }

    /** Uma passada do transmissor (só uma por vez neste terminal). */
    public synchronized Resumo transmitir(Sessao sessao) {
        CertificadoA1 certificado;
        try {
            certificado = certificados.doTerminal();
        } catch (NegocioException e) {
            return Resumo.parou(e.getMessage(), false);
        }
        ContextoSefaz contexto = emissao.contexto(certificado);
        ModoContingencia modo = emissao.modo();
        if (modo.ativo()) {
            try {
                RespostaSefaz status = sefaz.status(contexto);
                if (!status.emOperacao()) {
                    return Resumo.parou("A SEFAZ ainda não está em operação: " + status.descricao(), true);
                }
            } catch (SemComunicacaoException e) {
                return Resumo.parou("A SEFAZ continua sem responder.", true);
            }
            encerrarModo(sessao);
        }
        int autorizadas = 0;
        int rejeitadas = 0;
        int contingencia = 0;
        int substituidas = 0;
        int inutilizadas = 0;
        OffsetDateTime agora = OffsetDateTime.now(clock);

        // 2. PENDENTE largada no meio: o passo 4, sem entrar no modo (quem caiu foi o sistema, não a SEFAZ).
        List<Long> abandonadas = transacao.executar(con -> nfceDAO.pendentesAbandonadas(con,
                agora.minus(EmissaoNfceService.DURACAO_TRAVA), agora));
        for (long id : abandonadas) {
            EmissaoNfceService.Resultado r = emissao.comTrava(id, n -> new EmissaoNfceService.Resultado(
                    emissao.contingencia(sessao, n, false, true), true, null));
            if (r.semComunicacao()) {
                contingencia++;
            }
        }

        // 3. As de contingência.
        for (long id : transacao.executar(nfceDAO::emContingencia)) {
            EmissaoNfceService.Resultado r = emissao.transmitirContingencia(sessao, id);
            if (r.semComunicacao()) {
                return new Resumo(autorizadas, rejeitadas, contingencia + 1, substituidas, inutilizadas, true,
                        "A SEFAZ não respondeu: as NFC-e em contingência continuam a transmitir.");
            }
            switch (r.nfce().status()) {
                case AUTORIZADA -> autorizadas++;
                case REJEITADA, DENEGADA -> rejeitadas++;
                case CONTINGENCIA -> contingencia++;
                default -> {
                    // trava de outro: fica para a próxima
                }
            }
        }

        // 4. As sem resposta cuja substituta foi autorizada.
        for (long id : transacao.executar(nfceDAO::semRespostaComSubstitutaAutorizada)) {
            try {
                StatusNfce fim = resolverSemResposta(sessao, contexto, id);
                if (fim == StatusNfce.SUBSTITUIDA) {
                    substituidas++;
                } else if (fim == StatusNfce.INUTILIZADA) {
                    inutilizadas++;
                }
            } catch (SemComunicacaoException e) {
                return new Resumo(autorizadas, rejeitadas, contingencia, substituidas, inutilizadas, true,
                        "A SEFAZ não respondeu: as NFC-e sem resposta ficam para a próxima vez.");
            }
        }

        // 5. Rejeitadas de venda cancelada.
        for (long id : transacao.executar(nfceDAO::rejeitadasDeVendaCancelada)) {
            try {
                if (comTrava(id, n -> inutilizar(sessao, contexto, n)) == StatusNfce.INUTILIZADA) {
                    inutilizadas++;
                }
            } catch (SemComunicacaoException e) {
                return new Resumo(autorizadas, rejeitadas, contingencia, substituidas, inutilizadas, true,
                        "A SEFAZ não respondeu: a inutilização fica para a próxima vez.");
            }
        }
        return new Resumo(autorizadas, rejeitadas, contingencia, substituidas, inutilizadas, false, null);
    }

    private void encerrarModo(Sessao sessao) {
        OffsetDateTime inicio = emissao.modo().inicio().orElse(null);
        if (!emissao.modo().sair()) {
            return;
        }
        transacao.executar(con -> {
            auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_CONTINGENCIA_ENCERRADA, NfceService.ENTIDADE, null,
                    "Terminal " + terminal + " saiu da contingência: a SEFAZ voltou a responder (em contingência "
                            + "desde " + (inicio == null ? "—" : br.com.varesync.util.Formatos.dataHora(inicio))
                            + ")" + EmissaoNfceService.automatico(true));
            return null;
        });
        LOG.info("Terminal {} saiu do modo contingência", terminal);
    }

    /** Consulta a SEM_RESPOSTA pela chave e a cancela por substituição ou inutiliza o número. */
    private StatusNfce resolverSemResposta(Sessao sessao, ContextoSefaz contexto, long id) {
        return comTrava(id, n -> {
            if (n.status() != StatusNfce.SEM_RESPOSTA || n.substitutaId() == null) {
                return n.status();
            }
            RespostaSefaz consulta = sefaz.consultar(contexto, n.chave());
            if (consulta.autorizada()) {
                NfceGravada substituta = emissao.buscar(n.substitutaId());
                RespostaSefaz evento = sefaz.cancelarPorSubstituicao(contexto, n.chave(), consulta.protocolo(),
                        substituta.chave(), JUSTIFICATIVA_SUBSTITUICAO, OffsetDateTime.now(clock));
                OffsetDateTime agora = OffsetDateTime.now(clock);
                LOG.info("NFC-e nº {}, cancelamento por substituição: cStat {} - {}", n.numero(), evento.cstat(),
                        evento.motivo());
                return transacao.executar(con -> {
                    if (!evento.eventoRegistrado()) {
                        nfceDAO.responder(con, n.id(), StatusNfce.SEM_RESPOSTA, evento.cstat(), evento.motivo(), agora);
                        return StatusNfce.SEM_RESPOSTA;
                    }
                    nfceDAO.substituida(con, n.id(), consulta.protocolo(),
                            consulta.recebidoEm() == null ? agora : consulta.recebidoEm(), evento.cstat(),
                            evento.motivo(), agora);
                    nfceDAO.inserirEvento(con, n.id(), "CANCELAMENTO_SUBSTITUICAO", JUSTIFICATIVA_SUBSTITUICAO,
                            evento.cstat(), evento.motivo(), evento.protocolo(), evento.xml(), agora, null);
                    auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_CANCELADA, NfceService.ENTIDADE, n.id(),
                            "NFC-e nº " + n.numero() + " (série " + n.serie() + ") cancelada por substituição pela "
                                    + "NFC-e nº " + substituta.numero() + ", de contingência"
                                    + EmissaoNfceService.automatico(true));
                    return StatusNfce.SUBSTITUIDA;
                });
            }
            if (consulta.naoConsta()) {
                return inutilizar(sessao, contexto, n);
            }
            OffsetDateTime agora = OffsetDateTime.now(clock);
            transacao.executar(con -> {
                nfceDAO.responder(con, n.id(), StatusNfce.SEM_RESPOSTA, consulta.cstat(), consulta.motivo(), agora);
                return null;
            });
            return StatusNfce.SEM_RESPOSTA;
        });
    }

    /** Inutiliza o número da nota (SEM_RESPOSTA que não consta, ou REJEITADA de venda cancelada). */
    private StatusNfce inutilizar(Sessao sessao, ContextoSefaz contexto, NfceGravada n) {
        if (n.status() != StatusNfce.SEM_RESPOSTA && n.status() != StatusNfce.REJEITADA) {
            return n.status();
        }
        RespostaSefaz r = sefaz.inutilizar(contexto, n.serie(), n.numero(), n.numero(), JUSTIFICATIVA_INUTILIZACAO,
                OffsetDateTime.now(clock));
        OffsetDateTime agora = OffsetDateTime.now(clock);
        LOG.info("NFC-e nº {}, inutilização: cStat {} - {}", n.numero(), r.cstat(), r.motivo());
        return transacao.executar(con -> {
            if (!r.inutilizada()) {
                nfceDAO.responder(con, n.id(), n.status(), r.cstat(), r.motivo(), agora);
                return n.status();
            }
            int ano = n.emitidaEm().atZoneSameInstant(clock.getZone()).getYear() % 100;
            nfceDAO.inserirInutilizacao(con, n.serie(), n.numero(), n.numero(), ano, JUSTIFICATIVA_INUTILIZACAO,
                    r.cstat(), r.motivo(), r.protocolo(), r.xml(), agora, null);
            nfceDAO.inutilizada(con, n.id(), r.cstat(), r.motivo(), agora);
            auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_INUTILIZADA, NfceService.ENTIDADE, n.id(),
                    "Número " + n.numero() + " da série " + n.serie() + " da NFC-e inutilizado"
                            + EmissaoNfceService.automatico(true));
            return StatusNfce.INUTILIZADA;
        });
    }

    private StatusNfce comTrava(long id, java.util.function.Function<NfceGravada, StatusNfce> acao) {
        StatusNfce[] fim = new StatusNfce[1];
        emissao.comTrava(id, n -> {
            fim[0] = acao.apply(n);
            return null;
        });
        return fim[0];
    }
}
