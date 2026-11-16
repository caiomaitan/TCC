package br.com.varesync.service.nfce;

import br.com.swconsultoria.nfe.Nfe;
import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.Evento;
import br.com.swconsultoria.nfe.dom.enuns.AmbienteEnum;
import br.com.swconsultoria.nfe.dom.enuns.DocumentoEnum;
import br.com.swconsultoria.nfe.exception.NfeException;
import br.com.swconsultoria.nfe.schemas.TEnviNFe;
import br.com.swconsultoria.nfe.util.CancelamentoSubstituicaoUtil;
import br.com.swconsultoria.nfe.util.CancelamentoUtil;
import br.com.swconsultoria.nfe.util.InutilizacaoUtil;
import br.com.swconsultoria.nfe.util.XmlNfeUtil;
import br.com.varesync.service.NegocioException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SEFAZ de homologação da UF, pela biblioteca Java_NFe (F23.4). Só tpAmb 2: a configuração da biblioteca é sempre
 * criada em homologação e a nota de produção é recusada aqui, antes de qualquer envio (além do CHECK do banco).
 *
 * <p>Sem comunicação: qualquer falha da biblioteca na chamada (sem conexão, tempo esgotado, erro HTTP, resposta que não
 * se lê) e o serviço paralisado viram {@link SemComunicacaoException}; na dúvida, a nota pode ter chegado, e a
 * contingência e a consulta pela chave resolvem (F23.5).
 *
 * <p>As chamadas passam pela mesma trava da assinatura: a biblioteca guarda o certificado em campos estáticos.
 *
 * <p>Log: só o cStat e o motivo; nunca o certificado, a senha ou o XML (o log da biblioteca, que traz o XML, fica
 * desligado por {@link AssinaturaNfce}).
 */
public final class SefazHomologacao implements Sefaz {

    private static final Logger LOG = LoggerFactory.getLogger(SefazHomologacao.class);
    private static final Pattern TP_AMB = Pattern.compile("<(?:\\w+:)?tpAmb>\\s*(\\d)\\s*</(?:\\w+:)?tpAmb>");

    private final AssinaturaNfce assinatura;

    public SefazHomologacao(AssinaturaNfce assinatura) {
        this.assinatura = assinatura;
    }

    @FunctionalInterface
    private interface Chamada {
        RespostaSefaz executar(ConfiguracoesNfe config) throws NfeException;
    }

    private RespostaSefaz chamar(String servico, ContextoSefaz contexto, Chamada chamada) {
        ConfiguracoesNfe config = assinatura.configuracao(contexto.uf(), contexto.certificado());
        exigirHomologacao(config);
        config.setTimeout(Math.max(1, contexto.timeoutSegundos()) * 1000);
        RespostaSefaz resposta;
        synchronized (AssinaturaNfce.TRAVA) {
            try {
                resposta = chamada.executar(config);
            } catch (NfeException | RuntimeException e) {
                if (e instanceof NegocioException n) {
                    throw n;
                }
                LOG.warn("SEFAZ sem comunicação em {}: {}", servico, causas(e));
                throw new SemComunicacaoException("Sem comunicação com a SEFAZ (" + servico + ")");
            }
        }
        LOG.info("SEFAZ {}: cStat {} - {}", servico, resposta.cstat(), resposta.motivo());
        return resposta;
    }

    /** Só os nomes das classes das causas: as mensagens da biblioteca podem trazer o XML. */
    static String causas(Throwable e) {
        StringBuilder s = new StringBuilder();
        for (Throwable c = e; c != null && s.length() < 300; c = c.getCause()) {
            if (!s.isEmpty()) {
                s.append(" <- ");
            }
            s.append(c.getClass().getSimpleName());
            if (c.getCause() == c) {
                break;
            }
        }
        return s.toString();
    }

    static void exigirHomologacao(ConfiguracoesNfe config) {
        if (config.getAmbiente() != AmbienteEnum.HOMOLOGACAO) {
            throw new NegocioException("O sistema só emite NFC-e em homologação.");
        }
    }

