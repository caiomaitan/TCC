package br.com.varesync.service.fiscal;

import br.com.varesync.service.ValidacaoFiscal;
import java.util.List;

/** Regra 2: NCM fora do formato (8 dígitos) ou, com a tabela NCM importada, inexistente nela. */
public final class Regra02Ncm implements RegraFiscal {

    @Override
    public int numero() {
        return 2;
    }

    @Override
    public String titulo() {
        return "NCM inválido ou fora da tabela";
    }

    /** 8 dígitos, sem pontos. */
    static boolean formatoValido(String ncm) {
        return ncm != null && ncm.matches("\\d{8}");
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        String ncm = item.ncm();
        if (ncm == null || ncm.isBlank()) {
            return List.of();
        }
        if (!formatoValido(ncm)) {
            return List.of(erro("NCM \"" + ncm + "\" fora do formato: são 8 dígitos (ex.: 2203.00.00)."));
        }
        if (Boolean.FALSE.equals(item.ncmNaTabela())) {
            return List.of(erro("O NCM " + ValidacaoFiscal.formatarNcm(ncm) + " não existe na tabela NCM importada."));
        }
        return List.of();
    }
}
