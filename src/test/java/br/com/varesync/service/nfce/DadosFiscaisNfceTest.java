package br.com.varesync.service.nfce;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.CadastroFiscalProduto;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.RegimeTributario;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** F23.6: o que falta no cadastro fiscal do produto para ele ir na NFC-e (conferido no PDV). */
class DadosFiscaisNfceTest {

    private static PerfilTributario simples(String csosn) {
        return new PerfilTributario(1, "Simples", "5102", null, csosn, null, null, "49", null, "49", null, null, null,
                null, true, 0, null, null);
    }

    private static PerfilTributario normal(String cst, String cstIbsCbs, String cclass, BigDecimal reducao,
                                           boolean ativo) {
        return new PerfilTributario(2, "Normal", "5102", cst, null, new BigDecimal("18"), null, "01",
                new BigDecimal("1.65"), "01", new BigDecimal("7.6"), null, cstIbsCbs, cclass, ativo, 0, null, reducao);
    }

    @Test
    void completoNoSimplesPassa() {
        CadastroFiscalProduto c = new CadastroFiscalProduto("Arroz", "10063021", null, 0, simples("102"));
        assertThat(DadosFiscaisNfce.faltas(c, RegimeTributario.SIMPLES)).isEmpty();
        assertThat(DadosFiscaisNfce.recusa(c, RegimeTributario.SIMPLES)).isNull();
    }

    @Test
    void semNcmOrigemNemPerfilDizOQueFalta() {
        CadastroFiscalProduto c = new CadastroFiscalProduto("Bala", null, null, null, null);
        assertThat(DadosFiscaisNfce.recusa(c, RegimeTributario.SIMPLES)).isEqualTo("O produto \"Bala\" não pode ser "
                + "vendido com a NFC-e ligada: falta o NCM, a origem da mercadoria e o perfil tributário. Complete os "
                + "dados fiscais no cadastro do produto.");
    }

    @Test
    void perfilInativoESituacaoForaDaRegra14SaoRecusados() {
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("A", "10063021", null, 0,
                normal("00", "000", "000001", null, false)), RegimeTributario.NORMAL))
                .singleElement().asString().contains("está inativo");
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("A", "10063021", null, 0, simples("201")),
                RegimeTributario.SIMPLES)).singleElement().asString().contains("CSOSN 201 não é aceito");
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("A", "10063021", null, 0,
                normal("10", "000", "000001", null, true)), RegimeTributario.NORMAL))
                .singleElement().asString().contains("CST 10 não é aceito");
    }

    @Test
    void comStPedeOCest() {
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("Cerveja", "22030000", null, 0, simples("500")),
                RegimeTributario.SIMPLES)).containsExactly("o CEST (o produto tem substituição tributária)");
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("Cerveja", "22030000", "0302100", 0,
                simples("500")), RegimeTributario.SIMPLES)).isEmpty();
    }

    @Test
    void noNormalExigeOCstDoIbsECbsAceitoEOCclassTrib() {
        CadastroFiscalProduto semCst = new CadastroFiscalProduto("A", "10063021", null, 0,
                normal("00", null, null, null, true));
        assertThat(DadosFiscaisNfce.faltas(semCst, RegimeTributario.NORMAL))
                .containsExactly("o CST do IBS e da CBS no perfil", "o cClassTrib no perfil");
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("A", "10063021", null, 0,
                normal("00", "550", "550001", null, true)), RegimeTributario.NORMAL))
                .singleElement().asString().contains("o 550 não é aceito");
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("A", "10063021", null, 0,
                normal("00", "200", "200001", null, true)), RegimeTributario.NORMAL))
                .containsExactly("a redução de alíquota do IBS e da CBS no perfil (CST 200)");
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("A", "10063021", null, 0,
                normal("00", "200", "200001", new BigDecimal("60"), true)), RegimeTributario.NORMAL)).isEmpty();
        // No Simples, o grupo do IBS e da CBS não é informado (NT 2026.005).
        assertThat(DadosFiscaisNfce.faltas(new CadastroFiscalProduto("A", "10063021", null, 0, simples("102")),
                RegimeTributario.SIMPLES)).isEmpty();
    }
}