    /** Recusa a nota que não é de homologação (tpAmb 2), antes de qualquer envio. */
    static void exigirHomologacao(String nfe) {
        Matcher m = TP_AMB.matcher(nfe == null ? "" : nfe);
        if (!m.find() || !MontagemNfce.AMBIENTE_HOMOLOGACAO.equals(m.group(1))) {
            throw new NegocioException("O sistema só emite NFC-e em homologação: a nota de produção foi recusada.");
        }
    }

    @Override
    public RespostaSefaz status(ContextoSefaz contexto) {
        return chamar("status", contexto, c -> RespostasSefaz.status(Nfe.statusServico(c, DocumentoEnum.NFCE)));
    }

    @Override
    public RespostaSefaz autorizar(ContextoSefaz contexto, String nfeAssinada, String idLote) {
        exigirHomologacao(nfeAssinada);
        TEnviNFe lote = XmlNfeUtil.xmlToObject(AssinaturaNfce.loteCom(RespostasSefaz.semDeclaracao(nfeAssinada),
                idLote), TEnviNFe.class);
        return chamar("autorização", contexto, c -> RespostasSefaz.autorizacao(Nfe.enviarNfe(c, lote,
                DocumentoEnum.NFCE)));
    }

    @Override
    public RespostaSefaz consultar(ContextoSefaz contexto, String chave) {
        return chamar("consulta", contexto, c -> RespostasSefaz.consulta(Nfe.consultaXml(c, chave,
                DocumentoEnum.NFCE)));
    }

    @Override
    public RespostaSefaz cancelar(ContextoSefaz contexto, String chave, String protocolo, String justificativa,
                                  OffsetDateTime quando) {
        return chamar("cancelamento", contexto, c -> {
            Evento evento = evento(contexto, chave, protocolo, justificativa, quando, c.getZoneId());
            return RespostasSefaz.cancelamento(Nfe.cancelarNfe(c, CancelamentoUtil.montaCancelamento(evento, c),
                    false, DocumentoEnum.NFCE));
        });
    }

    @Override
    public RespostaSefaz cancelarPorSubstituicao(ContextoSefaz contexto, String chave, String protocolo,
                                                 String chaveSubstituta, String justificativa,
                                                 OffsetDateTime quando) {
        return chamar("cancelamento por substituição", contexto, c -> {
            Evento evento = evento(contexto, chave, protocolo, justificativa, quando, c.getZoneId());
            evento.setChaveSusbstituta(chaveSubstituta);
            return RespostasSefaz.cancelamentoSubstituicao(Nfe.cancelarSubstituicaoNfe(c,
                    CancelamentoSubstituicaoUtil.montaCancelamento(evento, c), false));
        });
    }

    @Override
    public RespostaSefaz inutilizar(ContextoSefaz contexto, int serie, int numeroInicial, int numeroFinal,
                                   String justificativa, OffsetDateTime quando) {
        return chamar("inutilização", contexto, c -> RespostasSefaz.inutilizacao(Nfe.inutilizacao(c,
                InutilizacaoUtil.montaInutilizacao(DocumentoEnum.NFCE, contexto.cnpj(), serie, numeroInicial,
                        numeroFinal, TextoNfe.limpar(justificativa, 255),
                        quando.atZoneSameInstant(zona(c)).toLocalDateTime(), c),
                DocumentoEnum.NFCE, false)));
    }

    private static Evento evento(ContextoSefaz contexto, String chave, String protocolo, String justificativa,
                                 OffsetDateTime quando, ZoneId zona) {
        Evento evento = new Evento();
        evento.setChave(chave);
        evento.setProtocolo(protocolo);
        evento.setMotivo(TextoNfe.limpar(justificativa, 255));
        evento.setCnpj(contexto.cnpj());
        evento.setDataEvento(quando.atZoneSameInstant(zona == null ? contexto.fuso() : zona).toLocalDateTime());
        return evento;
    }

    private static ZoneId zona(ConfiguracoesNfe c) {
        return c.getZoneId() == null ? ZoneId.systemDefault() : c.getZoneId();
    }
}
