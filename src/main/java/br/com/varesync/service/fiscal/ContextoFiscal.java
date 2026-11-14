package br.com.varesync.service.fiscal;

import br.com.varesync.model.Configuracoes;
import br.com.varesync.model.RegimeTributario;
import java.time.LocalDate;

/**
 * Dados da empresa que as regras do R30 usam (F19.1) e o dia de hoje, pelo {@code Clock} (a vigência da tabela IBPT,
 * regra 15).
 *
 * @param uf   sigla da UF da loja; vazia = não informada (a regra 8 não confere a UF do código de benefício)
 * @param hoje nulo = a regra 15 não confere a vigência
 */
public record ContextoFiscal(RegimeTributario regime, String uf, LocalDate hoje) {

    public ContextoFiscal {
        regime = regime == null ? RegimeTributario.SIMPLES : regime;
        uf = uf == null ? "" : uf.strip();
    }

    /** Sem o dia de hoje (regras da F19). */
    public ContextoFiscal(RegimeTributario regime, String uf) {
        this(regime, uf, null);
    }

    public static ContextoFiscal da(Configuracoes.Loja loja) {
        return new ContextoFiscal(loja.regime(), loja.uf());
    }

    public static ContextoFiscal da(Configuracoes.Loja loja, LocalDate hoje) {
        return new ContextoFiscal(loja.regime(), loja.uf(), hoje);
    }

    public boolean simples() {
        return regime == RegimeTributario.SIMPLES;
    }
}
