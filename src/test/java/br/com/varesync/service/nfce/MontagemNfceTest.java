package br.com.varesync.service.nfce;

import static br.com.varesync.service.nfce.NotasTeste.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.TipoEmissaoNfce;
import br.com.varesync.model.TributoItem;
import br.com.varesync.service.NegocioException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Montagem, assinatura, QR Code e validação pelo XSD da NFC-e (F23.3), sem banco, com o certificado de teste gerado
 * pelo keytool. Critérios de aceite da parte A: XSD, assinatura, totais, vUnCom × quantidade, descrição de
 * homologação e QR Code normal e de contingência.
 */
class MontagemNfceTest {

    @TempDir
    static Path pasta;
    private static GeracaoNfce geracao;
    private static CertificadoA1 certificado;
    private static PublicKey chavePublica;

    @BeforeAll
    static void preparar() {
        ZoneId fuso = ZoneId.of("America/Sao_Paulo");
        ValidacaoXsd validacao = new ValidacaoXsd(pasta);
        geracao = new GeracaoNfce(new MontagemNfce(fuso), new AssinaturaNfce(validacao.pasta(), fuso), validacao);
        certificado = CertificadoTeste.carregado();
        chavePublica = XmlNfceTeste.certificadoDeTeste().getPublicKey();
    }

    private static GeracaoNfce.Resultado gerar(NotaNfce nota) {
        return geracao.gerar(nota, certificado, "", "");
    }

    // ----- Venda com desconto, balança, embalagem com preço próprio e dois pagamentos -----

