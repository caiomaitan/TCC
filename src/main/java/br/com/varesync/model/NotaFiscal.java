package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Uma linha da aba Notas fiscais (F23.9) e do R32: a NFC-e com a venda.
 *
 * @param valor      o total da venda (vNF)
 * @param consumidor o CPF ou o CNPJ na nota, só dígitos; nulo = consumidor não identificado
 * @param mensagem   a última resposta (cStat e motivo) ou a recusa da validação local; nula sem resposta
 * @param temXml     o XML já foi montado (assinado ou, depois da autorização, o nfeProc)
 */
public record NotaFiscal(long id, long vendaId, int serie, int numero, OffsetDateTime emitidaEm, BigDecimal valor,
                         String consumidor, StatusNfce status, String protocolo, String mensagem, String chave,
                         TipoEmissaoNfce tipoEmissao, boolean temXml) {

    public boolean contingencia() {
        return tipoEmissao == TipoEmissaoNfce.CONTINGENCIA_OFFLINE;
    }

    /** "cStat 539 - Rejeicao: ..." ou só o motivo (recusa local, sem cStat). */
    public static String mensagem(String cstat, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return cstat == null ? null : "cStat " + cstat;
        }
        return cstat == null ? motivo : "cStat " + cstat + " - " + motivo;
    }
}
