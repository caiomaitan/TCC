package br.com.varesync.model;

import java.util.Arrays;
import java.util.List;

/**
 * Ajuste manual de estoque (6.4): a quantidade é digitada positiva e o sinal vem do tipo.
 * As sugestões de motivo aparecem na tela; em entrada e saída o texto é livre, na perda vale a lista de
 * {@link MotivoPerda}.
 */
public enum TipoAjuste {
    ENTRADA("Entrada", TipoMovimento.AJUSTE_ENTRADA,
            List.of("Acerto de contagem", "Devolução de cliente", "Bonificação de fornecedor", "Estoque inicial")),
    SAIDA("Saída", TipoMovimento.AJUSTE_SAIDA,
            List.of("Acerto de contagem", "Devolução ao fornecedor", "Transferência", "Brinde")),
    PERDA("Perda", TipoMovimento.PERDA,
            Arrays.stream(MotivoPerda.values()).map(MotivoPerda::rotulo).toList());

    private final String rotulo;
    private final TipoMovimento tipoMovimento;
    private final List<String> sugestoes;

    TipoAjuste(String rotulo, TipoMovimento tipoMovimento, List<String> sugestoes) {
        this.rotulo = rotulo;
        this.tipoMovimento = tipoMovimento;
        this.sugestoes = sugestoes;
    }

    public TipoMovimento tipoMovimento() {
        return tipoMovimento;
    }

    /** Motivos oferecidos na tela. */
    public List<String> sugestoes() {
        return sugestoes;
    }

    /** Na perda o motivo é escolhido da lista; nos demais, texto livre. */
    public boolean motivoDaLista() {
        return this == PERDA;
    }

    @Override
    public String toString() {
        return rotulo;
    }
}
