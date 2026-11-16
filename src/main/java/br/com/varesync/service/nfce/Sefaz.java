package br.com.varesync.service.nfce;

import java.time.OffsetDateTime;

/**
 * Os serviços da SEFAZ que a NFC-e usa (F23.4), sempre em homologação. Sem comunicação (sem conexão, tempo esgotado,
 * erro HTTP ou serviço paralisado) é {@link SemComunicacaoException}. Duas implementações: {@link SefazHomologacao},
 * pela biblioteca Java_NFe, e a SEFAZ falsa dos testes.
 */
public interface Sefaz {

    /** Status do serviço (107 = em operação). */
    RespostaSefaz status(ContextoSefaz contexto);

    /**
     * Autorização síncrona de uma nota (lote de uma, indSinc 1: a SEFAZ recusa resposta assíncrona para uma nota só,
     * rejeição 452).
     *
     * @param nfeAssinada o elemento {@code NFe} assinado, como está gravado
     */
    RespostaSefaz autorizar(ContextoSefaz contexto, String nfeAssinada, String idLote);

    /** Consulta pela chave; autorizada, a resposta traz o protocolo e o {@code protNFe}. */
    RespostaSefaz consultar(ContextoSefaz contexto, String chave);

    /** Evento 110111, cancelamento. */
    RespostaSefaz cancelar(ContextoSefaz contexto, String chave, String protocolo, String justificativa,
                           OffsetDateTime quando);

    /** Evento 110112, cancelamento por substituição (a de contingência ficou no lugar desta). */
    RespostaSefaz cancelarPorSubstituicao(ContextoSefaz contexto, String chave, String protocolo,
                                          String chaveSubstituta, String justificativa, OffsetDateTime quando);

    /** Inutilização de uma faixa de números da série, no ano de {@code quando}. */
    RespostaSefaz inutilizar(ContextoSefaz contexto, int serie, int numeroInicial, int numeroFinal,
                             String justificativa, OffsetDateTime quando);
}
