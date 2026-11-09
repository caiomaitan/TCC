package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import br.com.varesync.util.Formatos;
import java.util.List;

/** Regra 7: CST 20 ou 70 sem o percentual de redução da base; redução em outro CST (ou com CSOSN). */
public final class Regra07ReducaoBase implements RegraFiscal {

    @Override
    public int numero() {
        return 7;
    }

    @Override
    public String titulo() {
        return "Redução da base do ICMS";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null) {
            return List.of();
        }
        boolean comReducao = "20".equals(p.cstIcms()) || "70".equals(p.cstIcms());
        boolean informada = RegraFiscal.positivo(p.reducaoBaseIcms());
        if (comReducao && !informada) {
            return List.of(erro("CST " + p.cstIcms() + " tem redução da base do ICMS, mas o percentual não foi "
                    + "informado."));
        }
        if (!comReducao && informada) {
            return List.of(erro("Redução da base de " + Formatos.percentual(p.reducaoBaseIcms()) + " com "
                    + p.situacaoIcms() + ": a redução só vale nos CST 20 e 70."));
        }
        return List.of();
    }
}
