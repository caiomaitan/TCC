package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.ItemTributavel;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.TributoItem;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cálculo dos tributos do item (F22.2), com os vetores do aceite da F22. */
class CalculoTributosTest {

    private static final Configuracoes.Tributos CONFIG = Configuracoes.Tributos.PADRAO;
    private static final ItemTributavel.Ibpt IBPT = new ItemTributavel.Ibpt(v("13.45"), v("15.45"), v("18.00"),
            v("0"), "26.2.A");

    private static BigDecimal v(String valor) {
        return new BigDecimal(valor);
    }

    private static PerfilTributario simples(String csosn) {
        return new PerfilTributario(1, "Simples", "5102", null, csosn, v("0.00"), null, "49", v("0.00"), "49",
                v("0.00"), null, null, null, true, 0);
    }

    private static PerfilTributario normal(String cst, String icms, String reducao, String fcp, String cstIbsCbs,
                                           String reducaoIbsCbs) {
        return new PerfilTributario(2, "Normal", "5102", cst, null, v(icms), reducao == null ? null : v(reducao), "01",
                v("1.65"), "01", v("7.60"), null, cstIbsCbs, cstIbsCbs == null ? null : cstIbsCbs + "001", true, 0,
                v(fcp), reducaoIbsCbs == null ? null : v(reducaoIbsCbs));
    }

    private static ItemTributavel item(String quantidade, String liquido, int origem, PerfilTributario perfil,
                                       ItemTributavel.StRetido st, ItemTributavel.Ibpt ibpt) {
        return new ItemTributavel(v(quantidade), v(liquido), "22021000", null, origem, perfil, st, ibpt);
    }

    @Test
    void simplesCsosn102SemIcmsPisECofinsComOsTributosAproximados() {
        TributoItem t = CalculoTributos.calcular(item("1", "10.00", 0, simples("102"), null, IBPT),
                RegimeTributario.SIMPLES, CONFIG);

        assertThat(t.csosn()).isEqualTo("102");
        assertThat(t.cstIcms()).isNull();
        assertThat(t.baseIcms()).isEqualByComparingTo("0");
        assertThat(t.valorIcms()).isEqualByComparingTo("0");
        assertThat(t.valorFcp()).isEqualByComparingTo("0");
        assertThat(t.valorPis()).isEqualByComparingTo("0");
        assertThat(t.valorCofins()).isEqualByComparingTo("0");
        assertThat(t.baseIbsCbs()).as("no Simples não calcula o IBS e a CBS").isNull();
        assertThat(t.valorCbs()).isNull();
        assertThat(t.tribFederal()).isEqualByComparingTo("1.35");
        assertThat(t.tribEstadual()).isEqualByComparingTo("1.80");
        assertThat(t.tribMunicipal()).isEqualByComparingTo("0.00");
        assertThat(t.tributosAproximados()).isEqualByComparingTo("3.15");
        assertThat(t.ibptVersao()).isEqualTo("26.2.A");
    }

    @Test
    void origemEstrangeiraUsaOFederalDeImportados() {
        for (int origem : List.of(1, 2, 6, 7)) {
            TributoItem t = CalculoTributos.calcular(item("1", "10.00", origem, simples("102"), null, IBPT),
                    RegimeTributario.SIMPLES, CONFIG);
            assertThat(t.tribFederal()).as("origem " + origem).isEqualByComparingTo("1.55");
        }
        for (int origem : List.of(0, 3, 4, 5, 8)) {
            TributoItem t = CalculoTributos.calcular(item("1", "10.00", origem, simples("102"), null, IBPT),
                    RegimeTributario.SIMPLES, CONFIG);
            assertThat(t.tribFederal()).as("origem " + origem).isEqualByComparingTo("1.35");
        }
    }

    @Test
    void semLinhaIbptOsTributosAproximadosFicamNulos() {
        TributoItem t = CalculoTributos.calcular(item("1", "10.00", 0, simples("102"), null, null),
                RegimeTributario.SIMPLES, CONFIG);
        assertThat(t.tribFederal()).isNull();
        assertThat(t.tribEstadual()).isNull();
        assertThat(t.tribMunicipal()).isNull();
        assertThat(t.ibptVersao()).isNull();
        assertThat(t.tributosAproximados()).isNull();
    }

