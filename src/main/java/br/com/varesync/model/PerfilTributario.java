package br.com.varesync.model;

import br.com.varesync.util.Formatos;
import java.math.BigDecimal;

/**
 * Perfil tributário gravado (F19.2). A situação do ICMS é o CST ({@code cstIcms}, regime normal) ou o CSOSN
 * (Simples Nacional): sempre um dos dois, nunca os dois.
 *
 * @param reducaoBaseIcms        percentual de redução da base do ICMS; nulo = sem redução
 * @param codigoBeneficio        cBenef; nulo = sem código
 * @param cstIbsCbs              reforma tributária, opcional
 * @param cclassTrib             reforma tributária, opcional
 * @param produtos               quantos produtos usam o perfil
 * @param aliquotaFcp            Fundo de Combate à Pobreza, % (F22.1; só nos CST 00 e 20)
 * @param reducaoAliquotaIbsCbs  redução das alíquotas do IBS e da CBS, % (F22.1); nula = sem redução
 */
public record PerfilTributario(long id, String nome, String cfop, String cstIcms, String csosn,
                               BigDecimal aliquotaIcms, BigDecimal reducaoBaseIcms, String cstPis,
                               BigDecimal aliquotaPis, String cstCofins, BigDecimal aliquotaCofins,
                               String codigoBeneficio, String cstIbsCbs, String cclassTrib, boolean ativo,
                               long produtos, BigDecimal aliquotaFcp, BigDecimal reducaoAliquotaIbsCbs) {

    public PerfilTributario {
        aliquotaFcp = aliquotaFcp == null ? BigDecimal.ZERO : aliquotaFcp;
    }

    /** Sem FCP e sem redução de alíquota do IBS e da CBS (perfis da F19). */
    public PerfilTributario(long id, String nome, String cfop, String cstIcms, String csosn,
                            BigDecimal aliquotaIcms, BigDecimal reducaoBaseIcms, String cstPis,
                            BigDecimal aliquotaPis, String cstCofins, BigDecimal aliquotaCofins,
                            String codigoBeneficio, String cstIbsCbs, String cclassTrib, boolean ativo,
                            long produtos) {
        this(id, nome, cfop, cstIcms, csosn, aliquotaIcms, reducaoBaseIcms, cstPis, aliquotaPis, cstCofins,
                aliquotaCofins, codigoBeneficio, cstIbsCbs, cclassTrib, ativo, produtos, BigDecimal.ZERO, null);
    }

    public boolean usaCsosn() {
        return csosn != null;
    }

    /** "CST 00" ou "CSOSN 102". */
    public String situacaoIcms() {
        return usaCsosn() ? "CSOSN " + csosn : "CST " + cstIcms;
    }

    /** "CFOP 5102 · CSOSN 102 · ICMS 0,00%": resumo para a lista. */
    public String resumo() {
        return "CFOP " + cfop + " · " + situacaoIcms() + " · ICMS " + Formatos.percentual(aliquotaIcms)
                + (reducaoBaseIcms == null ? "" : " (redução " + Formatos.percentual(reducaoBaseIcms) + ")")
                + (aliquotaFcp.signum() == 0 ? "" : " · FCP " + Formatos.percentual(aliquotaFcp))
                + " · PIS " + cstPis + " · COFINS " + cstCofins;
    }

    @Override
    public String toString() {
        return ativo ? nome : nome + " (inativo)";
    }
}
