package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.service.NegocioException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Certificado A1 do terminal (F23.2), com o certificado autoassinado gerado pelo keytool. */
class CertificadoA1Test {

    private static final Clock AGORA = Clock.systemUTC();

    @TempDir
    Path pasta;

    @Test
    void titularCnpjVencimentoERaiz() {
        CertificadoA1 c = CertificadoTeste.carregado();
        assertThat(c.titular()).isEqualTo("EMPRESA TESTE LTDA");
        assertThat(c.cnpj()).isEqualTo("12345678000195");
        assertThat(c.vencido(AGORA)).isFalse();
        assertThat(c.diasParaVencer(AGORA)).isBetween(660L, 680L);
        assertThat(c.raizConfere("12345678000276")).as("mesma raiz, outra filial").isTrue();
        assertThat(c.raizConfere("11222333000181")).isFalse();
        assertThat(c.problema(AGORA, "12345678000195")).isEmpty();
        assertThat(c.problema(AGORA, "11222333000181")).contains("O CNPJ do certificado não é o da loja.");
    }

    @Test
    void vencido() {
        CertificadoA1 c = CertificadoA1.ler(CertificadoTeste.vencido(), CertificadoTeste.SENHA);
        assertThat(c.vencido(AGORA)).isTrue();
        assertThat(c.problema(AGORA, "12345678000195")).contains("O certificado está vencido.");
        Clock em2020 = Clock.fixed(Instant.parse("2020-01-01T12:00:00Z"), ZoneId.of("UTC"));
        assertThat(c.vencido(em2020)).isFalse();
    }

    @Test
    void senhaErradaSemMostrarASenha() {
        assertThatThrownBy(() -> CertificadoA1.ler(CertificadoTeste.arquivo(), "senha-errada-xyz"))
                .isInstanceOf(NegocioException.class).hasMessage("A senha do certificado não confere.");
    }

    @Test
    void arquivoQueNaoExisteSemMostrarOCaminho() {
        Path inexistente = pasta.resolve("segredo-da-loja.pfx");
        assertThatThrownBy(() -> CertificadoA1.ler(inexistente, CertificadoTeste.SENHA))
                .isInstanceOf(NegocioException.class).hasMessageContaining("não foi encontrado")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("segredo-da-loja"));
    }

    @Test
    void arquivoQueNaoECertificado() throws Exception {
        Path texto = Files.writeString(pasta.resolve("nota.pfx"), "isto não é um certificado");
        assertThatThrownBy(() -> CertificadoA1.ler(texto, "x")).isInstanceOf(NegocioException.class)
                .hasMessage("O arquivo não é um certificado A1 válido (.pfx ou .p12).");
    }

    @Test
    void toStringSemSenhaNemCaminho() {
        String texto = CertificadoTeste.carregado().toString();
        assertThat(texto).contains("EMPRESA TESTE LTDA").doesNotContain(CertificadoTeste.SENHA)
                .doesNotContain(CertificadoTeste.arquivo().toString());
    }
}