    @Test
    void normalCst00ComDescontoRateadoIcmsPisCofinsIbsECbs() {
        // 2 × R$ 5,00 com R$ 0,50 de desconto rateado = R$ 9,50 líquidos.
        TributoItem t = CalculoTributos.calcular(item("2", "9.50", 0, normal("00", "18", null, "0", "000", null), null,
                null), RegimeTributario.NORMAL, CONFIG);

        assertThat(t.baseIcms()).isEqualByComparingTo("9.50");
        assertThat(t.valorIcms()).isEqualByComparingTo("1.71");
        assertThat(t.basePis()).isEqualByComparingTo("7.79");
        assertThat(t.baseCofins()).isEqualByComparingTo("7.79");
        assertThat(t.valorPis()).isEqualByComparingTo("0.13");
        assertThat(t.valorCofins()).isEqualByComparingTo("0.59");
        assertThat(t.baseIbsCbs()).isEqualByComparingTo("7.07");
        assertThat(t.valorCbs()).isEqualByComparingTo("0.06");
        assertThat(t.valorIbsUf()).isEqualByComparingTo("0.01");
        assertThat(t.valorIbsMun()).isEqualByComparingTo("0.00");
        assertThat(t.aliquotaCbs()).isEqualByComparingTo("0.9");
        assertThat(t.aliquotaIbsUf()).isEqualByComparingTo("0.1");
        assertThat(t.aliquotaIbsMun()).isEqualByComparingTo("0");
        assertThat(t.reducaoAliquotaIbsCbs()).isNull();
    }

    @Test
    void semExcluirOIcmsABaseDoPisECofinsEOValorLiquido() {
        Configuracoes.Tributos comIcms = new Configuracoes.Tributos(v("0.9"), v("0.1"), v("0"), false);
        TributoItem t = CalculoTributos.calcular(item("2", "9.50", 0, normal("00", "18", null, "0", "000", null), null,
                null), RegimeTributario.NORMAL, comIcms);
        assertThat(t.basePis()).isEqualByComparingTo("9.50");
        assertThat(t.valorPis()).isEqualByComparingTo("0.16"); // 9,50 × 1,65% = 0,15675
        assertThat(t.valorCofins()).isEqualByComparingTo("0.72"); // 9,50 × 7,6% = 0,722
    }

    @Test
    void cst20ReduzABaseEmDuasCasas() {
        TributoItem t = CalculoTributos.calcular(item("1", "100.00", 0, normal("20", "18", "33.33", "0", null, null),
                null, null), RegimeTributario.NORMAL, CONFIG);
        assertThat(t.baseIcms()).isEqualByComparingTo("66.67");
        assertThat(t.valorIcms()).isEqualByComparingTo("12.00");
        assertThat(t.reducaoBaseIcms()).isEqualByComparingTo("33.33");
        assertThat(t.baseIbsCbs()).as("sem o CST do IBS e da CBS no perfil").isNull();
    }

