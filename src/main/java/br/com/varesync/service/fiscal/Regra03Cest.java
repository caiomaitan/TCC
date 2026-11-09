package br.com.varesync.service.fiscal;

import br.com.varesync.service.ValidacaoFiscal;
import java.util.List;

/**
 * Regra 3: CEST fora do formato (7 dígitos); com a tabela CEST importada, CEST que não existe nela ou que não
 * corresponde ao NCM do produto (a correspondência só é conferida com NCM válido).
 */
public final class Regra03Cest implements RegraFiscal {

    @Override
    public int numero() {
        return 3;
    }

    @Override
    public String titulo() {
        return "CEST inválido ou de outro NCM";
    }

    /** 7 dígitos, sem pontos. */
    static boolean formatoValido(String cest) {
        return cest != null && cest.matches("\\d{7}");
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        String cest = item.cest();
        if (cest == null || cest.isBlank()) {
            return List.of();
        }
        if (!formatoValido(cest)) {
            return List.of(erro("CEST \"" + cest + "\" fora do formato: são 7 dígitos (ex.: 03.021.00)."));
        }
        String formatado = ValidacaoFiscal.formatarCest(cest);
        if (Boolean.FALSE.equals(item.cestNaTabela())) {
            return List.of(erro("O CEST " + formatado + " não existe na tabela CEST importada."));
        }
        if (Boolean.FALSE.equals(item.cestCorrespondeAoNcm())) {
            return List.of(erro("O CEST " + formatado + " não corresponde ao NCM "
                    + ValidacaoFiscal.formatarNcm(item.ncm()) + " na tabela CEST importada."));
        }
        return List.of();
    }
}
