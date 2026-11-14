package br.com.varesync.service.fiscal;

import br.com.varesync.service.ValidacaoFiscal;
import java.util.List;

/**
 * Regra 15 (aviso, F22.5): NCM de produto ativo sem linha vigente na tabela IBPT importada; a venda sai sem os
 * tributos aproximados. Só com a tabela importada e o NCM no formato.
 */
public final class Regra15Ibpt implements RegraFiscal {

    @Override
    public int numero() {
        return 15;
    }

    @Override
    public String titulo() {
        return "Tabela IBPT";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        if (!Boolean.FALSE.equals(item.ibptVigente())) {
            return List.of();
        }
        return List.of(aviso("O NCM " + ValidacaoFiscal.formatarNcm(item.ncm()) + " não tem linha vigente na tabela "
                + "IBPT: a venda sai sem os tributos aproximados. Importe a tabela atualizada."));
    }
}
