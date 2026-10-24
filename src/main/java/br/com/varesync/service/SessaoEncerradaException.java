package br.com.varesync.service;

/**
 * O acesso do usuário logado foi encerrado por outro computador (F10.1): usuário inativado ou com a senha redefinida
 * pelo supervisor. A interface faz o log off e abre o login.
 */
public class SessaoEncerradaException extends NegocioException {

    public static final String MENSAGEM = "Seu acesso foi alterado pelo supervisor. Entre novamente.";

    public SessaoEncerradaException() {
        super(MENSAGEM);
    }
}
