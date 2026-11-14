package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import java.util.List;
import java.util.Set;

/**
 * Regra 14 (F22.5): situação do ICMS que a NFC-e desta versão não aceita: CST fora de 00, 20, 40, 41 e 60; CSOSN fora
 * de 102, 103, 300, 400 e 500. Nos dois regimes (o CST no Simples, e o contrário, já são a regra 4).
 */
public final class Regra14SituacaoIcmsNfce implements RegraFiscal {

    public static final Set<String> CST_ACEITOS = Set.of("00", "20", "40", "41", "60");
    public static final Set<String> CSOSN_ACEITOS = Set.of("102", "103", "300", "400", "500");

    @Override
    public int numero() {
        return 14;
    }

    @Override
    public String titulo() {
        return "Situação do ICMS na NFC-e";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null) {
            return List.of();
        }
        if (p.usaCsosn() ? CSOSN_ACEITOS.contains(p.csosn()) : CST_ACEITOS.contains(p.cstIcms())) {
            return List.of();
        }
        return List.of(erro(p.situacaoIcms() + " não é aceito na NFC-e desta versão (só "
                + (p.usaCsosn() ? "CSOSN 102, 103, 300, 400 e 500" : "CST 00, 20, 40, 41 e 60") + ")."));
    }
}
