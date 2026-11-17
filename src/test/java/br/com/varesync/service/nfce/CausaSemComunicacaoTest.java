package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * F23.6, "Contingência à vista": a causa da falta de comunicação em linguagem simples, pelos tipos da cadeia de
 * exceções; as mensagens (que podem trazer o XML, o endereço ou o caminho do certificado) nunca entram.
 */
class CausaSemComunicacaoTest {

    private static RuntimeException embrulhada(Throwable causa) {
        return new RuntimeException("<NFe>xml inteiro</NFe> C:\\certificados\\loja.pfx", causa);
    }

    @Test
    void cadaCausaEmLinguagemSimples() {
        assertThat(CausaSemComunicacao.descrever(embrulhada(new SocketTimeoutException("Read timed out")), 10))
                .isEqualTo("tempo esgotado: a SEFAZ não respondeu em 10 segundos");
        assertThat(CausaSemComunicacao.descrever(embrulhada(new UnknownHostException("homologacao.sefaz")), 10))
                .startsWith("endereço da SEFAZ não encontrado");
        assertThat(CausaSemComunicacao.descrever(embrulhada(new ConnectException("Connection refused")), 10))
                .startsWith("conexão recusada");
        assertThat(CausaSemComunicacao.descrever(embrulhada(new SSLHandshakeException("bad_certificate")), 10))
                .startsWith("certificado recusado pela SEFAZ");
        assertThat(CausaSemComunicacao.descrever(embrulhada(new IllegalStateException("x")), 10))
                .isEqualTo("erro inesperado na comunicação (IllegalStateException)");
        assertThat(CausaSemComunicacao.paralisado("108")).isEqualTo("serviço da SEFAZ paralisado (cStat 108)");
    }

    @Test
    void nuncaLevaAMensagemDaExcecao() {
        String causa = CausaSemComunicacao.descrever(embrulhada(new IllegalStateException("<NFe>senha123</NFe>")), 5);
        assertThat(causa).doesNotContain("<NFe", "senha123", ".pfx");
    }
}