    @Test
    void arredondamentoPorItemTresItensDe25CentavosDao15Centavos() {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            TributoItem t = CalculoTributos.calcular(item("1", "0.25", 0, normal("00", "18", null, "0", null, null),
                    null, null), RegimeTributario.NORMAL, CONFIG);
            assertThat(t.valorIcms()).isEqualByComparingTo("0.05");
            total = total.add(t.valorIcms());
        }
        assertThat(total).isEqualByComparingTo("0.15"); // e não round(0,75 × 18%) = 0,14
    }

    @Test
    void fcpSoNosCst00E20ESaiDaBaseDoIbsECbs() {
        TributoItem t = CalculoTributos.calcular(item("1", "100.00", 0, normal("00", "18", null, "2", "000", null),
                null, null), RegimeTributario.NORMAL, CONFIG);
        assertThat(t.aliquotaFcp()).isEqualByComparingTo("2");
        assertThat(t.valorFcp()).isEqualByComparingTo("2.00");
        // 100 − 18 (ICMS) − 2 (FCP) − 1,35 (PIS sobre 82) − 6,23 (COFINS sobre 82) = 72,42
        assertThat(t.valorPis()).isEqualByComparingTo("1.35");
        assertThat(t.valorCofins()).isEqualByComparingTo("6.23");
        assertThat(t.baseIbsCbs()).isEqualByComparingTo("72.42");

        TributoItem isento = CalculoTributos.calcular(item("1", "100.00", 0, normal("40", "0", null, "2", null, null),
                null, null), RegimeTributario.NORMAL, CONFIG);
        assertThat(isento.valorFcp()).isEqualByComparingTo("0");
        assertThat(isento.aliquotaFcp()).isEqualByComparingTo("0");
        assertThat(isento.baseIcms()).isEqualByComparingTo("0");
        assertThat(isento.valorIcms()).isEqualByComparingTo("0");
    }

    @Test
    void reducaoDeAliquotaDoIbsECbs() {
        // 60% de redução: CBS efetiva 0,36%; base 100 − 1,65 − 7,60 (PIS e COFINS sem ICMS) = 90,75.
        TributoItem t = CalculoTributos.calcular(item("1", "100.00", 0, normal("40", "0", null, "0", "200", "60"),
                null, null), RegimeTributario.NORMAL, CONFIG);
        assertThat(t.baseIbsCbs()).isEqualByComparingTo("90.75");
        assertThat(t.reducaoAliquotaIbsCbs()).isEqualByComparingTo("60");
        assertThat(t.valorCbs()).isEqualByComparingTo("0.33"); // 90,75 × 0,36% = 0,3267
        assertThat(t.valorIbsUf()).isEqualByComparingTo("0.04"); // 90,75 × 0,04% = 0,0363
        assertThat(CalculoTributos.efetiva(v("0.9"), v("60"))).isEqualByComparingTo("0.36");
        assertThat(CalculoTributos.efetiva(v("0.9"), null)).isEqualByComparingTo("0.9");
    }

    @Test
    void cst60ECsosn500ComOStRetidoDoProdutoVezesAQuantidade() {
        ItemTributavel.StRetido st = new ItemTributavel.StRetido(v("5.0000"), v("18.00"), v("0.5000"), v("0.3000"));
        TributoItem cst60 = CalculoTributos.calcular(item("2", "12.00", 0, normal("60", "0", null, "0", null, null),
                st, null), RegimeTributario.NORMAL, CONFIG);
        assertThat(cst60.baseStRetido()).isEqualByComparingTo("10.00");
        assertThat(cst60.icmsStRetido()).isEqualByComparingTo("0.60");
        assertThat(cst60.icmsSubstituto()).isEqualByComparingTo("1.00");
        assertThat(cst60.aliquotaSt()).isEqualByComparingTo("18");
        assertThat(cst60.valorIcms()).as("sem ICMS próprio").isEqualByComparingTo("0");

        TributoItem csosn500 = CalculoTributos.calcular(item("2", "12.00", 0, simples("500"), st, null),
                RegimeTributario.SIMPLES, CONFIG);
        assertThat(csosn500.baseStRetido()).isEqualByComparingTo("10.00");
        assertThat(csosn500.icmsStRetido()).isEqualByComparingTo("0.60");

        TributoItem semDado = CalculoTributos.calcular(item("2", "12.00", 0, simples("500"),
                ItemTributavel.StRetido.NENHUM, null), RegimeTributario.SIMPLES, CONFIG);
        assertThat(semDado.baseStRetido()).isNull();
        assertThat(semDado.icmsStRetido()).isNull();

        TributoItem tributado = CalculoTributos.calcular(item("2", "12.00", 0, simples("102"), st, null),
                RegimeTributario.SIMPLES, CONFIG);
        assertThat(tributado.baseStRetido()).as("o ST retido só vale no CST 60 e no CSOSN 500").isNull();
    }

    @Test
    void outrosCstFicamSemValores() {
        for (String cst : List.of("10", "30", "41", "51", "70", "90")) {
            TributoItem t = CalculoTributos.calcular(item("1", "10.00", 0, normal(cst, "18", null, "2", null, null),
                    null, null), RegimeTributario.NORMAL, CONFIG);
            assertThat(t.baseIcms()).as(cst).isEqualByComparingTo("0");
            assertThat(t.valorIcms()).as(cst).isEqualByComparingTo("0");
            assertThat(t.valorFcp()).as(cst).isEqualByComparingTo("0");
            assertThat(t.cstIcms()).isEqualTo(cst);
        }
    }

    @Test
    void pisECofinsForaDosCst01E02ComBaseEValorZero() {
        PerfilTributario monofasico = new PerfilTributario(3, "Monofásico", "5405", "60", null, v("0"), null, "04",
                v("0"), "04", v("0"), null, null, null, true, 0);
        TributoItem t = CalculoTributos.calcular(item("1", "10.00", 0, monofasico, null, null),
                RegimeTributario.NORMAL, CONFIG);
        assertThat(t.basePis()).isEqualByComparingTo("0");
        assertThat(t.valorPis()).isEqualByComparingTo("0");
        assertThat(t.baseCofins()).isEqualByComparingTo("0");
        assertThat(t.valorCofins()).isEqualByComparingTo("0");
        assertThat(t.cstPis()).isEqualTo("04");
    }
}
