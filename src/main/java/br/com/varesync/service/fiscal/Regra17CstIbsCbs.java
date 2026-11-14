package br.com.varesync.service.fiscal;

import br.com.varesync.model.PerfilTributario;
import br.com.varesync.util.Formatos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Regra 17 (F22.5), só no regime normal: CST do IBS e da CBS que a NFC-e desta versão não aceita (fora de 000, 200 e
 * 410) e CST 200 sem a redução de alíquota (erros); redução preenchida fora do CST 200 (aviso, a redução é ignorada).
 * O perfil sem o CST fica com a regra 12 (aviso), como confirmado pelo usuário.
 */
public final class Regra17CstIbsCbs implements RegraFiscal {

    public static final Set<String> CST_ACEITOS = Set.of("000", "200", "410");

    @Override
    public int numero() {
        return 17;
    }

    @Override
    public String titulo() {
        return "CST do IBS e da CBS";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        PerfilTributario p = item.perfil();
        if (p == null || contexto.simples() || p.cstIbsCbs() == null || p.cstIbsCbs().isBlank()) {
            return List.of();
        }
        List<Inconsistencia> achados = new ArrayList<>();
        boolean reduzido = "200".equals(p.cstIbsCbs());
        boolean comReducao = RegraFiscal.positivo(p.reducaoAliquotaIbsCbs());
        if (!CST_ACEITOS.contains(p.cstIbsCbs())) {
            achados.add(erro("CST " + p.cstIbsCbs() + " do IBS e da CBS não é aceito na NFC-e desta versão (só 000, "
                    + "200 e 410): o item fica sem IBS e CBS."));
        }
        if (reduzido && !comReducao) {
            achados.add(erro("CST 200 (alíquota reduzida) sem a redução de alíquota do IBS e da CBS no perfil: o "
                    + "cálculo usa a alíquota cheia."));
        }
        if (!reduzido && comReducao) {
            achados.add(aviso("Redução de " + Formatos.percentual(p.reducaoAliquotaIbsCbs()) + " no CST "
                    + p.cstIbsCbs() + ": a redução só vale no CST 200 e é ignorada."));
        }
        return achados;
    }
}
