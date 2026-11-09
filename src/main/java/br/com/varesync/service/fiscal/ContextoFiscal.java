package br.com.varesync.service.fiscal;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.RegimeTributario;

/**
 * Dados da empresa que as regras do R30 usam (F19.1).
 *
 * @param uf sigla da UF da loja; vazia = não informada (a regra 8 não confere a UF do código de benefício)
 */
public record ContextoFiscal(RegimeTributario regime, String uf) {

    public ContextoFiscal {
        regime = regime == null ? RegimeTributario.SIMPLES : regime;
        uf = uf == null ? "" : uf.strip();
    }

    public static ContextoFiscal da(Configuracoes.Loja loja) {
        return new ContextoFiscal(loja.regime(), loja.uf());
    }

    public boolean simples() {
        return regime == RegimeTributario.SIMPLES;
    }
}
