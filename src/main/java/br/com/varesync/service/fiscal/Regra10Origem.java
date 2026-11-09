package br.com.varesync.service.fiscal;

import java.util.List;

/** Regra 10: origem da mercadoria não informada. */
public final class Regra10Origem implements RegraFiscal {

    @Override
    public int numero() {
        return 10;
    }

    @Override
    public String titulo() {
        return "Origem ausente";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        return item.origem() == null ? List.of(erro("Origem da mercadoria não informada.")) : List.of();
    }
}
