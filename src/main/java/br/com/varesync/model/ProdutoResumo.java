package br.com.varesync.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Linha da busca de produtos (6.3): código, nome, categoria, preço, estoque e última alteração. */
public record ProdutoResumo(long id, String nome, String categoriaNome, String unidade, boolean unidadeFracionada,
                            BigDecimal precoVenda, BigDecimal estoque, boolean ativo, OffsetDateTime atualizadoEm,
                            String atualizadoPorNome) {
}
