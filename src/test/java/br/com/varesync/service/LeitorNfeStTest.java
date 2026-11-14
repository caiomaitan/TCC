package br.com.varesync.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ST da NF-e de compra (F22.4): os grupos ICMS60, ICMSSN500, ICMS10 e ICMS70, e o ST por unidade de venda. */
class LeitorNfeStTest {

    @Test
    void leOStDosGruposDoIcms() throws Exception {
        LeitorNfe.Nota nota = LeitorNfe.ler(LeitorNfeTest.recurso("nfe-st.xml"));

        LeitorNfe.StNota icms60 = nota.itens().get(0).fiscal().st();
        assertThat(icms60.base()).isEqualByComparingTo("60.00");
        assertThat(icms60.aliquota()).isEqualByComparingTo("18");
        assertThat(icms60.icmsSubstituto()).isEqualByComparingTo("2.40");
        assertThat(icms60.icmsRetido()).isEqualByComparingTo("3.60");

        LeitorNfe.StNota icms10 = nota.itens().get(1).fiscal().st();
        assertThat(icms10.base()).as("vBCST").isEqualByComparingTo("84.00");
        assertThat(icms10.aliquota()).as("pICMSST").isEqualByComparingTo("18");
        assertThat(icms10.icmsSubstituto()).as("vICMS próprio").isEqualByComparingTo("10.80");
        assertThat(icms10.icmsRetido()).as("vICMSST").isEqualByComparingTo("4.32");

        LeitorNfe.StNota icms70 = nota.itens().get(2).fiscal().st();
        assertThat(icms70.base()).isEqualByComparingTo("240.00");
        assertThat(icms70.icmsRetido()).isEqualByComparingTo("23.20");

        assertThat(nota.itens().get(3).fiscal().st()).as("ICMS00 não tem ST").isNull();
        assertThat(nota.itens().get(0).fiscal().ncm()).isEqualTo("22030000");
    }

    @Test
    void grupoComStSemValoresFicaSemSt() throws Exception {
        // ICMSSN500 da nfe-fiscal.xml só com orig e CSOSN.
        LeitorNfe.Nota nota = LeitorNfe.ler(LeitorNfeTest.recurso("nfe-fiscal.xml"));
        assertThat(nota.itens().get(1).fiscal().st()).isNull();
    }

    @Test
    void stPorUnidadeEmQuatroCasas() {
        LeitorNfe.StNota st = new LeitorNfe.StNota(new BigDecimal("60.00"), new BigDecimal("18.0000"),
                new BigDecimal("2.40"), new BigDecimal("3.60"));
        var unitario = FiscalService.stPorUnidade(st, new BigDecimal("12"));
        assertThat(unitario.baseUnitaria()).isEqualByComparingTo("5.0000");
        assertThat(unitario.baseUnitaria().scale()).isEqualTo(4);
        assertThat(unitario.icmsRetidoUnitario()).isEqualByComparingTo("0.3000");
        assertThat(unitario.icmsSubstitutoUnitario()).isEqualByComparingTo("0.2000");
        assertThat(unitario.aliquota()).isEqualByComparingTo("18.00");
        var setenta = FiscalService.stPorUnidade(new LeitorNfe.StNota(new BigDecimal("240.00"), null,
                new BigDecimal("20.00"), new BigDecimal("23.20")), new BigDecimal("24"));
        assertThat(setenta.icmsSubstitutoUnitario()).isEqualByComparingTo("0.8333");
        assertThat(setenta.icmsRetidoUnitario()).isEqualByComparingTo("0.9667");
        assertThat(setenta.aliquota()).isNull();
        assertThat(FiscalService.stPorUnidade(st, BigDecimal.ZERO)).isNull();
        assertThat(FiscalService.descreverSt(FiscalService.stPorUnidade(st, new BigDecimal("12"))))
                .isEqualTo("base R$ 5,0000, alíquota 18,00%, ICMS próprio R$ 0,2000, retido R$ 0,3000");
    }
}
