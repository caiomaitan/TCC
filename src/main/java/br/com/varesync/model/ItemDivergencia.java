package br.com.varesync.model;

import br.com.varesync.util.Dinheiro;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Linha da aba Divergências e do relatório R13 (6.7, 7.1): estoque do sistema capturado na contagem, contado,
 * diferença (contado − sistema, calculada pelo banco) e o custo do item no momento da contagem. Os valores da
 * contagem são nulos enquanto o item não foi contado.
 */
public record ItemDivergencia(long itemId, long produtoId, String nome, String unidade, boolean unidadeFracionada,
                              BigDecimal estoqueSistema, BigDecimal quantidadeContada, BigDecimal diferenca,
                              BigDecimal custoUnitario, OffsetDateTime contadoEm, String contadoPorNome) {

    public boolean contado() {
        return quantidadeContada != null;
    }

    /** Diferença × custo do item, em reais (2 casas); nulo se não contado. Sobra positiva, falta negativa. */
    public BigDecimal valorDiferenca() {
        return contado() ? Dinheiro.multiplicar(diferenca, custoUnitario) : null;
    }

    /** Contado e com diferença diferente de zero: é o que gera movimento ao aplicar. */
    public boolean temDiferenca() {
        return contado() && diferenca.signum() != 0;
    }
}
