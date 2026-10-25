package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.PoliticaSenha;
import org.junit.jupiter.api.Test;

class SenhasTest {

    private final Senhas senhas = new Senhas(4);

    /** Com a política padrão (V5: 6 caracteres, sem exigir letras e números). */
    private void validar(String login, String senha, String confirmacao) {
        senhas.validarNova(login, senha, confirmacao, PoliticaSenha.PADRAO);
    }

    /** Aceite F10 (senha): com tamanho mínimo 8, "abc123" é recusada com a regra na mensagem. */
    @Test
    void politicaConfiguradaVaiNaMensagem() {
        PoliticaSenha oito = new PoliticaSenha(8, false);
        assertThatThrownBy(() -> senhas.validarNova("maria", "abc123", "abc123", oito))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A senha deve ter ao menos 8 caracteres e ser diferente do login.");
        assertThatCode(() -> senhas.validarNova("maria", "abcdefgh", "abcdefgh", oito)).doesNotThrowAnyException();

        PoliticaSenha letraENumero = new PoliticaSenha(8, true);
        String regra = "A senha deve ter ao menos 8 caracteres, com letras e números, e ser diferente do login.";
        assertThat(letraENumero.descricao()).isEqualTo(regra);
        for (String fora : new String[] {"abc123", "abcdefgh", "12345678", "!!!!!!!!"}) {
            assertThatThrownBy(() -> senhas.validarNova("maria", fora, fora, letraENumero)).as(fora).hasMessage(regra);
        }
        assertThatCode(() -> senhas.validarNova("maria", "abcdefg1", "abcdefg1", letraENumero))
                .doesNotThrowAnyException();
        assertThatCode(() -> senhas.validarNova("maria", "Ação2026", "Ação2026", letraENumero))
                .as("letra acentuada conta como letra").doesNotThrowAnyException();
        assertThatThrownBy(() -> senhas.validarNova("maria", "a1".repeat(26), "a1".repeat(26), letraENumero))
                .as("o limite de 50 caracteres continua").hasMessageStartingWith("A senha deve ter no máximo 50");
    }

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
        assertThatThrownBy(() -> validar("maria", "12345", "12345"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A senha deve ter ao menos 6 caracteres e ser diferente do login.");
        assertThatThrownBy(() -> validar("maria", null, null)).isInstanceOf(NegocioException.class);
        assertThatCode(() -> validar("maria", "123456", "123456")).doesNotThrowAnyException();
    }

    @Test
    void senhaNovaDiferenteDoLoginSemDiferenciarMaiusculas() {
        assertThatThrownBy(() -> validar("mariana", "MARIANA", "MARIANA"))
                .hasMessage("A senha não pode ser igual ao login.");
        assertThatThrownBy(() -> validar("mariana", " mariana ", " mariana "))
                .hasMessage("A senha não pode ser igual ao login.");
    }

    @Test
    void confirmacaoTemDeConferir() {
        assertThatThrownBy(() -> validar("maria", "segredo1", "segredo2"))
                .hasMessage("A confirmação não confere com a senha.");
    }

    @Test
    void senhaLongaDemaisERecusada() {
        assertThatThrownBy(() -> validar("maria", "a".repeat(51), "a".repeat(51)))
                .hasMessageStartingWith("A senha deve ter no máximo 50 caracteres");
        // 40 caracteres acentuados ocupam 80 bytes: passa do limite do BCrypt.
        assertThatThrownBy(() -> validar("maria", "é".repeat(40), "é".repeat(40)))
                .hasMessageStartingWith("A senha deve ter no máximo 50 caracteres");
        assertThatCode(() -> validar("maria", "a".repeat(50), "a".repeat(50)))
                .doesNotThrowAnyException();
    }
}
