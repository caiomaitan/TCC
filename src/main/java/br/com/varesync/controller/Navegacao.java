package br.com.varesync.controller;

import br.com.varesync.service.Sessao;

/** Troca de telas da sequência de acesso (3.4), implementada pela {@code App}. */
public interface Navegacao {

    /** Tela de login, com o campo Usuário já preenchido (pode ser vazio). */
    void mostrarLogin(String loginSugerido);

    /** Login aceito: troca de senha obrigatória, se marcada, ou janela principal. */
    void entrar(Sessao sessao);

    /** Registra o logout da sessão atual e volta ao login. */
    void logoff();

    /** Encerra o sistema (o logout é registrado ao fechar). */
    void sair();
}
