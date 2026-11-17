package br.com.varesync.service.nfce;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Resposta da SEFAZ já traduzida (F23.4).
 *
 * @param cstat      o cStat da nota, do evento ou da inutilização (o do lote, quando não há o de dentro)
 * @param motivo     o xMotivo
 * @param protocolo  nProt, quando há
 * @param recebidoEm dhRecbto ou dhRegEvento, quando há
 * @param xml        na autorização, o {@code protNFe}; na consulta, o {@code protNFe} da nota autorizada; no evento
 *                   registrado, o procEventoNFe, e na inutilização homologada, o procInutNFe (o pedido assinado com o
 *                   retorno, F23.9); nas recusas, a resposta inteira; nulo quando não há
 */
public record RespostaSefaz(String cstat, String motivo, String protocolo, OffsetDateTime recebidoEm, String xml) {

    /** Uso denegado (o número fica gasto). */
    public static final Set<String> DENEGADA = Set.of("110", "301", "302", "303");
    /** Serviço paralisado: conta como sem comunicação. */
    public static final Set<String> PARALISADO = Set.of("108", "109");

    /** 100 (autorizado o uso) ou 150 (autorizado fora do prazo). */
    public boolean autorizada() {
        return "100".equals(cstat) || "150".equals(cstat);
    }

    public boolean denegada() {
        return DENEGADA.contains(cstat);
    }

    /** 107: serviço em operação. */
    public boolean emOperacao() {
        return "107".equals(cstat);
    }

    /** 217: a NF-e não consta na base da SEFAZ. */
    public boolean naoConsta() {
        return "217".equals(cstat);
    }

    /** 204: duplicidade de NF-e (a nota já chegou antes). */
    public boolean duplicidade() {
        return "204".equals(cstat);
    }

    /** 135 e 136 (evento registrado) ou 573 (duplicidade de evento: o primeiro pedido chegou). */
    public boolean eventoRegistrado() {
        return "135".equals(cstat) || "136".equals(cstat) || "573".equals(cstat);
    }

    /** 102: inutilização de número homologada. */
    public boolean inutilizada() {
        return "102".equals(cstat);
    }

    /** "cStat motivo", para o log e para a coluna motivo (sem o XML). */
    public String descricao() {
        return cstat + " - " + motivo;
    }
}
