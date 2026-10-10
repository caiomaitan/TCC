package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SenhasTest {

    private final Senhas senhas = new Senhas(4);

    @Test
    void producaoUsaBcryptComCusto12() {
        Senhas producao = new Senhas();
        assertThat(producao.custo()).isEqualTo(12);
        assertThat(producao.gerarHash("segredo1")).startsWith("$2a$12$").hasSize(60);
    }

    @Test
    void hashNaoContemASenhaEConfere() {
        String hash = senhas.gerarHash("segredo1");

        assertThat(hash).doesNotContain("segredo1");
        assertThat(senhas.confere("segredo1", hash)).isTrue();
        assertThat(senhas.confere("Segredo1", hash)).isFalse();
        assertThat(senhas.gerarHash("segredo1")).as("sal aleatório").isNotEqualTo(hash);
    }

    @Test
    void hashInvalidoOuAusenteNaoConfere() {
        assertThat(senhas.confere("segredo1", null)).isFalse();
        assertThat(senhas.confere("segredo1", "")).isFalse();
        assertThat(senhas.confere("segredo1", "hash-corrompido")).isFalse();
        assertThat(senhas.confere(null, senhas.gerarHash("segredo1"))).isFalse();
    }

    @Test
    void senhaNovaTemAoMenosSeisCaracteres() {
        assertThatThrownBy(() -> senhas.validarNova("maria", "12345", "12345"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A senha deve ter ao menos 6 caracteres.");
        assertThatThrownBy(() -> senhas.validarNova("maria", null, null)).isInstanceOf(NegocioException.class);
        assertThatCode(() -> senhas.validarNova("maria", "123456", "123456")).doesNotThrowAnyException();
    }

    @Test
    void senhaNovaDiferenteDoLoginSemDiferenciarMaiusculas() {
        assertThatThrownBy(() -> senhas.validarNova("mariana", "MARIANA", "MARIANA"))
                .hasMessage("A senha não pode ser igual ao login.");
        assertThatThrownBy(() -> senhas.validarNova("mariana", " mariana ", " mariana "))
                .hasMessage("A senha não pode ser igual ao login.");
    }

    @Test
    void confirmacaoTemDeConferir() {
        assertThatThrownBy(() -> senhas.validarNova("maria", "segredo1", "segredo2"))
                .hasMessage("A confirmação não confere com a senha.");
    }

    @Test
    void senhaLongaDemaisERecusada() {
        assertThatThrownBy(() -> senhas.validarNova("maria", "a".repeat(51), "a".repeat(51)))
                .hasMessageStartingWith("A senha deve ter no máximo 50 caracteres");
        // 40 caracteres acentuados ocupam 80 bytes: passa do limite do BCrypt.
        assertThatThrownBy(() -> senhas.validarNova("maria", "é".repeat(40), "é".repeat(40)))
                .hasMessageStartingWith("A senha deve ter no máximo 50 caracteres");
        assertThatCode(() -> senhas.validarNova("maria", "a".repeat(50), "a".repeat(50)))
                .doesNotThrowAnyException();
    }
}