    @Test
    void notaValidaNoXsdEAssinaturaConfereComOCertificado() {
        GeracaoNfce.Resultado r = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null));
        assertThat(r.erros()).isEmpty();
        assertThat(XmlNfceTeste.assinaturaConfere(r.xml(), chavePublica)).isTrue();
        // mexer num valor depois de assinar quebra a assinatura
        String adulterado = r.xml().replace("<vNF>83.65</vNF>", "<vNF>83.66</vNF>");
        assertThat(adulterado).isNotEqualTo(r.xml());
        assertThat(XmlNfceTeste.assinaturaConfere(adulterado, chavePublica)).isFalse();
    }

    @Test
    void totaisBatemComAVendaEVUnComVezesQuantidadeBateComCadaItem() {
        NotaNfce nota = NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null);
        String xml = gerar(nota).xml();
        assertThat(XmlNfceTeste.texto(xml, "vProd")).isEqualTo("10.98"); // o 1º vProd é o do item
        assertThat(XmlNfceTeste.todos(xml, "vProd")).containsExactly("10.98", "13.77", "59.90", "84.65");
        assertThat(XmlNfceTeste.todos(xml, "vDesc")).containsExactly("1.00", "1.00");
        assertThat(XmlNfceTeste.texto(xml, "vNF")).isEqualTo("83.65");
        List<String> quantidades = XmlNfceTeste.todos(xml, "qCom");
        List<String> unitarios = XmlNfceTeste.todos(xml, "vUnCom");
        List<String> valores = XmlNfceTeste.todos(xml, "vProd").subList(0, 3);
        assertThat(unitarios).containsExactly("5.4900000000", "39.9130434783", "4.9916666667");
        for (int i = 0; i < 3; i++) {
            BigDecimal produto = new BigDecimal(unitarios.get(i)).multiply(new BigDecimal(quantidades.get(i)))
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(produto).as("item %d", i + 1).isEqualByComparingTo(valores.get(i));
        }
        assertThat(XmlNfceTeste.todos(xml, "vUnTrib")).isEqualTo(unitarios);
    }

    @Test
    void emHomologacaoO1ItemSaiComADescricaoExigida() {
        String xml = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null)).xml();
        assertThat(XmlNfceTeste.todos(xml, "xProd")).containsExactly(MontagemNfce.DESCRICAO_HOMOLOGACAO,
                "Queijo Mussarela kg", "Refrigerante Cola 350 ml");
        assertThat(XmlNfceTeste.texto(xml, "tpAmb")).isEqualTo("2");
    }

    @Test
    void identificacaoEmitenteEDestinatario() {
        String xml = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, "52998224725")).xml();
        assertThat(XmlNfceTeste.texto(xml, "mod")).isEqualTo("65");
        assertThat(XmlNfceTeste.texto(xml, "natOp")).isEqualTo("VENDA");
        assertThat(XmlNfceTeste.texto(xml, "dhEmi")).isEqualTo("2026-11-14T10:30:00-03:00");
        assertThat(XmlNfceTeste.texto(xml, "tpImp")).isEqualTo("4");
        assertThat(XmlNfceTeste.texto(xml, "indIntermed")).isEqualTo("0");
        assertThat(XmlNfceTeste.texto(xml, "verProc")).isEqualTo("2.0.0-SNAPSHOT");
        assertThat(XmlNfceTeste.texto(xml, "CRT")).isEqualTo("1");
        assertThat(XmlNfceTeste.texto(xml, "xFant")).isEqualTo("Mercado Bom Preço");
        assertThat(XmlNfceTeste.texto(xml, "CPF")).isEqualTo("52998224725");
        assertThat(XmlNfceTeste.todos(xml, "xNome")).as("o destinatário vai sem nome")
                .containsExactly("EMPRESA TESTE LTDA");
        assertThat(XmlNfceTeste.texto(xml, "modFrete")).isEqualTo("9");
    }

    @Test
    void semDocumentoNaoHaDestinatario() {
        String xml = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null)).xml();
        assertThat(XmlNfceTeste.todos(xml, "dest")).isEmpty();
    }

    @Test
    void ceanDoGtinValidoESemGtinNoDeUsoInterno() {
        String xml = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null)).xml();
        assertThat(XmlNfceTeste.todos(xml, "cEAN")).containsExactly("7891000315507", "SEM GTIN", "7891234567895");
        assertThat(XmlNfceTeste.todos(xml, "cEANTrib")).containsExactly("7891000315507", "SEM GTIN",
                "7891234567895");
        assertThat(MontagemNfce.gtin("7891234567890")).as("DV errado").isEqualTo("SEM GTIN");
        assertThat(MontagemNfce.gtin(null)).isEqualTo("SEM GTIN");
    }

    @Test
    void pagamentosDinheiroComTrocoECartao() {
        String xml = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null)).xml();
        assertThat(XmlNfceTeste.todos(xml, "tPag")).containsExactly("01", "04");
        assertThat(XmlNfceTeste.todos(xml, "vPag")).as("no dinheiro, o valor entregue").containsExactly("50.00",
                "40.00");
        assertThat(XmlNfceTeste.texto(xml, "vTroco")).isEqualTo("6.35");
        assertThat(XmlNfceTeste.todos(xml, "tpIntegra")).as("só no cartão").containsExactly("2");
    }

    @Test
    void tributosAproximadosEIcmsDoSimples() {
        String xml = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null)).xml();
        assertThat(XmlNfceTeste.todos(xml, "vTotTrib")).containsExactly("3.15", "3.15", "3.15", "9.45");
        assertThat(XmlNfceTeste.todos(xml, "CSOSN")).containsExactly("102", "102", "102");
        assertThat(XmlNfceTeste.todos(xml, "IBSCBS")).as("no Simples não vai o grupo").isEmpty();
    }

    // ----- QR Code versão 3 -----

    @Test
    void qrCodeNormalTerminaNaChaveVersao3ETpAmb2() {
        NotaNfce nota = NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null);
        GeracaoNfce.Resultado r = gerar(nota);
        String chave = nota.identificacao().chave();
        assertThat(r.qrCode()).isEqualTo("https://www.homologacao.nfce.fazenda.sp.gov.br/qrcode?p=" + chave + "|3|2");
        assertThat(XmlNfceTeste.texto(r.xml(), "qrCode")).isEqualTo(r.qrCode());
        assertThat(XmlNfceTeste.texto(r.xml(), "urlChave"))
                .isEqualTo("https://www.homologacao.nfce.fazenda.sp.gov.br/consulta");
    }

    @Test
    void enderecosDaConfiguracaoValemNoLugarDosDaBiblioteca() {
        NotaNfce nota = NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null);
        GeracaoNfce.Resultado r = geracao.gerar(nota, certificado, "https://qrcode.exemplo.gov.br/nfce",
                "https://consulta.exemplo.gov.br/nfce");
        assertThat(r.qrCode()).startsWith("https://qrcode.exemplo.gov.br/nfce?p=");
        assertThat(r.urlConsulta()).isEqualTo("https://consulta.exemplo.gov.br/nfce");
        assertThat(r.erros()).isEmpty();
    }

    @Test
    void contingenciaTemOitoParametrosEAssinaturaConfereComOCertificado() {
        NotaNfce nota = NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.CONTINGENCIA_OFFLINE,
                "52998224725");
        GeracaoNfce.Resultado r = gerar(nota);
        assertThat(r.erros()).isEmpty();
        String parametros = r.qrCode().substring(r.qrCode().indexOf("?p=") + 3);
        String[] p = parametros.split("\\|", -1);
        assertThat(p).hasSize(8);
        assertThat(p[0]).isEqualTo(nota.identificacao().chave());
        assertThat(p[0].charAt(34)).as("tpEmis 9 na chave").isEqualTo('9');
        assertThat(List.of(p[1], p[2], p[3], p[4], p[5], p[6])).containsExactly("3", "2", "14", "83.65", "2",
                "52998224725");
        assertThat(XmlNfceTeste.assinaturaQrCodeConfere(r.qrCode(), chavePublica)).isTrue();
        assertThat(XmlNfceTeste.texto(r.xml(), "tpEmis")).isEqualTo("9");
        assertThat(XmlNfceTeste.texto(r.xml(), "dhCont")).isEqualTo("2026-11-14T10:30:00-03:00");
        assertThat(XmlNfceTeste.texto(r.xml(), "xJust")).isEqualTo("SEFAZ indisponivel - sem resposta da autorizacao");
    }

    @Test
    void contingenciaSemDestinatarioDeixaOsDoisCamposVazios() {
        GeracaoNfce.Resultado r = gerar(NotasTeste.simplesComDescontoBalancaEmbalagem(
                TipoEmissaoNfce.CONTINGENCIA_OFFLINE, null));
        String[] p = r.qrCode().substring(r.qrCode().indexOf("?p=") + 3).split("\\|", -1);
        assertThat(p).hasSize(8);
        assertThat(p[5]).isEmpty();
        assertThat(p[6]).isEmpty();
        assertThat(XmlNfceTeste.assinaturaQrCodeConfere(r.qrCode(), chavePublica)).isTrue();
    }

    // ----- Regime normal: ICMS, PIS, COFINS e IBS/CBS -----

    @Test
    void regimeNormalComIbsCbsEResponsavelTecnico() {
        GeracaoNfce.Resultado r = gerar(NotasTeste.normal(NotasTeste.normalCst00(), v("0.50")));
        assertThat(r.erros()).isEmpty();
        String xml = r.xml();
        assertThat(XmlNfceTeste.texto(xml, "CRT")).isEqualTo("3");
        assertThat(XmlNfceTeste.todos(xml, "vICMS")).containsExactly("1.71", "1.71");
        assertThat(XmlNfceTeste.todos(xml, "vPIS")).containsExactly("0.13", "0.13");
        assertThat(XmlNfceTeste.todos(xml, "vCOFINS")).containsExactly("0.59", "0.59");
        assertThat(XmlNfceTeste.texto(xml, "cClassTrib")).isEqualTo("000001");
        assertThat(XmlNfceTeste.todos(xml, "vBC")).contains("9.50", "7.79", "7.07");
        assertThat(XmlNfceTeste.texto(xml, "pCBS")).isEqualTo("0.9000");
        assertThat(XmlNfceTeste.todos(xml, "vCBS")).containsExactly("0.06", "0.06");
        assertThat(XmlNfceTeste.todos(xml, "vIBSUF")).containsExactly("0.01", "0.01");
        assertThat(XmlNfceTeste.texto(xml, "vBCIBSCBS")).isEqualTo("7.07");
        assertThat(XmlNfceTeste.todos(xml, "gRed")).as("sem redução no 000").isEmpty();
        assertThat(XmlNfceTeste.texto(xml, "xContato")).isEqualTo("Fulano de Tal");
        assertThat(XmlNfceTeste.todos(xml, "tpIntegra")).as("PIX dinâmico (17) leva o grupo card")
                .containsExactly("2");
    }

    @Test
    void cst200LevaAReducaoEAAliquotaEfetiva() {
        TributoItem t = NotasTeste.normalCst00();
        TributoItem cst200 = new TributoItem(t.ncm(), t.cest(), t.origem(), t.cfop(), t.cstIcms(), t.csosn(),
                t.codigoBeneficio(), t.cstPis(), t.cstCofins(), "200", "200001", t.baseIcms(), t.aliquotaIcms(),
                t.reducaoBaseIcms(), t.valorIcms(), t.aliquotaFcp(), t.valorFcp(), null, null, null, null,
                t.basePis(), t.aliquotaPis(), t.valorPis(), t.baseCofins(), t.aliquotaCofins(), t.valorCofins(),
                t.baseIbsCbs(), t.aliquotaCbs(), t.aliquotaIbsUf(), t.aliquotaIbsMun(), v("60"), v("0.03"),
                v("0.00"), v("0.00"), null, null, null, null);
        GeracaoNfce.Resultado r = gerar(NotasTeste.normal(cst200, v("0.50")));
        assertThat(r.erros()).isEmpty();
        assertThat(XmlNfceTeste.todos(r.xml(), "pRedAliq")).containsExactly("60.0000", "60.0000", "60.0000");
        assertThat(XmlNfceTeste.todos(r.xml(), "pAliqEfet")).containsExactly("0.0400", "0.0000", "0.3600");
    }

    @Test
    void cst410SoLevaOCstEOCClassTrib() {
        TributoItem t = NotasTeste.normalCst00();
        TributoItem cst410 = new TributoItem(t.ncm(), t.cest(), t.origem(), t.cfop(), t.cstIcms(), t.csosn(),
                t.codigoBeneficio(), t.cstPis(), t.cstCofins(), "410", "410001", t.baseIcms(), t.aliquotaIcms(),
                t.reducaoBaseIcms(), t.valorIcms(), t.aliquotaFcp(), t.valorFcp(), null, null, null, null,
                t.basePis(), t.aliquotaPis(), t.valorPis(), t.baseCofins(), t.aliquotaCofins(), t.valorCofins(),
                null, null, null, null, null, null, null, null, null, null, null, null);
        GeracaoNfce.Resultado r = gerar(NotasTeste.normal(cst410, v("0.50")));
        assertThat(r.erros()).isEmpty();
        assertThat(XmlNfceTeste.todos(r.xml(), "gIBSCBS")).isEmpty();
        assertThat(XmlNfceTeste.texto(r.xml(), "vBCIBSCBS")).isEqualTo("0.00");
    }

    // ----- O que a NFC-e desta versão não aceita e o que o XSD acusa -----

    @Test
    void itemSemOsTributosGravadosERecusado() {
        NotaNfce nota = NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null);
        NotaNfce.Item semFiscal = new NotaNfce.Item(4, 99, "Produto sem perfil", null, "UN", v("1.000"), v("1.00"),
                BigDecimal.ZERO, null);
        NotaNfce outra = new NotaNfce(nota.identificacao(), nota.emitente(), null,
                List.of(nota.itens().get(0), semFiscal), nota.pagamentos(), nota.troco(), v("10.98"), null, "2");
        assertThatThrownBy(() -> new MontagemNfce(ZoneId.of("America/Sao_Paulo")).montar(outra))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Produto sem perfil")
                .hasMessageContaining("dados fiscais");
    }

    @Test
    void cstForaDaRegra14ERecusado() {
        TributoItem t = NotasTeste.normalCst00();
        TributoItem cst10 = new TributoItem(t.ncm(), null, 0, "5102", "10", null, null, "01", "01", "000", "000001",
                t.baseIcms(), t.aliquotaIcms(), null, t.valorIcms(), BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                null, null, t.basePis(), t.aliquotaPis(), t.valorPis(), t.baseCofins(), t.aliquotaCofins(),
                t.valorCofins(), t.baseIbsCbs(), t.aliquotaCbs(), t.aliquotaIbsUf(), t.aliquotaIbsMun(), null,
                t.valorCbs(), t.valorIbsUf(), t.valorIbsMun(), null, null, null, null);
        assertThatThrownBy(() -> new MontagemNfce(ZoneId.of("America/Sao_Paulo"))
                .montar(NotasTeste.normal(cst10, v("0.50"))))
                .isInstanceOf(NegocioException.class).hasMessageContaining("CST 10");
    }

    @Test
    void regimeNormalSemOCstDoIbsECbsERecusado() {
        TributoItem t = NotasTeste.normalCst00();
        TributoItem semIbs = new TributoItem(t.ncm(), null, 0, "5102", "00", null, null, "01", "01", null, null,
                t.baseIcms(), t.aliquotaIcms(), null, t.valorIcms(), BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                null, null, t.basePis(), t.aliquotaPis(), t.valorPis(), t.baseCofins(), t.aliquotaCofins(),
                t.valorCofins(), null, null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> new MontagemNfce(ZoneId.of("America/Sao_Paulo"))
                .montar(NotasTeste.normal(semIbs, v("0.50"))))
                .isInstanceOf(NegocioException.class).hasMessageContaining("CST do IBS e da CBS");
    }

    @Test
    void xsdAcusaOQueEstaForaDoLeiaute() {
        NotaNfce nota = NotasTeste.simplesComDescontoBalancaEmbalagem(TipoEmissaoNfce.NORMAL, null);
        NotaNfce.Item ok = nota.itens().get(0);
        TributoItem ncmCurto = NotasTeste.simples102("123");
        NotaNfce ruim = new NotaNfce(nota.identificacao(), nota.emitente(), null,
                List.of(new NotaNfce.Item(1, ok.codigo(), ok.descricao(), ok.gtin(), ok.unidade(), ok.quantidade(),
                        ok.valorBruto(), ok.desconto(), ncmCurto)),
                List.of(new NotaNfce.Pagamento("01", "Dinheiro", v("9.98"))), BigDecimal.ZERO, v("9.98"),
                Configuracoes.RespTecnico.VAZIO, "2");
        GeracaoNfce.Resultado r = gerar(ruim);
        assertThat(r.valido()).isFalse();
        assertThat(r.erros()).anySatisfy(e -> assertThat(e).contains("123"));
        assertThat(r.motivo()).startsWith("O XML da NFC-e não passou na validação pelo XSD");
    }

    @Test
    void textoForaDaFaixaDoLeiauteViraEspaco() {
        assertThat(TextoNfe.limpar("  Café — 500 g <promo>  ", 120)).isEqualTo("Café 500 g promo");
        assertThat(TextoNfe.limpar("abc", 2)).isEqualTo("ab");
        assertThat(MontagemNfce.valorUnitario(v("59.90"), v("12"))).isEqualByComparingTo("4.9916666667");
        assertThat(NotasTeste.emitente(RegimeTributario.NORMAL).crt()).isEqualTo("3");
    }
}
