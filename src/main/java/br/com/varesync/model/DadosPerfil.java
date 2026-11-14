package br.com.varesync.model;

import java.math.BigDecimal;

/**
 * O que a tela do perfil tributário envia para gravar (F19.2). {@code FiscalService} normaliza e confere só o formato
 * de cada campo; as combinações erradas aparecem no R30.
 *
 * @param usaCsosn              a situação do ICMS é o CSOSN (Simples Nacional); senão, o CST
 * @param situacaoIcms          o CST (2 dígitos) ou o CSOSN (3 dígitos)
 * @param reducaoBaseIcms       nula ou zero = sem redução
 * @param codigoBeneficio       vazio = sem código
 * @param aliquotaFcp           FCP, % (F22.1); nula = zero
 * @param reducaoAliquotaIbsCbs redução das alíquotas do IBS e da CBS, % (F22.1); nula ou zero = sem redução
 */
public record DadosPerfil(String nome, String cfop, boolean usaCsosn, String situacaoIcms, BigDecimal aliquotaIcms,
                          BigDecimal reducaoBaseIcms, String cstPis, BigDecimal aliquotaPis, String cstCofins,
                          BigDecimal aliquotaCofins, String codigoBeneficio, String cstIbsCbs, String cclassTrib,
                          boolean ativo, BigDecimal aliquotaFcp, BigDecimal reducaoAliquotaIbsCbs) {

    /** Sem FCP e sem redução de alíquota do IBS e da CBS. */
    public DadosPerfil(String nome, String cfop, boolean usaCsosn, String situacaoIcms, BigDecimal aliquotaIcms,
                       BigDecimal reducaoBaseIcms, String cstPis, BigDecimal aliquotaPis, String cstCofins,
                       BigDecimal aliquotaCofins, String codigoBeneficio, String cstIbsCbs, String cclassTrib,
                       boolean ativo) {
        this(nome, cfop, usaCsosn, situacaoIcms, aliquotaIcms, reducaoBaseIcms, cstPis, aliquotaPis, cstCofins,
                aliquotaCofins, codigoBeneficio, cstIbsCbs, cclassTrib, ativo, null, null);
    }
}
