package br.com.varesync.service.nfce;

import java.time.ZoneId;

/**
 * O que toda chamada à SEFAZ precisa (F23.4): a UF e o CNPJ da loja, o certificado deste terminal e o tempo de espera
 * ({@code nfce.timeout_segundos}).
 */
public record ContextoSefaz(String uf, String cnpj, CertificadoA1 certificado, int timeoutSegundos, ZoneId fuso) {

    /** Sem o certificado e o CNPJ no texto: pode ir para o log. */
    @Override
    public String toString() {
        return "ContextoSefaz[uf=" + uf + ", timeout=" + timeoutSegundos + "s]";
    }
}
