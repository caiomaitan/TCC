package br.com.varesync.service.fiscal;

import br.com.varesync.service.ValidacaoFiscal;
import java.util.List;

/**
 * Regra 13 (aviso, não erro): produtos ativos com o mesmo NCM em perfis diferentes. Aparece em cada um deles, com os
 * nomes dos outros perfis.
 */
public final class Regra13NcmEmPerfis implements RegraFiscal {

    @Override
    public int numero() {
        return 13;
    }

    @Override
    public String titulo() {
        return "Mesmo NCM em perfis diferentes";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        if (item.perfil() == null || item.ncm() == null || item.outrosPerfisDoNcm().isEmpty()) {
            return List.of();
        }
        List<String> outros = item.outrosPerfisDoNcm();
        return List.of(aviso("Outros produtos com o NCM " + ValidacaoFiscal.formatarNcm(item.ncm()) + " estão "
                + (outros.size() == 1 ? "no perfil \"" + outros.get(0) + "\""
                : "nos perfis " + String.join(", ", outros.stream().map(n -> "\"" + n + "\"").toList()))
                + ": confira se a tributação deve ser a mesma."));
    }
}
