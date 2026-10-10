package br.com.varesync.service;

/** O usuário da sessão não tem a permissão exigida pelo service. */
public class PermissaoNegadaException extends NegocioException {

    public PermissaoNegadaException(String mensagem) {
        super(mensagem);
    }
}
