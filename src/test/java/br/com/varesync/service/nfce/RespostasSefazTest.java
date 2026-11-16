package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.swconsultoria.nfe.schemas.TRetConsSitNFe;
import br.com.swconsultoria.nfe.schemas.TRetConsStatServ;
import br.com.swconsultoria.nfe.schemas.TRetEnviNFe;
import br.com.swconsultoria.nfe.schemas.TRetInutNFe;
import br.com.swconsultoria.nfe.schemas_eventos.TRetEnvEventoCancelamento;
import br.com.swconsultoria.nfe.schemas_eventos.TRetEnvEventoCancelamentoSubstituicao;
import br.com.swconsultoria.nfe.util.XmlNfeUtil;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Tradução das respostas da biblioteca (F23.4), com respostas de exemplo no leiaute 4.00, sem rede. */
class RespostasSefazTest {

    private static final String NS = "xmlns=\"http://www.portalfiscal.inf.br/nfe\"";
    private static final String CHAVE = "35261112345678000195650010000001231876543214";

    private static String protNFe(String cstat, String motivo, String protocolo) {
        return "<protNFe versao=\"4.00\"><infProt><tpAmb>2</tpAmb><verAplic>SP_NFCE_PL_010</verAplic><chNFe>" + CHAVE
                + "</chNFe><dhRecbto>2026-11-16T10:30:05-03:00</dhRecbto>" + (protocolo == null ? ""
                : "<nProt>" + protocolo + "</nProt>") + "<digVal>q2FbLXxRyFg5tLz1V4z9t3sQh9s=</digVal><cStat>"
                + cstat + "</cStat><xMotivo>" + motivo + "</xMotivo></infProt></protNFe>";
    }

    private static TRetEnviNFe retorno(String cstatLote, String prot) {
        return XmlNfeUtil.xmlToObject("<retEnviNFe " + NS + " versao=\"4.00\"><tpAmb>2</tpAmb><verAplic>SP</verAplic>"
                + "<cStat>" + cstatLote + "</cStat><xMotivo>Lote processado</xMotivo><cUF>35</cUF>"
                + "<dhRecbto>2026-11-16T10:30:05-03:00</dhRecbto>" + (prot == null ? "" : prot) + "</retEnviNFe>",
                TRetEnviNFe.class);
    }

