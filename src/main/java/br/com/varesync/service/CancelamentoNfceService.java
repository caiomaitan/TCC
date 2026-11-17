package br.com.varesync.service;

import br.com.varesync.dao.ConfiguracaoDAO;
import br.com.varesync.dao.NfceDAO;
import br.com.varesync.model.AcaoAuditoria;
import br.com.varesync.model.NfceGravada;
import br.com.varesync.model.StatusNfce;
import br.com.varesync.service.nfce.CertificadoA1;
import br.com.varesync.service.nfce.RespostaSefaz;
import br.com.varesync.service.nfce.Sefaz;
import br.com.varesync.service.nfce.SemComunicacaoException;
import br.com.varesync.util.Textos;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancelamento da venda com NFC-e (F23.8), pelo {@link NfceDaVenda} do {@link VendaService}:
 * <ul>
 *   <li>AUTORIZADA, dentro de {@code nfce.prazo_cancelamento_minutos} contados da autorização: primeiro, fora da
 *   transação, o evento 110111, com o motivo do cancelamento como justificativa; depois, na transação da venda, a
 *   NFC-e CANCELADA, o evento (procEventoNFe) e a venda cancelada. Recusa ou falta de resposta da SEFAZ: a venda não é
 *   cancelada. A duplicidade de evento (573) conta como cancelada: o primeiro pedido chegou, mas a gravação falhou;</li>
 *   <li>fora do prazo: recusa, e a orientação é a devolução;</li>
 *   <li>PENDENTE ou CONTINGENCIA: recusa até a nota ser autorizada;</li>
 *   <li>REJEITADA: a venda é cancelada, e o transmissor inutiliza o número; DENEGADA: cancelada sem evento.</li>
 * </ul>
 */
public class CancelamentoNfceService {

    private static final Logger LOG = LoggerFactory.getLogger(CancelamentoNfceService.class);
    static final int JUSTIFICATIVA_MINIMA = 15;
    static final int JUSTIFICATIVA_MAXIMA = 255;
    private static final Set<StatusNfce> VIGENTES = EnumSet.of(StatusNfce.PENDENTE, StatusNfce.AUTORIZADA,
            StatusNfce.CONTINGENCIA, StatusNfce.REJEITADA);

    private final NfceDAO nfceDAO;
    private final ConfiguracaoDAO configuracaoDAO;
    private final EmissaoNfceService emissao;
    private final CertificadoService certificados;
    private final Sefaz sefaz;
    private final AuditoriaService auditoria;
    private final Clock clock;

