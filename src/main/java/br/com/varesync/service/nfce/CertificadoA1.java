package br.com.varesync.service.nfce;

import br.com.swconsultoria.certificado.Certificado;
import br.com.swconsultoria.certificado.CertificadoService;
import br.com.swconsultoria.certificado.exception.CertificadoException;
import br.com.varesync.service.NegocioException;
import br.com.varesync.util.Textos;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Optional;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/**
 * Certificado A1 do terminal (F23.2): o arquivo .pfx ou .p12 lido com a senha, com o titular e o CNPJ (no CN, depois do
 * ":"), o vencimento e a chave privada. O certificado A3 (cartão ou token) fica fora.
 *
 * <p>Sigilo (F23.2 e RNF13): nem o caminho, nem a senha, nem a chave aparecem em mensagem, em {@link #toString()} ou no
 * log. Os erros da leitura viram mensagens fixas, sem o texto da exceção original.
 */
public final class CertificadoA1 {

    private final String alias;
    private final byte[] arquivo;
    private final char[] senha;
    private final X509Certificate x509;
    private final PrivateKey chavePrivada;
    private final String titular;
    private final String cnpj;

    private CertificadoA1(String alias, byte[] arquivo, char[] senha, X509Certificate x509, PrivateKey chavePrivada) {
        this.alias = alias;
        this.arquivo = arquivo;
        this.senha = senha;
        this.x509 = x509;
        this.chavePrivada = chavePrivada;
        String cn = nomeComum(x509);
        int doisPontos = cn.lastIndexOf(':');
        this.titular = doisPontos >= 0 ? cn.substring(0, doisPontos).strip() : cn;
        this.cnpj = doisPontos >= 0 ? Textos.apenasDigitos(cn.substring(doisPontos + 1)) : "";
    }

    /** Lê o arquivo do certificado; recusa com mensagens sem o caminho e sem a senha. */
    public static CertificadoA1 ler(Path caminho, String senha) {
        if (caminho == null) {
            throw new NegocioException("Escolha o arquivo do certificado A1 (.pfx ou .p12).");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(caminho);
        } catch (NoSuchFileException e) {
            throw new NegocioException("O arquivo do certificado não foi encontrado. Escolha o arquivo de novo em "
                    + "Configurações › NFC-e.");
        } catch (IOException | SecurityException e) {
            throw new NegocioException("Não foi possível ler o arquivo do certificado. Confira se ele está acessível "
                    + "neste computador.");
        }
        return ler(bytes, senha);
    }

    static CertificadoA1 ler(byte[] bytes, String senha) {
        char[] chars = (senha == null ? "" : senha).toCharArray();
        KeyStore loja;
        try {
            loja = KeyStore.getInstance("PKCS12");
            loja.load(new ByteArrayInputStream(bytes), chars);
        } catch (IOException e) {
            if (e.getCause() instanceof UnrecoverableKeyException || e.getCause() instanceof javax.crypto.BadPaddingException
                    || String.valueOf(e.getMessage()).toLowerCase().contains("password")) {
                throw new NegocioException("A senha do certificado não confere.");
            }
            throw new NegocioException("O arquivo não é um certificado A1 válido (.pfx ou .p12).");
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new NegocioException("O arquivo não é um certificado A1 válido (.pfx ou .p12).");
        }
        try {
            for (String alias : Collections.list(loja.aliases())) {
                if (loja.isKeyEntry(alias) && loja.getCertificate(alias) instanceof X509Certificate x509) {
                    PrivateKey chave = (PrivateKey) loja.getKey(alias, chars);
                    if (chave != null) {
                        return new CertificadoA1(alias, bytes.clone(), chars, x509, chave);
                    }
                }
            }
        } catch (UnrecoverableKeyException e) {
            throw new NegocioException("A senha do certificado não confere.");
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new NegocioException("O arquivo não é um certificado A1 válido (.pfx ou .p12).");
        }
        throw new NegocioException("O arquivo não tem a chave privada do certificado: use o arquivo .pfx ou .p12 do "
                + "certificado A1.");
    }

    private static String nomeComum(X509Certificate x509) {
        try {
            for (Rdn rdn : new LdapName(x509.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue()).strip();
                }
            }
        } catch (InvalidNameException e) {
            return "";
        }
        return "";
    }

    /** Nome do titular (o CN antes do ":"). */
    public String titular() {
        return titular;
    }

    /** CNPJ do CN (depois do ":"), só dígitos, ou vazio. */
    public String cnpj() {
        return cnpj;
    }

    public Instant validoAte() {
        return x509.getNotAfter().toInstant();
    }

    public boolean vencido(Clock clock) {
        return !clock.instant().isBefore(validoAte()) || clock.instant().isBefore(x509.getNotBefore().toInstant());
    }

    /** Dias até o vencimento (negativo depois de vencido). */
    public long diasParaVencer(Clock clock) {
        return ChronoUnit.DAYS.between(clock.instant(), validoAte());
    }

    /** Se a raiz do CNPJ (8 primeiros dígitos) bate com a da loja. */
    public boolean raizConfere(String cnpjLoja) {
        return cnpj.length() == 14 && cnpjLoja != null && cnpjLoja.length() == 14
                && cnpj.substring(0, 8).equals(cnpjLoja.substring(0, 8));
    }

    X509Certificate x509() {
        return x509;
    }

    PrivateKey chavePrivada() {
        return chavePrivada;
    }

    /** O mesmo certificado no formato da biblioteca Java_NFe (pelos bytes, sem o caminho). */
    Certificado paraBiblioteca() {
        try {
            Certificado c = CertificadoService.certificadoPfxBytes(arquivo, new String(senha));
            c.setNome(alias);
            return c;
        } catch (CertificadoException e) {
            throw new NegocioException("O arquivo não é um certificado A1 válido (.pfx ou .p12).");
        }
    }

    public Optional<String> problema(Clock clock, String cnpjLoja) {
        if (vencido(clock)) {
            return Optional.of("O certificado está vencido.");
        }
        if (!raizConfere(cnpjLoja)) {
            return Optional.of("O CNPJ do certificado não é o da loja.");
        }
        return Optional.empty();
    }

    /** Sem a senha, o caminho e a chave. */
    @Override
    public String toString() {
        return "CertificadoA1[titular=" + titular + ", validoAte=" + validoAte() + "]";
    }
}
