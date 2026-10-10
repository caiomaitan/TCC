package br.com.varesync.service;

import br.com.varesync.util.Textos;
import java.util.regex.Pattern;

/** Normaliza e valida login e nome antes de gravar, com as mesmas regras dos CHECK de {@code usuario}. */
final class ValidacaoUsuario {

    static final int TAMANHO_MAXIMO_NOME = 80;
    static final String LOGIN_INVALIDO =
            "O login deve ter de 3 a 30 caracteres: letras minúsculas, números, ponto, hífen ou sublinhado.";

    /** Mesmo padrão de {@code ck_usuario_login}. */
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9._-]{3,30}");

    private ValidacaoUsuario() {
    }

    /** @return o login normalizado (sem espaços nas pontas e minúsculo) */
    static String login(String digitado) {
        String login = Textos.normalizarLogin(digitado);
        if (!LOGIN.matcher(login).matches()) {
            throw new NegocioException(LOGIN_INVALIDO);
        }
        return login;
    }

    /** @return o nome sem espaços nas pontas nem duplicados */
    static String nome(String digitado) {
        String nome = Textos.normalizarEspacos(digitado);
        if (nome.isEmpty()) {
            throw new NegocioException("Informe o nome do usuário.");
        }
        if (nome.length() > TAMANHO_MAXIMO_NOME) {
            throw new NegocioException("O nome pode ter no máximo " + TAMANHO_MAXIMO_NOME + " caracteres.");
        }
        return nome;
    }
}