    public CancelamentoNfceService(NfceDAO nfceDAO, ConfiguracaoDAO configuracaoDAO, EmissaoNfceService emissao,
                                   CertificadoService certificados, Sefaz sefaz, AuditoriaService auditoria,
                                   Clock clock) {
        this.nfceDAO = nfceDAO;
        this.configuracaoDAO = configuracaoDAO;
        this.emissao = emissao;
        this.certificados = certificados;
        this.sefaz = sefaz;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    /** Ver {@link NfceDaVenda#conferirCancelamento}. */
    public NfceDaVenda.Cancelamento conferir(Connection con, long vendaId, String justificativa) throws SQLException {
        List<NfceGravada> notas = nfceDAO.daVenda(con, vendaId);
        Optional<NfceGravada> semResposta = notas.stream().filter(n -> n.status() == StatusNfce.SEM_RESPOSTA)
                .findFirst();
        if (semResposta.isPresent()) {
            // A original sem resposta ainda é resolvida pelo transmissor (110112 ou inutilização), que precisa da de
            // contingência autorizada: cancelar a venda antes a deixaria sem solução.
            throw new NegocioException("A venda nº " + vendaId + " tem a NFC-e nº " + semResposta.get().numero()
                    + " sem resposta da SEFAZ, que ainda está sendo resolvida. Use \"Transmitir agora\" em Fiscal › "
                    + "Notas fiscais e tente de novo.");
        }
        Optional<NfceGravada> vigente = notas.stream().filter(n -> VIGENTES.contains(n.status())).findFirst();
        if (vigente.isEmpty()) {
            return null; // sem NFC-e, ou DENEGADA (o número já está gasto)
        }
        NfceGravada n = vigente.get();
        return switch (n.status()) {
            case PENDENTE -> throw new NegocioException("A NFC-e nº " + n.numero() + " desta venda ainda aguarda a "
                    + "resposta da SEFAZ. Tente de novo em alguns minutos.");
            case CONTINGENCIA -> throw new NegocioException("A NFC-e nº " + n.numero() + " desta venda foi emitida "
                    + "em contingência e ainda não foi autorizada: a venda só pode ser cancelada depois da "
                    + "autorização. Use \"Transmitir agora\" em Fiscal › Notas fiscais e tente de novo.");
            case REJEITADA -> null; // o transmissor inutiliza o número
            case AUTORIZADA -> autorizada(con, n, justificativa);
            default -> null;
        };
    }

    private NfceDaVenda.Cancelamento autorizada(Connection con, NfceGravada n, String justificativa)
            throws SQLException {
        int prazo = configuracaoDAO.carregar(con).nfce().prazoCancelamentoMinutos();
        OffsetDateTime agora = OffsetDateTime.now(clock);
        if (Duration.between(n.autorizadaEm(), agora).compareTo(Duration.ofMinutes(prazo)) > 0) {
            throw new NegocioException("A NFC-e foi autorizada há mais de " + prazo + " minutos e não pode mais ser "
                    + "cancelada. Registre uma devolução.");
        }
        String texto = Textos.normalizarEspacos(justificativa);
        if (texto.length() < JUSTIFICATIVA_MINIMA || texto.length() > JUSTIFICATIVA_MAXIMA) {
            throw new NegocioException("Para cancelar a NFC-e, o motivo precisa ter de " + JUSTIFICATIVA_MINIMA
                    + " a " + JUSTIFICATIVA_MAXIMA + " caracteres (exigência da SEFAZ). Escreva o motivo com mais "
                    + "detalhes.");
        }
        return new NfceDaVenda.Cancelamento(n.id(), n.numero(), n.serie(), n.chave(), n.protocolo(), texto);
    }

    /** Ver {@link NfceDaVenda#cancelarNaSefaz}. */
    public RespostaSefaz cancelarNaSefaz(NfceDaVenda.Cancelamento c) {
        CertificadoA1 certificado;
        try {
            certificado = certificados.doTerminal();
        } catch (NegocioException e) {
            throw new NegocioException("A NFC-e nº " + c.numero() + " não pode ser cancelada: " + e.getMessage());
        }
        RespostaSefaz r;
        try {
            r = sefaz.cancelar(emissao.contexto(certificado), c.chave(), c.protocolo(), c.justificativa(),
                    OffsetDateTime.now(clock));
        } catch (SemComunicacaoException e) {
            LOG.info("NFC-e nº {}, cancelamento sem resposta da SEFAZ ({})", c.numero(), e.causa());
            throw new NegocioException("A SEFAZ não respondeu ao cancelamento da NFC-e nº " + c.numero() + " ("
                    + e.causa() + "): a venda não foi cancelada. Tente de novo em alguns minutos.");
        }
        LOG.info("NFC-e nº {}, cancelamento: cStat {} - {}", c.numero(), r.cstat(), r.motivo());
        if (!r.eventoRegistrado()) {
            throw new NegocioException("A SEFAZ recusou o cancelamento da NFC-e nº " + c.numero() + " (cStat "
                    + r.descricao() + "): a venda não foi cancelada.");
        }
        return r;
    }

    /** Ver {@link NfceDaVenda#registrarCancelamento}. */
    public void registrar(Connection con, Sessao sessao, NfceDaVenda.Cancelamento c, RespostaSefaz evento)
            throws SQLException {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        NfceGravada n = nfceDAO.buscar(con, c.nfceId(), true)
                .orElseThrow(() -> new NegocioException("NFC-e não encontrada."));
        if (!nfceDAO.cancelada(con, n.id(), evento.cstat(), evento.motivo(), agora)) {
            throw new NegocioException("A NFC-e nº " + n.numero() + " está " + n.status().rotulo().toLowerCase()
                    + ": o cancelamento não foi gravado. Tente de novo.");
        }
        nfceDAO.inserirEvento(con, n.id(), "CANCELAMENTO", c.justificativa(), evento.cstat(), evento.motivo(),
                evento.protocolo(), evento.xml(), agora, sessao.usuarioId());
        auditoria.registrar(con, sessao, AcaoAuditoria.NFCE_CANCELADA, NfceService.ENTIDADE, n.id(),
                "NFC-e nº " + n.numero() + " (série " + n.serie() + ") da venda nº " + n.vendaId()
                        + " cancelada pelo evento 110111 (cStat " + evento.cstat()
                        + (evento.protocolo() == null ? "" : ", protocolo " + evento.protocolo()) + "). Motivo: "
                        + c.justificativa());
    }
}
