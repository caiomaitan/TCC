package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.util.List;

/** Regra 9: produto com ST (CST 60 ou CSOSN 500) sem CEST. */
public final class Regra09StSemCest implements RegraFiscal {

    @Override
    public int numero() {
        return 9;
    }

    @Override
    public String titulo() {
        return "ST sem CEST";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null || !RegraFiscal.comSt(p) || (item.cest() != null && !item.cest().isBlank())) {
            return List.of();
        }
        return List.of(erro("Mercadoria com ST (" + p.situacaoIcms() + ") sem CEST."));
    }
}
