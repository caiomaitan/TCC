package br.com.varesync.model;

import java.time.OffsetDateTime;

/**
 * Uma NFC-e gravada (F23.1).
 *
 * @param xml    o assinado (e, depois da autorização, o nfeProc); nulo até a montagem
 * @param qrcode nulo até a montagem
 */
public record NfceGravada(long id, long vendaId, int serie, int numero, String codigoNumerico, String chave,
                   TipoEmissaoNfce tipoEmissao, OffsetDateTime emitidaEm, OffsetDateTime contingenciaEm,
                   StatusNfce status, String cstat, String motivo, String protocolo, String xml, String qrcode) {
}
