package br.com.varesync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    @TempDir
    Path pasta;

    @Test
    void primeiraExecucaoCriaArquivoComPadroes() throws Exception {
        Path arquivo = pasta.resolve("sub").resolve("config.properties");

        AppConfig config = AppConfig.carregar(arquivo);

        assertThat(arquivo).exists();
        assertThat(config.url()).isEqualTo("jdbc:postgresql://localhost:5432/varesync");
        assertThat(config.usuario()).isEqualTo("varesync");
        assertThat(config.senha()).isEmpty();
        assertThat(config.terminalId()).isEqualTo("CAIXA-01");
        assertThat(Files.readString(arquivo, StandardCharsets.UTF_8))
                .contains("db.url=", "db.usuario=varesync", "db.senha=", "terminal.id=CAIXA-01");
    }

    @Test
    void salvaERelêConexaoMantendoOTerminal() throws Exception {
        Path arquivo = pasta.resolve("config.properties");
        Files.writeString(arquivo, "terminal.id=CAIXA-02\n", StandardCharsets.UTF_8);

        AppConfig alterada = AppConfig.carregar(arquivo)
                .comConexao("jdbc:postgresql://servidor:5433/loja", "loja", "s3nh@:ção");
        alterada.salvar();
        AppConfig relida = AppConfig.carregar(arquivo);

        assertThat(relida.url()).isEqualTo("jdbc:postgresql://servidor:5433/loja");
        assertThat(relida.usuario()).isEqualTo("loja");
        assertThat(relida.senha()).isEqualTo("s3nh@:ção");
        assertThat(relida.terminalId()).isEqualTo("CAIXA-02");
    }

    @Test
    void valoresEmBrancoUsamPadrao() throws Exception {
        Path arquivo = pasta.resolve("config.properties");
        Files.writeString(arquivo, "db.url=\ndb.usuario=  \n", StandardCharsets.UTF_8);

        AppConfig config = AppConfig.carregar(arquivo);

        assertThat(config.url()).isEqualTo(AppConfig.URL_PADRAO);
        assertThat(config.usuario()).isEqualTo(AppConfig.USUARIO_PADRAO);
        assertThat(config.terminalId()).isEqualTo(AppConfig.TERMINAL_PADRAO);
    }

    @Test
    void toStringNaoExpoeASenha() {
        AppConfig config = new AppConfig(pasta.resolve("c.properties"), null, null, "segredo-123", null);
        assertThat(config.toString()).doesNotContain("segredo-123");
    }

    /** F23.2: o certificado do terminal fica no arquivo e sobrevive à troca da conexão. */
    @Test
    void certificadoDoTerminalNoArquivo() throws Exception {
        Path arquivo = pasta.resolve("config.properties");
        AppConfig.carregar(arquivo).comCertificado("C:\\certs\\loja.pfx", "s3nh@-cert").salvar();
        AppConfig.carregar(arquivo).comConexao("jdbc:postgresql://outro:5432/loja", "loja", "x").salvar();

        AppConfig relida = AppConfig.carregar(arquivo);
        assertThat(relida.certificado()).isEqualTo("C:\\certs\\loja.pfx");
        assertThat(relida.senhaCertificadoDoArquivo()).isEqualTo("s3nh@-cert");
        assertThat(relida.url()).isEqualTo("jdbc:postgresql://outro:5432/loja");
        assertThat(relida.toString()).doesNotContain("s3nh@-cert").doesNotContain("loja.pfx");

        AppConfig.carregar(arquivo).comCertificado("", "qualquer").salvar();
        AppConfig sem = AppConfig.carregar(arquivo);
        assertThat(sem.certificado()).isEmpty();
        assertThat(sem.senhaCertificadoDoArquivo()).isEmpty();
        assertThat(Files.readString(arquivo, StandardCharsets.UTF_8)).doesNotContain("nfce.certificado");
    }

    /** A variável VARESYNC_CERT_SENHA, se existir, vale no lugar da senha do arquivo. */
    @Test
    void senhaDoCertificadoPelaVariavelDeAmbiente() {
        AppConfig config = new AppConfig(pasta.resolve("c.properties"), null, null, "", null, "C:\\a.pfx", "do-arquivo");
        assertThat(config.senhaCertificado(java.util.Map.of())).isEqualTo("do-arquivo");
        assertThat(config.senhaCertificado(java.util.Map.of(AppConfig.VARIAVEL_SENHA_CERTIFICADO, "da-variavel")))
                .isEqualTo("da-variavel");
    }
}