    @Test
    void autorizadaComProtocoloDataEProtNFe() {
        RespostaSefaz r = RespostasSefaz.autorizacao(retorno("104", protNFe("100", "Autorizado o uso da NF-e",
                "135260000123456")));
        assertThat(r.autorizada()).isTrue();
        assertThat(r.protocolo()).isEqualTo("135260000123456");
        assertThat(r.recebidoEm()).isEqualTo(OffsetDateTime.parse("2026-11-16T10:30:05-03:00"));
        assertThat(r.xml()).startsWith("<protNFe").contains("<nProt>135260000123456</nProt>")
                .doesNotContain("<?xml");
        String proc = RespostasSefaz.nfeProc("<?xml version=\"1.0\"?><NFe " + NS + "><infNFe/></NFe>", r.xml());
        assertThat(proc).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><nfeProc " + NS + " versao=\"4.00\">"
                + "<NFe ").endsWith("</protNFe></nfeProc>");
    }

    @Test
    void rejeicaoDaNotaEDoLoteEDenegacao() {
        RespostaSefaz nota = RespostasSefaz.autorizacao(retorno("104", protNFe("539",
                "Rejeicao: Duplicidade de NF-e, com diferenca na Chave de Acesso", null)));
        assertThat(nota.autorizada()).isFalse();
        assertThat(nota.cstat()).isEqualTo("539");
        assertThat(nota.protocolo()).isNull();
        RespostaSefaz lote = RespostasSefaz.autorizacao(retorno("225", null));
        assertThat(lote.cstat()).as("sem o protNFe, vale o do lote").isEqualTo("225");
        RespostaSefaz denegada = RespostasSefaz.autorizacao(retorno("104", protNFe("302",
                "Uso Denegado: Irregularidade fiscal do destinatario", "135260000999999")));
        assertThat(denegada.denegada()).isTrue();
    }

    @Test
    void servicoParalisadoContaComoSemComunicacao() {
        assertThatThrownBy(() -> RespostasSefaz.autorizacao(retorno("108", null)))
                .isInstanceOf(SemComunicacaoException.class);
        assertThatThrownBy(() -> RespostasSefaz.status(XmlNfeUtil.xmlToObject("<retConsStatServ " + NS
                + " versao=\"4.00\"><tpAmb>2</tpAmb><verAplic>SP</verAplic><cStat>109</cStat><xMotivo>Servico "
                + "Paralisado sem Previsao</xMotivo><cUF>35</cUF><dhRecbto>2026-11-16T10:30:05-03:00</dhRecbto>"
                + "</retConsStatServ>", TRetConsStatServ.class))).isInstanceOf(SemComunicacaoException.class);
    }

    @Test
    void statusEmOperacao() {
        RespostaSefaz r = RespostasSefaz.status(XmlNfeUtil.xmlToObject("<retConsStatServ " + NS + " versao=\"4.00\">"
                + "<tpAmb>2</tpAmb><verAplic>SP</verAplic><cStat>107</cStat><xMotivo>Servico em Operacao</xMotivo>"
                + "<cUF>35</cUF><dhRecbto>2026-11-16T10:30:05-03:00</dhRecbto><tMed>1</tMed></retConsStatServ>",
                TRetConsStatServ.class));
        assertThat(r.emOperacao()).isTrue();
        assertThat(r.descricao()).isEqualTo("107 - Servico em Operacao");
    }

    @Test
    void consultaAutorizadaENaoConsta() {
        RespostaSefaz autorizada = RespostasSefaz.consulta(XmlNfeUtil.xmlToObject("<retConsSitNFe " + NS
                + " versao=\"4.00\"><tpAmb>2</tpAmb><verAplic>SP</verAplic><cStat>100</cStat><xMotivo>Autorizado o "
                + "uso da NF-e</xMotivo><cUF>35</cUF><dhRecbto>2026-11-16T10:40:00-03:00</dhRecbto><chNFe>" + CHAVE
                + "</chNFe>" + protNFe("100", "Autorizado o uso da NF-e", "135260000123456") + "</retConsSitNFe>",
                TRetConsSitNFe.class));
        assertThat(autorizada.autorizada()).isTrue();
        assertThat(autorizada.protocolo()).isEqualTo("135260000123456");
        RespostaSefaz naoConsta = RespostasSefaz.consulta(XmlNfeUtil.xmlToObject("<retConsSitNFe " + NS
                + " versao=\"4.00\"><tpAmb>2</tpAmb><verAplic>SP</verAplic><cStat>217</cStat><xMotivo>Rejeicao: NF-e "
                + "nao consta na base de dados da SEFAZ</xMotivo><cUF>35</cUF><dhRecbto>2026-11-16T10:40:00-03:00"
                + "</dhRecbto><chNFe>" + CHAVE + "</chNFe></retConsSitNFe>", TRetConsSitNFe.class));
        assertThat(naoConsta.naoConsta()).isTrue();
        assertThat(naoConsta.protocolo()).isNull();
    }

    private static String retEnvEvento(String tpEvento, String cstat) {
        return "<retEnvEvento " + NS + " versao=\"1.00\"><idLote>1</idLote><tpAmb>2</tpAmb><verAplic>SP</verAplic>"
                + "<cOrgao>35</cOrgao><cStat>128</cStat><xMotivo>Lote de Evento Processado</xMotivo>"
                + "<retEvento versao=\"1.00\"><infEvento><tpAmb>2</tpAmb><verAplic>SP</verAplic><cOrgao>35</cOrgao>"
                + "<cStat>" + cstat + "</cStat><xMotivo>Evento registrado e vinculado a NF-e</xMotivo><chNFe>" + CHAVE
                + "</chNFe><tpEvento>" + tpEvento + "</tpEvento><xEvento>Cancelamento</xEvento><nSeqEvento>1"
                + "</nSeqEvento><dhRegEvento>2026-11-16T10:50:00-03:00</dhRegEvento><nProt>135260000777777</nProt>"
                + "</infEvento></retEvento></retEnvEvento>";
    }

    @Test
    void eventosPeloCstatDeDentro() {
        RespostaSefaz substituicao = RespostasSefaz.cancelamentoSubstituicao(XmlNfeUtil.xmlToObject(
                retEnvEvento("110112", "135"), TRetEnvEventoCancelamentoSubstituicao.class));
        assertThat(substituicao.eventoRegistrado()).isTrue();
        assertThat(substituicao.cstat()).as("o do evento, não o 128 do lote").isEqualTo("135");
        assertThat(substituicao.protocolo()).isEqualTo("135260000777777");
        RespostaSefaz duplicidade = RespostasSefaz.cancelamento(XmlNfeUtil.xmlToObject(retEnvEvento("110111", "573"),
                TRetEnvEventoCancelamento.class));
        assertThat(duplicidade.eventoRegistrado()).as("573: o primeiro pedido chegou").isTrue();
    }

    @Test
    void inutilizacaoHomologada() {
        RespostaSefaz r = RespostasSefaz.inutilizacao(XmlNfeUtil.xmlToObject("<retInutNFe " + NS + " versao=\"4.00\">"
                + "<infInut><tpAmb>2</tpAmb><verAplic>SP</verAplic><cStat>102</cStat><xMotivo>Inutilizacao de numero "
                + "homologado</xMotivo><cUF>35</cUF><ano>26</ano><CNPJ>12345678000195</CNPJ><mod>65</mod><serie>1"
                + "</serie><nNFIni>5</nNFIni><nNFFin>5</nNFFin><dhRecbto>2026-11-16T11:00:00-03:00</dhRecbto><nProt>"
                + "135260000888888</nProt></infInut></retInutNFe>", TRetInutNFe.class));
        assertThat(r.inutilizada()).isTrue();
        assertThat(r.protocolo()).isEqualTo("135260000888888");
    }

    @Test
    void dataForaDoFormatoViraNula() {
        assertThat(RespostasSefaz.data("16/11/2026")).isNull();
        assertThat(RespostasSefaz.data(null)).isNull();
    }
}
