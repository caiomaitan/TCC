package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * Tributos calculados de um item vendido ({@code venda_item_tributo}, F22.2), com a cópia do cadastro fiscal do
 * momento da venda. Valores em 2 casas; os grupos que não se aplicam ficam nulos: o ST retido sem o dado no produto,
 * o IBS e a CBS fora do regime normal ou sem o CST no perfil, os tributos aproximados sem a linha IBPT vigente.
 */
public record TributoItem(String ncm, String cest, int origem, String cfop, String cstIcms, String csosn,
                          String codigoBeneficio, String cstPis, String cstCofins, String cstIbsCbs, String cclassTrib,
                          BigDecimal baseIcms, BigDecimal aliquotaIcms, BigDecimal reducaoBaseIcms,
                          BigDecimal valorIcms, BigDecimal aliquotaFcp, BigDecimal valorFcp,
                          BigDecimal baseStRetido, BigDecimal aliquotaSt, BigDecimal icmsSubstituto,
                          BigDecimal icmsStRetido,
                          BigDecimal basePis, BigDecimal aliquotaPis, BigDecimal valorPis,
                          BigDecimal baseCofins, BigDecimal aliquotaCofins, BigDecimal valorCofins,
                          BigDecimal baseIbsCbs, BigDecimal aliquotaCbs, BigDecimal aliquotaIbsUf,
                          BigDecimal aliquotaIbsMun, BigDecimal reducaoAliquotaIbsCbs, BigDecimal valorCbs,
                          BigDecimal valorIbsUf, BigDecimal valorIbsMun,
                          BigDecimal tribFederal, BigDecimal tribEstadual, BigDecimal tribMunicipal,
                          String ibptVersao) {

    /** Soma dos três tributos aproximados; nula sem a tabela IBPT. */
    public BigDecimal tributosAproximados() {
        return tribFederal == null ? null : tribFederal.add(tribEstadual).add(tribMunicipal);
    }
}
