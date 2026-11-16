package br.com.varesync.model;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * O que a tela mostra do certificado A1 do terminal (F23.2), sem o caminho, a senha nem a chave.
 *
 * @param raizConfere se a raiz do CNPJ (8 dígitos) bate com a da loja
 * @param problema    o que impede a emissão (vencido) ou pede atenção (CNPJ de outra empresa), ou vazio
 */
public record InfoCertificado(String titular, String cnpj, OffsetDateTime validoAte, long diasParaVencer,
                              boolean vencido, boolean raizConfere, Optional<String> problema) {
}
