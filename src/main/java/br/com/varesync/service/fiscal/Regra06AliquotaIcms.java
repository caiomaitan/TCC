package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import br.com.varesync.util.Formatos;
import java.util.List;

/**
 * Regra 6: situação sem destaque do ICMS (CST 40, 41 ou 60; CSOSN 102, 103, 300, 400 ou 500) com alíquota maior que
 * zero; CST tributado (00 ou 20) com alíquota zero.
 */
public final class Regra06AliquotaIcms implements RegraFiscal {

    @Override
    public int numero() {
        return 6;
    }

    @Override
    public String titulo() {
        return "Alíquota do ICMS";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null) {
            return List.of();
        }
        boolean semDestaque = p.cstIcms() != null ? CST_SEM_DESTAQUE.contains(p.cstIcms())
                : CSOSN_SEM_DESTAQUE.contains(p.csosn());
        if (semDestaque && RegraFiscal.positivo(p.aliquotaIcms())) {
            return List.of(erro(p.situacaoIcms() + " não tem destaque do ICMS, mas a alíquota é "
                    + Formatos.percentual(p.aliquotaIcms()) + ": deve ser 0."));
        }
        if (("00".equals(p.cstIcms()) || "20".equals(p.cstIcms())) && !RegraFiscal.positivo(p.aliquotaIcms())) {
            return List.of(erro("CST " + p.cstIcms() + " é tributado, mas a alíquota do ICMS é zero."));
        }
        return List.of();
    }
}
