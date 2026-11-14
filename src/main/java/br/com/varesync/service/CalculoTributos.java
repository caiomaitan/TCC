package br.com.varesync.service;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.ItemTributavel;
import br.com.varesync.model.ItemTributavel.Ibpt;
import br.com.varesync.model.ItemTributavel.StRetido;
import br.com.varesync.model.PerfilTributario;
import br.com.varesync.model.RegimeTributario;
import br.com.varesync.model.TributoItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Tributos de um item vendido (F22.2), sem banco. O cálculo só registra: nenhum valor da venda muda.
 *
 * <ul>
 *   <li><b>Base:</b> o valor líquido do item (bruto − desconto − rateio). Cada valor de cada item em 2 casas, HALF_UP;
 *       o total da venda é a soma dos itens (a SEFAZ confere a soma).</li>
 *   <li><b>ICMS (CST):</b> 00 sobre o valor líquido; 20 sobre o valor líquido reduzido; 40 e 41 sem valores; 60 com o
 *       ST retido do produto × a quantidade. FCP só nos CST 00 e 20. Os outros CST ficam sem valores.</li>
 *   <li><b>ICMS (CSOSN):</b> 500 com o ST retido; os outros sem valores. Sem FCP.</li>
 *   <li><b>PIS e COFINS:</b> CST 01 e 02 sobre o valor líquido menos o ICMS (com a exclusão ligada, Tema 69 do STF);
 *       os outros CST com base e valor zero.</li>
 *   <li><b>IBS e CBS:</b> só no regime normal e com o CST no perfil: base = valor líquido − ICMS − FCP − PIS − COFINS
 *       (NT 2025.002-RTC, campo UB16); alíquota efetiva = alíquota × (1 − redução ÷ 100), em 4 casas (como o
 *       {@code pAliqEfet} da NT).</li>
 *   <li><b>Tributos aproximados (Lei 12.741/2012):</b> valor líquido × as alíquotas da linha IBPT vigente do NCM; o
 *       federal de importados quando a origem é estrangeira (1, 2, 6 ou 7).</li>
 * </ul>
 */
public final class CalculoTributos {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final int ESCALA = 2;
    private static final int ESCALA_ALIQUOTA_EFETIVA = 4;
    private static final Set<Integer> ORIGENS_ESTRANGEIRAS = Set.of(1, 2, 6, 7);
    private static final Set<String> CST_PIS_COFINS_TRIBUTADOS = Set.of("01", "02");

    private CalculoTributos() {
    }

