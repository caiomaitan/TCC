package br.com.varesync.service.nfce;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Certificado A1 autoassinado dos testes (F23.2), gerado pelo {@code keytool} do JDK numa pasta temporária, com o CN
 * "EMPRESA TESTE LTDA:12345678000195". Nenhum arquivo de certificado vai para o repositório. Gerado uma vez por JVM.
 * A senha vai ao keytool pelo ambiente do processo ({@code -storepass:env}), nunca na linha de comando: o JDK 21
 * registra a linha de comando de todo {@code ProcessBuilder.start()} no log (nível DEBUG, logger
 * {@code java.lang.ProcessBuilder}), e o {@link SigiloCertificadoTest} confere que a senha não aparece em log nenhum.
 */
public final class CertificadoTeste {

    public static final String CNPJ = "12345678000195";
    public static final String SENHA = "senha-de-teste-123";
    private static final String VARIAVEL_SENHA = "VARESYNC_SENHA_CERTIFICADO_TESTE";
    private static Path arquivo;
    private static Path vencido;

    private CertificadoTeste() {
    }

    /**
     * O .pfx válido por 2 anos, a partir de 60 dias atrás (o relógio dos testes de integração está no passado, em
     * 06/10/2026).
     */
    public static synchronized Path arquivo() {
        if (arquivo == null) {
            arquivo = gerar("varesync-teste", "EMPRESA TESTE LTDA:" + CNPJ, 730, "-60d");
        }
        return arquivo;
    }

    /** Um .pfx que já venceu (de 2020, válido por 1 dia). */
    public static synchronized Path vencido() {
        if (vencido == null) {
            vencido = gerar("varesync-vencido", "EMPRESA TESTE LTDA:" + CNPJ, 1, "2020/01/01 00:00:00");
        }
        return vencido;
    }

    public static CertificadoA1 carregado() {
        return CertificadoA1.ler(arquivo(), SENHA);
    }

    private static Path gerar(String alias, String cn, int dias, String inicio) {
        try {
            Path pasta = Files.createTempDirectory("varesync-cert");
            pasta.toFile().deleteOnExit();
            Path pfx = pasta.resolve(alias + ".pfx");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            List<String> comando = new java.util.ArrayList<>(List.of(keytool, "-genkeypair", "-alias", alias,
                    "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA", "-dname",
                    "CN=" + cn + ", OU=Testes, O=VareSync, C=BR", "-validity", String.valueOf(dias),
                    "-storetype", "PKCS12", "-keystore", pfx.toString(), "-storepass:env", VARIAVEL_SENHA,
                    "-keypass:env", VARIAVEL_SENHA, "-noprompt"));
            if (inicio != null) {
                comando.add("-startdate");
                comando.add(inicio);
            }
            ProcessBuilder pb = new ProcessBuilder(comando).redirectErrorStream(true);
            pb.environment().put(VARIAVEL_SENHA, SENHA);
            Process p = pb.start();
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("keytool falhou: " + saida);
            }
            pfx.toFile().deleteOnExit();
            return pfx;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
