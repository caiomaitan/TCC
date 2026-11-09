package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.util.List;

/**
 * Regra 12 (aviso), só no regime normal: perfil sem o CST do IBS e da CBS ou sem o cClassTrib, ou cClassTrib que não
 * começa pelo CST. No Simples, quem não optou pelo regime regular do IBS e da CBS não informa esses campos (NT
 * 2026.005).
 */
public final class Regra12IbsCbs implements RegraFiscal {

    @Override
    public int numero() {
        return 12;
    }

    @Override
    public String titulo() {
        return "IBS e CBS";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null || contexto.simples()) {
            return List.of();
        }
        boolean semCst = p.cstIbsCbs() == null || p.cstIbsCbs().isBlank();
        boolean semClass = p.cclassTrib() == null || p.cclassTrib().isBlank();
        if (semCst || semClass) {
            return List.of(aviso("Perfil sem " + (semCst && semClass ? "o CST do IBS e da CBS e o cClassTrib"
                    : semCst ? "o CST do IBS e da CBS" : "o cClassTrib")
                    + ", exigidos na NF-e e na NFC-e do regime normal desde 2026."));
        }
        if (!p.cclassTrib().startsWith(p.cstIbsCbs())) {
            return List.of(aviso("cClassTrib " + p.cclassTrib() + " não começa pelo CST do IBS e da CBS ("
                    + p.cstIbsCbs() + ")."));
        }
        return List.of();
    }
}
