package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.util.List;

/** Regra 4: CSOSN no regime normal ou CST do ICMS no Simples Nacional. */
public final class Regra04SituacaoIcmsRegime implements RegraFiscal {

    @Override
    public int numero() {
        return 4;
    }

    @Override
    public String titulo() {
        return "Situação do ICMS fora do regime";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null) {
            return List.of();
        }
        if (p.csosn() != null && !contexto.simples()) {
            return List.of(erro("CSOSN " + p.csosn() + " com a empresa no regime normal: use o CST do ICMS."));
        }
        if (p.cstIcms() != null && contexto.simples()) {
            return List.of(erro("CST " + p.cstIcms() + " do ICMS com a empresa no Simples Nacional: use o CSOSN."));
        }
        return List.of();
    }
}
