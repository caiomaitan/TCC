package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.util.List;

/**
 * Regra 16 (aviso, F22.5): produto ativo com CST 60 ou CSOSN 500 sem o ST retido, que vem da NF-e de compra (F22.4).
 * Os campos são opcionais no leiaute da NFC-e, mas algumas UFs os exigem.
 */
public final class Regra16StRetido implements RegraFiscal {

    @Override
    public int numero() {
        return 16;
    }

    @Override
    public String titulo() {
        return "ST retido";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null || !RegraFiscal.comSt(p) || item.stRetido()) {
            return List.of();
        }
        return List.of(aviso(p.situacaoIcms() + " sem o ICMS ST retido: lance a NF-e de compra pelo XML para "
                + "preenchê-lo (algumas UFs o exigem na NFC-e)."));
    }
}
