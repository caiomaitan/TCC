package br.com.varesync.service.fiscal;

import java.util.ArrayList;
import java.util.List;

/** Regra 1: produto ativo sem NCM ou sem perfil tributário (uma mensagem para cada). */
public final class Regra01SemNcmOuPerfil implements RegraFiscal {

    @Override
    public int numero() {
        return 1;
    }

    @Override
    public String titulo() {
        return "Sem NCM ou sem perfil";
    }

    @Override
    public List<Inconsistencia> verificar(ItemFiscal item, ContextoFiscal contexto) {
        List<Inconsistencia> achados = new ArrayList<>();
        if (item.ncm() == null || item.ncm().isBlank()) {
            achados.add(erro("Sem NCM."));
        }
        if (item.perfil() == null) {
            achados.add(erro("Sem perfil tributário."));
        }
        return achados;
    }
}
