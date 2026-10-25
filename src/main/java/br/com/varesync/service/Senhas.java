package br.com.varesync.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import br.com.varesync.model.PoliticaSenha;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hash BCrypt das senhas e regras de senha nova (6.1 e F10.6): a política configurada (tamanho mínimo e, se ligado,
 * letras e números), no máximo 50 caracteres e diferente do login.
 * A senha em texto nunca é gravada nem registrada no log.
 */
public final class Senhas {

    private static final Logger log = LoggerFactory.getLogger(Senhas.class);

    public static final int CUSTO_PADRAO = 12;
    public static final int TAMANHO_MAXIMO = PoliticaSenha.TAMANHO_MAXIMO;
    /** Limite do BCrypt (versão 2a): 72 bytes menos o terminador. */
    private static final int BYTES_MAXIMO = 71;

    private final int custo;
    private volatile String hashDeReferencia;

    /** BCrypt com custo 12 (3.1). */
    public Senhas() {
        this(CUSTO_PADRAO);
    }

    /** Custo menor só nos testes, para não gastar ~0,3 s por hash. */
    public Senhas(int custo) {
        this.custo = custo;
    }

    public int custo() {
        return custo;
    }

    public String gerarHash(String senha) {
        return BCrypt.withDefaults().hashToString(custo, senha.toCharArray());
    }

    /** Confere a senha com o hash. Hash vazio ou corrompido e senha longa demais dão {@code false}. */
    public boolean confere(String senha, String hash) {
        if (senha == null || hash == null || hash.isEmpty()
                || senha.getBytes(StandardCharsets.UTF_8).length > BYTES_MAXIMO) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(senha.toCharArray(), hash).verified;
        } catch (IllegalArgumentException invalido) {
            // Só o hash gravado no banco pode estar errado aqui (alterado fora do sistema); nem ele vai para o log.
            log.warn("Hash de senha em formato inválido no banco: tratado como senha errada");
            return false;
        }
    }

    /**
     * Gasta o mesmo tempo de uma conferência real quando o login não existe,
     * para o tempo de resposta não revelar quais logins existem.
     */
    public void simularConferencia(String senha) {
        if (hashDeReferencia == null) {
            hashDeReferencia = gerarHash("varesync-login-inexistente");
        }
        confere(senha == null ? "" : senha, hashDeReferencia);
    }

    /**
     * Valida uma senha nova pela política configurada (F10.6). Fora da política, a mensagem é a própria regra.
     *
     * @param login login já normalizado do dono da senha
     * @throws NegocioException com a mensagem para o usuário
     */
    public void validarNova(String login, String senha, String confirmacao, PoliticaSenha politica) {
        if (!politica.atende(senha)) {
            throw new NegocioException(politica.descricao());
        }
        if (senha.length() > TAMANHO_MAXIMO
                || senha.getBytes(StandardCharsets.UTF_8).length > BYTES_MAXIMO) {
            throw new NegocioException("A senha deve ter no máximo " + TAMANHO_MAXIMO
                    + " caracteres (com poucos acentos).");
        }
        if (login != null && senha.strip().equalsIgnoreCase(login.strip())) {
            throw new NegocioException("A senha não pode ser igual ao login.");
        }
        if (!senha.equals(confirmacao)) {
            throw new NegocioException("A confirmação não confere com a senha.");
        }
    }
}
