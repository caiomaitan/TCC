package br.com.varesync.service;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Modo contingência do terminal (F23.5), em memória: depois de uma emissão sem resposta da SEFAZ, as vendas seguintes
 * saem em contingência off-line, sem tentar a autorização, com dhCont = o início do modo, até o transmissor conseguir
 * falar com a SEFAZ (status 107). Um por terminal (montado no {@code AppContext}); a entrada e a saída vão para a
 * auditoria por quem chama.
 */
public final class ModoContingencia {

    private OffsetDateTime inicio;

    public synchronized boolean ativo() {
        return inicio != null;
    }

    public synchronized Optional<OffsetDateTime> inicio() {
        return Optional.ofNullable(inicio);
    }

    /** Entra no modo; devolve se entrou agora (falso se já estava). */
    synchronized boolean entrar(OffsetDateTime quando) {
        if (inicio != null) {
            return false;
        }
        inicio = quando;
        return true;
    }

    /** Sai do modo; devolve se saiu agora (falso se não estava). */
    synchronized boolean sair() {
        if (inicio == null) {
            return false;
        }
        inicio = null;
        return true;
    }
}
