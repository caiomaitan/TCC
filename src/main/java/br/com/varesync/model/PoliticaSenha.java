package br.com.varesync.model;

/**
 * Política de senha (F10.6): tamanho mínimo ({@code seguranca.senha_tamanho_minimo}) e, se ligado, letras e números
 * ({@code seguranca.senha_letra_e_numero}). Vale para senhas novas e trocadas; as antigas continuam valendo até a
 * próxima troca. O limite de 50 caracteres (71 bytes, o do BCrypt) continua.
 */
public record PoliticaSenha(int tamanhoMinimo, boolean letraENumero) {

    /** Menor tamanho mínimo aceito na configuração: o da regra original (6.1). */
    public static final int TAMANHO_MINIMO = 6;
    public static final int TAMANHO_MAXIMO = 50;
    public static final PoliticaSenha PADRAO = new PoliticaSenha(TAMANHO_MINIMO, false);

    /** "A senha deve ter ao menos 8 caracteres, com letras e números, e ser diferente do login." */
    public String descricao() {
        return "A senha deve ter ao menos " + tamanhoMinimo + " caracteres"
                + (letraENumero ? ", com letras e números," : "") + " e ser diferente do login.";
    }

    /** A senha atende ao tamanho mínimo e, se pedido, tem ao menos uma letra e um dígito? */
    public boolean atende(String senha) {
        if (senha == null || senha.length() < tamanhoMinimo) {
            return false;
        }
        return !letraENumero || (senha.chars().anyMatch(Character::isLetter)
                && senha.chars().anyMatch(Character::isDigit));
    }
}
