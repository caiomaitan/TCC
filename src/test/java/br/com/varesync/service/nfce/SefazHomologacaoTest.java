package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.enuns.AmbienteEnum;
import br.com.swconsultoria.nfe.exception.NfeException;
import br.com.varesync.service.NegocioException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A SEFAZ de homologação (F23.4) sem ir à rede: produção recusada e o log das falhas sem as mensagens. */
class SefazHomologacaoTest {

    @TempDir
    Path pasta;

    @Test
    void notaDeProducaoERecusadaAntesDeQualquerEnvio() {
        SefazHomologacao sefaz = new SefazHomologacao(new AssinaturaNfce(pasta, ZoneId.of("America/Sao_Paulo")));
        String producao = "<NFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><infNFe><ide><tpAmb>1</tpAmb></ide>"
                + "</infNFe></NFe>";
        ContextoSefaz contexto = new ContextoSefaz("SP", "12345678000195", CertificadoTeste.carregado(), 1,
                ZoneId.of("America/Sao_Paulo"));
        assertThatThrownBy(() -> sefaz.autorizar(contexto, producao, "1")).isInstanceOf(NegocioException.class)
                .hasMessageContaining("só emite NFC-e em homologação");
        assertThatThrownBy(() -> SefazHomologacao.exigirHomologacao("<NFe/>")).isInstanceOf(NegocioException.class);
        assertThatCode(() -> SefazHomologacao.exigirHomologacao("<NFe><ide><tpAmb>2</tpAmb></ide></NFe>"))
                .doesNotThrowAnyException();
    }

    @Test
    void configuracaoDaBibliotecaSempreEmHomologacao() {
        AssinaturaNfce assinatura = new AssinaturaNfce(pasta, ZoneId.of("America/Sao_Paulo"));
        ConfiguracoesNfe config = assinatura.configuracao("SP", CertificadoTeste.carregado());
        assertThat(config.getAmbiente()).isEqualTo(AmbienteEnum.HOMOLOGACAO);
        config.setAmbiente(AmbienteEnum.PRODUCAO);
        assertThatThrownBy(() -> SefazHomologacao.exigirHomologacao(config)).isInstanceOf(NegocioException.class);
    }

    @Test
    void falhaVaiParaOLogSoComOsNomesDasClasses() {
        NfeException e = new NfeException("<NFe>xml inteiro com a senha</NFe>",
                new SocketTimeoutException("Read timed out"));
        assertThat(SefazHomologacao.causas(e)).isEqualTo("NfeException <- SocketTimeoutException")
                .doesNotContain("NFe>", "senha", "timed out");
    }
}