    public static TributoItem calcular(ItemTributavel e, RegimeTributario regime, Configuracoes.Tributos config) {
        PerfilTributario p = e.perfil();
        BigDecimal liquido = e.valorLiquido();
        BigDecimal zero = BigDecimal.ZERO.setScale(ESCALA);

        // ICMS próprio, FCP e ST retido
        BigDecimal baseIcms = zero;
        BigDecimal valorIcms = zero;
        BigDecimal aliquotaFcp = BigDecimal.ZERO.setScale(ESCALA);
        BigDecimal valorFcp = zero;
        boolean comSt;
        if (p.usaCsosn()) {
            comSt = "500".equals(p.csosn());
        } else {
            String cst = p.cstIcms();
            comSt = "60".equals(cst);
            if ("00".equals(cst) || "20".equals(cst)) {
                baseIcms = "20".equals(cst) && p.reducaoBaseIcms() != null
                        ? arredondar(liquido.multiply(CEM.subtract(p.reducaoBaseIcms())).divide(CEM))
                        : liquido.setScale(ESCALA, RoundingMode.HALF_UP);
                valorIcms = percentual(baseIcms, p.aliquotaIcms());
                aliquotaFcp = p.aliquotaFcp();
                valorFcp = percentual(baseIcms, p.aliquotaFcp());
            }
        }
        StRetido st = comSt && e.st() != null ? e.st() : StRetido.NENHUM;

        // PIS e COFINS
        BigDecimal basePisCofins = config.excluirIcmsBasePisCofins() ? liquido.subtract(valorIcms) : liquido;
        BigDecimal basePis = CST_PIS_COFINS_TRIBUTADOS.contains(p.cstPis()) ? arredondar(basePisCofins) : zero;
        BigDecimal valorPis = CST_PIS_COFINS_TRIBUTADOS.contains(p.cstPis()) ? percentual(basePis, p.aliquotaPis())
                : zero;
        BigDecimal baseCofins = CST_PIS_COFINS_TRIBUTADOS.contains(p.cstCofins()) ? arredondar(basePisCofins) : zero;
        BigDecimal valorCofins = CST_PIS_COFINS_TRIBUTADOS.contains(p.cstCofins())
                ? percentual(baseCofins, p.aliquotaCofins()) : zero;

        // IBS e CBS (regime normal, com o CST no perfil)
        BigDecimal baseIbsCbs = null;
        BigDecimal aliquotaCbs = null;
        BigDecimal aliquotaIbsUf = null;
        BigDecimal aliquotaIbsMun = null;
        BigDecimal reducao = null;
        BigDecimal valorCbs = null;
        BigDecimal valorIbsUf = null;
        BigDecimal valorIbsMun = null;
        if (regime == RegimeTributario.NORMAL && p.cstIbsCbs() != null) {
            baseIbsCbs = arredondar(liquido.subtract(valorIcms).subtract(valorFcp).subtract(valorPis)
                    .subtract(valorCofins)).max(zero);
            aliquotaCbs = config.aliquotaCbs();
            aliquotaIbsUf = config.aliquotaIbsUf();
            aliquotaIbsMun = config.aliquotaIbsMun();
            reducao = p.reducaoAliquotaIbsCbs();
            valorCbs = percentual(baseIbsCbs, efetiva(aliquotaCbs, reducao));
            valorIbsUf = percentual(baseIbsCbs, efetiva(aliquotaIbsUf, reducao));
            valorIbsMun = percentual(baseIbsCbs, efetiva(aliquotaIbsMun, reducao));
        }

        // Tributos aproximados (IBPT)
        Ibpt ibpt = e.ibpt();
        BigDecimal federal = null;
        BigDecimal estadual = null;
        BigDecimal municipal = null;
        if (ibpt != null) {
            federal = percentual(liquido, ORIGENS_ESTRANGEIRAS.contains(e.origem()) ? ibpt.importadosFederal()
                    : ibpt.nacionalFederal());
            estadual = percentual(liquido, ibpt.estadual());
            municipal = percentual(liquido, ibpt.municipal());
        }

        return new TributoItem(e.ncm(), e.cest(), e.origem(), p.cfop(), p.cstIcms(), p.csosn(), p.codigoBeneficio(),
                p.cstPis(), p.cstCofins(), p.cstIbsCbs(), p.cclassTrib(),
                baseIcms, p.aliquotaIcms(), p.reducaoBaseIcms(), valorIcms, aliquotaFcp, valorFcp,
                vezes(st.baseUnitaria(), e.quantidade()), st.aliquota(),
                vezes(st.icmsSubstitutoUnitario(), e.quantidade()), vezes(st.icmsRetidoUnitario(), e.quantidade()),
                basePis, p.aliquotaPis(), valorPis, baseCofins, p.aliquotaCofins(), valorCofins,
                baseIbsCbs, aliquotaCbs, aliquotaIbsUf, aliquotaIbsMun, reducao, valorCbs, valorIbsUf, valorIbsMun,
                federal, estadual, municipal, ibpt == null ? null : ibpt.versao());
    }

    /** Alíquota × (1 − redução ÷ 100), em 4 casas; sem redução, a própria alíquota. */
    static BigDecimal efetiva(BigDecimal aliquota, BigDecimal reducao) {
        if (reducao == null || reducao.signum() == 0) {
            return aliquota;
        }
        return aliquota.multiply(CEM.subtract(reducao)).divide(CEM, ESCALA_ALIQUOTA_EFETIVA, RoundingMode.HALF_UP);
    }

    /** round(base × % ÷ 100, 2). */
    private static BigDecimal percentual(BigDecimal base, BigDecimal aliquota) {
        BigDecimal pct = aliquota == null ? BigDecimal.ZERO : aliquota;
        return base.multiply(pct).divide(CEM, ESCALA, RoundingMode.HALF_UP);
    }

    private static BigDecimal vezes(BigDecimal unitario, BigDecimal quantidade) {
        return unitario == null ? null : arredondar(unitario.multiply(quantidade));
    }

    private static BigDecimal arredondar(BigDecimal valor) {
        return valor.setScale(ESCALA, RoundingMode.HALF_UP);
    }
}
