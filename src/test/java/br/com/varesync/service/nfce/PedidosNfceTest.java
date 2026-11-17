package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O XML completo dos eventos e das inutilizações (F23.9): os pedidos assinados passam no XSD do envio e o
 * procEventoNFe e o procInutNFe montados com o retorno passam no XSD deles, com a assinatura conferindo com o
 * certificado de teste. Sem rede: o retorno é o da SEFAZ falsa.
 */
class PedidosNfceTest {

    private static final String CHAVE = "35261112345678000195650010000001231876543214";
    private static final String SUBSTITUTA = "35261112345678000195650010000001249876543217";
    private static final OffsetDateTime QUANDO = OffsetDateTime.of(2026, 11, 16, 10, 15, 0, 0, ZoneOffset.ofHours(-3));

    @TempDir
    Path pasta;

    private ValidacaoXsd validacao;
    private ConfiguracoesNfe config;
    private PublicKey chavePublica;

    @BeforeEach
    void preparar() {
        validacao = new ValidacaoXsd(pasta);
        config = new AssinaturaNfce(validacao.pasta(), ZoneId.of("America/Sao_Paulo"))
                .configuracao("SP", CertificadoTeste.carregado());
        chavePublica = XmlNfceTeste.certificadoDeTeste().getPublicKey();
    }

    @Test
    void cancelamentoAssinadoEProcEventoValidosNoXsd() {
        String lote = PedidosNfce.cancelamento(config, CertificadoTeste.CNPJ, CHAVE, "135260000000001",
                "Cliente desistiu da compra no caixa", QUANDO);
        assertThat(validacao.erros(lote, "envEventoCancNFe_v1.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(lote, chavePublica, "infEvento")).isTrue();
        assertThat(XmlNfceTeste.texto(lote, "tpEvento")).isEqualTo("110111");
        assertThat(XmlNfceTeste.texto(lote, "tpAmb")).isEqualTo("2");
        assertThat(XmlNfceTeste.texto(lote, "dhEvento")).isEqualTo("2026-11-16T10:15:00-03:00");

        String proc = PedidosNfce.procEvento(lote, retEvento("110111"));
        assertThat(validacao.erros(proc, "procEventoCancNFe_v1.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(proc, chavePublica, "infEvento")).isTrue();
        assertThat(XmlNfceTeste.texto(proc, "nProt")).isEqualTo("135260000000001");
        assertThat(XmlNfceTeste.todos(proc, "cStat")).containsExactly("135");
    }

    @Test
    void cancelamentoPorSubstituicaoAssinadoEProcEventoValidosNoXsd() {
        String lote = PedidosNfce.cancelamentoPorSubstituicao(config, CertificadoTeste.CNPJ, CHAVE,
                "135260000000001", SUBSTITUTA, "Cancelada por substituicao: SEFAZ sem resposta na emissao", QUANDO);
        assertThat(validacao.erros(lote, "envEventoCancSubst_v1.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(lote, chavePublica, "infEvento")).isTrue();
        assertThat(XmlNfceTeste.texto(lote, "chNFeRef")).isEqualTo(SUBSTITUTA);

        String proc = PedidosNfce.procEvento(lote, retEvento("110112"));
        assertThat(validacao.erros(proc, "procEventoCancSubst_v1.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(proc, chavePublica, "infEvento")).isTrue();
    }

    @Test
    void inutilizacaoAssinadaEProcInutValidosNoXsd() {
        var pedido = PedidosNfce.inutilizacao(config, CertificadoTeste.CNPJ, 1, 10, 12,
                "Numeros pulados pela troca do sistema de caixa", QUANDO);
        String assinado = PedidosNfce.inutilizacaoAssinada(config, pedido);
        assertThat(validacao.erros(assinado, "inutNFe_v4.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(assinado, chavePublica, "infInut")).isTrue();
        assertThat(XmlNfceTeste.texto(assinado, "ano")).isEqualTo("26");
        // Determinística: assinar de novo (como a biblioteca faz antes de enviar) dá o mesmo texto.
        assertThat(PedidosNfce.inutilizacaoAssinada(config, pedido)).isEqualTo(assinado);

        String retorno = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns2:retInutNFe xmlns:ns2="
                + "\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\"><ns2:infInut><ns2:tpAmb>2</ns2:tpAmb>"
                + "<ns2:verAplic>SP_NFCE</ns2:verAplic><ns2:cStat>102</ns2:cStat><ns2:xMotivo>Inutilizacao de numero "
                + "homologado</ns2:xMotivo><ns2:cUF>35</ns2:cUF><ns2:ano>26</ns2:ano><ns2:CNPJ>12345678000195"
                + "</ns2:CNPJ><ns2:mod>65</ns2:mod><ns2:serie>1</ns2:serie><ns2:nNFIni>10</ns2:nNFIni><ns2:nNFFin>12"
                + "</ns2:nNFFin><ns2:dhRecbto>2026-11-16T10:15:02-03:00</ns2:dhRecbto><ns2:nProt>135260000000009"
                + "</ns2:nProt></ns2:infInut></ns2:retInutNFe>";
        String proc = PedidosNfce.procInutilizacao(assinado, retorno);
        assertThat(validacao.erros(proc, "procInutNFe_v4.00.xsd")).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(proc, chavePublica, "infInut")).isTrue();
        assertThat(XmlNfceTeste.texto(proc, "nProt")).isEqualTo("135260000000009");
    }

    @Test
    void respostaQueNaoSeLeVaiComoEsta() {
        assertThat(PedidosNfce.elemento("<?xml version=\"1.0\"?><retEvento>sem fim", "retEvento"))
                .isEqualTo("<retEvento>sem fim");
    }

    private static String retEvento(String tipo) {
        return "<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.00\"><idLote>1</idLote>"
                + "<tpAmb>2</tpAmb><verAplic>SP_EVENTOS</verAplic><cOrgao>35</cOrgao><cStat>128</cStat>"
                + "<xMotivo>Lote de Evento Processado</xMotivo><retEvento versao=\"1.00\"><infEvento><tpAmb>2</tpAmb>"
                + "<verAplic>SP_EVENTOS</verAplic><cOrgao>35</cOrgao><cStat>135</cStat><xMotivo>Evento registrado "
                + "e vinculado a NF-e</xMotivo><chNFe>" + CHAVE + "</chNFe><tpEvento>" + tipo + "</tpEvento>"
                + "<nSeqEvento>1</nSeqEvento><dhRegEvento>2026-11-16T10:15:03-03:00</dhRegEvento>"
                + "<nProt>135260000000001</nProt></infEvento></retEvento></retEnvEvento>";
    }
}
