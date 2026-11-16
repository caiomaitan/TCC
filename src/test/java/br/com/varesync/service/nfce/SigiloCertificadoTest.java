package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.varesync.config.AppConfig;
import br.com.varesync.model.TipoEmissaoNfce;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * F23.2 e aceite da parte A: o certificado, a senha e a chave privada não aparecem no log (o do sistema, pelo SLF4J,
 * e o da biblioteca, pelo java.util.logging) nem nas mensagens, nem quando a senha está errada, o arquivo some ou a
 * nota é assinada.
 */
class SigiloCertificadoTest {

    @TempDir
    static Path pasta;

    private ListAppender<ILoggingEvent> logSistema;
    private final List<String> logBiblioteca = new ArrayList<>();
    private Handler capturaBiblioteca;
    private final List<String> mensagens = new ArrayList<>();

    @BeforeEach
    void capturar() {
        Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logSistema = new ListAppender<>();
        logSistema.start();
        raiz.addAppender(logSistema);
        raiz.setLevel(Level.DEBUG);
        capturaBiblioteca = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logBiblioteca.add(r.getMessage() + (r.getThrown() == null ? "" : " " + r.getThrown()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capturaBiblioteca.setLevel(java.util.logging.Level.ALL);
        java.util.logging.Logger.getLogger("").addHandler(capturaBiblioteca);
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.ALL);
    }

    @AfterEach
    void soltar() {
        Logger raiz = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        raiz.detachAppender(logSistema);
        raiz.setLevel(Level.INFO);
        java.util.logging.Logger.getLogger("").removeHandler(capturaBiblioteca);
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.INFO);
    }

    private void tentar(Runnable acao) {
        Throwable erro = catchThrowable(acao::run);
        for (Throwable t = erro; t != null; t = t.getCause()) {
            mensagens.add(t.toString());
        }
    }

    private static void juntarCausas(List<String> destino, IThrowableProxy t) {
        for (IThrowableProxy p = t; p != null; p = p.getCause()) {
            destino.add(p.getClassName() + ": " + p.getMessage());
        }
    }

    @Test
    void nadaDoCertificadoNoLogNemNasMensagens() throws Exception {
        Path arquivo = CertificadoTeste.arquivo();
        CertificadoA1 certificado = CertificadoTeste.carregado();
        String chavePrivada = Base64.getEncoder().encodeToString(certificado.chavePrivada().getEncoded());
        String pfx = Base64.getEncoder().encodeToString(Files.readAllBytes(arquivo));
        Path caminhoSumido = pasta.resolve("certificado-sumido-da-loja.pfx");

        tentar(() -> CertificadoA1.ler(arquivo, "senha-errada-123"));
        tentar(() -> CertificadoA1.ler(caminhoSumido, CertificadoTeste.SENHA));
        tentar(() -> CertificadoA1.ler(CertificadoTeste.vencido(), CertificadoTeste.SENHA));
        ZoneId fuso = ZoneId.of("America/Sao_Paulo");
        ValidacaoXsd validacao = new ValidacaoXsd(pasta);
        GeracaoNfce geracao = new GeracaoNfce(new MontagemNfce(fuso), new AssinaturaNfce(validacao.pasta(), fuso),
                validacao);
        GeracaoNfce.Resultado r = geracao.gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(
                TipoEmissaoNfce.CONTINGENCIA_OFFLINE, null), certificado, "", "");
        assertThat(r.erros()).isEmpty();
        tentar(() -> new AssinaturaNfce(validacao.pasta(), fuso).configuracao("SP",
                CertificadoA1.ler(CertificadoTeste.vencido(), CertificadoTeste.SENHA)));
        AppConfig config = new AppConfig(pasta.resolve("config.properties"), null, null, "senha-do-banco", null,
                arquivo.toString(), CertificadoTeste.SENHA);
        mensagens.add(config.toString());
        mensagens.add(certificado.toString());

        List<String> tudo = new ArrayList<>(mensagens);
        tudo.addAll(logBiblioteca);
        for (ILoggingEvent e : logSistema.list) {
            tudo.add(e.getFormattedMessage());
            juntarCausas(tudo, e.getThrowableProxy());
        }
        assertThat(mensagens).as("houve mensagens a conferir").hasSizeGreaterThan(4);
        assertThat(logBiblioteca).as("a biblioteca registrou no log").isNotEmpty();
        for (String linha : tudo) {
            assertThat(linha).doesNotContain(CertificadoTeste.SENHA).doesNotContain("senha-errada-123")
                    .doesNotContain(arquivo.toString()).doesNotContain(arquivo.getFileName().toString())
                    .doesNotContain("certificado-sumido-da-loja").doesNotContain(chavePrivada.substring(0, 40))
                    .doesNotContain(pfx.substring(0, 40)).doesNotContain("senha-do-banco");
        }
    }
}
