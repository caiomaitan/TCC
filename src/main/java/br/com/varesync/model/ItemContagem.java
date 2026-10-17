package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Linha da aba Contagem (6.7). <b>Contagem cega</b>: não tem o estoque do sistema. {@code codigoBarras} é o
 * principal (nulo se o produto não tem); contado, quando e por quem são nulos enquanto o item não foi contado.
 */
public record ItemContagem(long itemId, long produtoId, String codigoBarras, String nome, String unidade,
                           boolean unidadeFracionada, BigDecimal quantidadeContada, OffsetDateTime contadoEm,
                           String contadoPorNome) {

    public boolean contado() {
        return quantidadeContada != null;
    }
}
