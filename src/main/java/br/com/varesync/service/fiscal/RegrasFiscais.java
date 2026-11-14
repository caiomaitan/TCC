package br.com.varesync.service.fiscal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** As 17 regras do R30 (F19.4 e F22.5), na ordem dos números. */
public final class RegrasFiscais {

    private static final List<RegraFiscal> TODAS = List.of(new Regra01SemNcmOuPerfil(), new Regra02Ncm(),
            new Regra03Cest(), new Regra04SituacaoIcmsRegime(), new Regra05Cfop(), new Regra06AliquotaIcms(),
            new Regra07ReducaoBase(), new Regra08CodigoBeneficio(), new Regra09StSemCest(), new Regra10Origem(),
            new Regra11PisCofins(), new Regra12IbsCbs(), new Regra13NcmEmPerfis(), new Regra14SituacaoIcmsNfce(),
            new Regra15Ibpt(), new Regra16StRetido(), new Regra17CstIbsCbs());

    private RegrasFiscais() {
    }

    public static List<RegraFiscal> todas() {
        return TODAS;
    }

    public static Optional<RegraFiscal> regra(int numero) {
        return TODAS.stream().filter(r -> r.numero() == numero).findFirst();
    }

    /** Tudo o que as regras acham no produto, na ordem das regras. */
    public static List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        List<Inconsistencia> achados = new ArrayList<>();
        for (RegraFiscal regra : TODAS) {
            achados.addAll(regra.verificar(item, contexto));
        }
        return achados;
    }
}
