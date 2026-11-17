package br.com.varesync.service.nfce;

import br.com.swconsultoria.nfe.schemas.TProtNFe;
import br.com.swconsultoria.nfe.schemas.TRetConsSitNFe;
import br.com.swconsultoria.nfe.schemas.TRetConsStatServ;
import br.com.swconsultoria.nfe.schemas.TRetEnviNFe;
import br.com.swconsultoria.nfe.schemas.TRetInutNFe;
import br.com.swconsultoria.nfe.schemas_eventos.TRetEnvEventoCancelamento;
import br.com.swconsultoria.nfe.schemas_eventos.TRetEnvEventoCancelamentoSubstituicao;
import br.com.swconsultoria.nfe.util.XmlNfeUtil;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Tradução das respostas da biblioteca Java_NFe para {@link RespostaSefaz} (F23.4), sem rede: testada com respostas
 * de exemplo. Serviço paralisado (cStat 108 e 109) vira {@link SemComunicacaoException}.
 */
public final class RespostasSefaz {

    private RespostasSefaz() {
    }

    public static RespostaSefaz status(TRetConsStatServ r) {
        return conferir(new RespostaSefaz(r.getCStat(), r.getXMotivo(), null, data(r.getDhRecbto()), null));
    }

    /**
     * Autorização síncrona: o lote processado (104) traz o protNFe, e vale o cStat de dentro; sem o protNFe, vale o do
     * lote (uma rejeição do lote inteiro).
     */
    public static RespostaSefaz autorizacao(TRetEnviNFe r) {
        TProtNFe prot = r.getProtNFe();
        if (prot == null || prot.getInfProt() == null) {
            return conferir(new RespostaSefaz(r.getCStat(), r.getXMotivo(), null, data(r.getDhRecbto()), null));
        }
        TProtNFe.InfProt inf = prot.getInfProt();
        return conferir(new RespostaSefaz(inf.getCStat(), inf.getXMotivo(), inf.getNProt(), data(inf.getDhRecbto()),
                xmlProtocolo(prot)));
    }

    /** Consulta pela chave: autorizada, com o protNFe; senão, o cStat da consulta (217 = não consta). */
    public static RespostaSefaz consulta(TRetConsSitNFe r) {
        TProtNFe prot = r.getProtNFe();
        if (prot != null && prot.getInfProt() != null) {
            TProtNFe.InfProt inf = prot.getInfProt();
            if ("100".equals(inf.getCStat()) || "150".equals(inf.getCStat())) {
                return new RespostaSefaz(inf.getCStat(), inf.getXMotivo(), inf.getNProt(), data(inf.getDhRecbto()),
                        xmlProtocolo(prot));
            }
        }
        return conferir(new RespostaSefaz(r.getCStat(), r.getXMotivo(), null, data(r.getDhRecbto()), null));
    }

    /** Evento 110111: o cStat do evento (135 registrado), ou o do lote quando não há o de dentro. */
    public static RespostaSefaz cancelamento(TRetEnvEventoCancelamento r) {
        if (!r.getRetEvento().isEmpty() && r.getRetEvento().get(0).getInfEvento() != null) {
            var inf = r.getRetEvento().get(0).getInfEvento();
            return conferir(new RespostaSefaz(inf.getCStat(), inf.getXMotivo(), inf.getNProt(),
                    data(inf.getDhRegEvento()), xml(r)));
        }
        return conferir(new RespostaSefaz(r.getCStat(), r.getXMotivo(), null, null, xml(r)));
    }

    /** Evento 110112, como o 110111. */
    public static RespostaSefaz cancelamentoSubstituicao(TRetEnvEventoCancelamentoSubstituicao r) {
        if (!r.getRetEvento().isEmpty() && r.getRetEvento().get(0).getInfEvento() != null) {
            var inf = r.getRetEvento().get(0).getInfEvento();
            return conferir(new RespostaSefaz(inf.getCStat(), inf.getXMotivo(), inf.getNProt(),
                    data(inf.getDhRegEvento()), xml(r)));
        }
        return conferir(new RespostaSefaz(r.getCStat(), r.getXMotivo(), null, null, xml(r)));
    }

    /** Inutilização: 102 = homologada. */
    public static RespostaSefaz inutilizacao(TRetInutNFe r) {
        var inf = r.getInfInut();
        return conferir(new RespostaSefaz(inf.getCStat(), inf.getXMotivo(), inf.getNProt(), data(inf.getDhRecbto()),
                xml(r)));
    }

    private static RespostaSefaz conferir(RespostaSefaz r) {
        if (r.cstat() == null || RespostaSefaz.PARALISADO.contains(r.cstat())) {
            throw new SemComunicacaoException(CausaSemComunicacao.paralisado(r.cstat()));
        }
        return r;
    }

    /** dhRecbto e dhRegEvento (AAAA-MM-DDThh:mm:ss±hh:mm); fora do formato, nulo. */
    static OffsetDateTime data(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(texto.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** O protNFe sozinho, sem a declaração XML (vai dentro do nfeProc). */
    static String xmlProtocolo(TProtNFe prot) {
        try {
            return semDeclaracao(XmlNfeUtil.objectToXml(prot, TProtNFe.class, "protNFe"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String xml(Object resposta) {
        try {
            return semDeclaracao(XmlNfeUtil.objectToXml(resposta));
        } catch (Exception e) {
            return null;
        }
    }

    static String semDeclaracao(String xml) {
        return xml == null ? null : xml.replaceFirst("^\\s*<\\?xml[^>]*\\?>\\s*", "").strip();
    }

    /**
     * O {@code nfeProc} (F23.1): a NFe assinada, como foi enviada, e o protocolo de autorização, que é o que fica
     * gravado e vai para o contador.
     */
    public static String nfeProc(String nfeAssinada, String protNFe) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\" "
                + "versao=\"" + MontagemNfce.VERSAO_LEIAUTE + "\">" + semDeclaracao(nfeAssinada)
                + semDeclaracao(protNFe) + "</nfeProc>";
    }
}
